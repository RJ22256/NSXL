// nsxl-native: the Windows-only jobs NSXL can't do from Electron.
//
// Protocol: NSXL writes one JSON object per line to stdin, e.g. {"id":3,"cmd":"vol.set","v":40}.
// The helper answers {"id":3,"ok":true,"data":...} or {"id":3,"ok":false,"error":"..."},
// and sends events without an id, e.g. {"event":"pad","combo":"viewmenu"}.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Management;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;
using Windows.Devices.Radios;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace Nsxl.Native
{
    internal static class Program
    {
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
        static readonly object OutLock = new object();

        static void Send(object o)
        {
            string line = Json.Serialize(o);
            lock (OutLock) { Console.Out.WriteLine(line); Console.Out.Flush(); }
        }

        static void Main()
        {
            // a windowless app (no console for Windows to close under it): talk over the pipes NSXL gave us
            var utf8 = new UTF8Encoding(false);
            Console.SetOut(new StreamWriter(Console.OpenStandardOutput(), utf8) { AutoFlush = true });
            Console.SetError(new StreamWriter(Console.OpenStandardError(), utf8) { AutoFlush = true });
            Console.SetIn(new StreamReader(Console.OpenStandardInput(), utf8));
            // anything that slips through is written to NSXL's log instead of silently ending the helper
            AppDomain.CurrentDomain.UnhandledException += (s, e) => { try { Console.Error.WriteLine("crash: " + e.ExceptionObject); } catch { } };
            TaskScheduler.UnobservedTaskException += (s, e) => { try { Console.Error.WriteLine("task error: " + e.Exception.GetBaseException().Message); } catch { } e.SetObserved(); };
            Pads.Start();
            PsPads.Start();
            Media.Watch();
            string line;
            while ((line = Console.In.ReadLine()) != null)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                Dictionary<string, object> msg;
                try { msg = Json.Deserialize<Dictionary<string, object>>(line); } catch { continue; }
                Task.Run(() => Handle(msg));
            }
            Environment.Exit(0);   // NSXL closed: stop too
        }

        static async Task Handle(Dictionary<string, object> m)
        {
            object id = m.ContainsKey("id") ? m["id"] : null;
            try
            {
                object data = await Run(Str(m, "cmd"), m);
                Send(new Dictionary<string, object> { { "id", id }, { "ok", true }, { "data", data } });
            }
            catch (Exception e)
            {
                Send(new Dictionary<string, object> { { "id", id }, { "ok", false }, { "error", e.Message } });
            }
        }

        static string Str(Dictionary<string, object> m, string k) { object v; return m.TryGetValue(k, out v) && v != null ? v.ToString() : ""; }
        static int Int(Dictionary<string, object> m, string k) { object v; return m.TryGetValue(k, out v) && v != null ? Convert.ToInt32(v) : 0; }
        static bool Bool(Dictionary<string, object> m, string k) { object v; return m.TryGetValue(k, out v) && v is bool && (bool)v; }

        static async Task<object> Run(string cmd, Dictionary<string, object> m)
        {
            switch (cmd)
            {
                case "ping": return "pong";
                // Bluetooth
                case "bt.radio": return await Bt.Radio();
                case "bt.power": return await Bt.Power(Bool(m, "on"));
                case "bt.paired": return await Bt.Paired();
                case "bt.scan": Bt.Scan(true); return true;
                case "bt.stop": Bt.Scan(false); return true;
                case "bt.pair": return await Bt.Pair(Str(m, "dev"));
                case "bt.unpair": return await Bt.Unpair(Str(m, "dev"));
                // sound and brightness
                case "vol.get": return Audio.Get();
                case "vol.set": Audio.Set(Int(m, "v")); return Audio.Get();
                case "vol.mute": Audio.ToggleMute(); return Audio.Get();
                case "bright.get": return Brightness.Get();
                case "bright.set": Brightness.Set(Int(m, "v")); return true;
                // whatever is playing (Spotify, a browser, ...)
                case "media.get": return await Media.Get(Bool(m, "art"));
                case "media.cmd": return await Media.Command(Str(m, "c"));
                // the game in front
                case "fg.get": return Foreground.Get();
                case "fg.set": return Foreground.Bring(Convert.ToInt64(m["hwnd"]));
                case "win.min": return Foreground.Minimise(Convert.ToInt64(m["hwnd"]));
                case "keys.xboxmode": return Foreground.XboxMode();
                case "proc.kill": Process.GetProcessById(Int(m, "pid")).Kill(); return true;
                case "proc.killdir": return Procs.KillIn(Str(m, "dir"));
                case "proc.list": return Procs.ListIn(Str(m, "dir"));
                default: throw new Exception("Unknown command " + cmd);
            }
        }

        internal static void Event(string name, Dictionary<string, object> extra)
        {
            var o = extra ?? new Dictionary<string, object>();
            o["event"] = name;
            Send(o);
        }
    }

    // ------------------------------------------------------------------ Bluetooth: search, pair, forget

    internal static class Bt
    {
        static DeviceWatcher watcher;
        const string CodMajor = "System.Devices.Aep.Bluetooth.Cod.Major";
        const string CodMinor = "System.Devices.Aep.Bluetooth.Cod.Minor";

        static readonly string[] PadWords = { "controller", "gamepad", "game pad", "joy-con", "dualsense", "dualshock", "xbox", "8bitdo", "gamesir", "pro con", "wireless controller", "joystick", "stadia", "flydigi" };

        static bool LooksLikePad(string name, object major, object minor)
        {
            try
            {
                if (major != null && Convert.ToInt32(major) == 5)          // peripheral
                {
                    int mi = minor != null ? Convert.ToInt32(minor) : 0;    // gamepad or joystick sub-type
                    if ((mi & 0x0F) == 1 || (mi & 0x0F) == 2) return true;
                }
            }
            catch { }
            string n = (name ?? "").ToLowerInvariant();
            return PadWords.Any(w => n.Contains(w));
        }

        public static async Task<object> Radio()
        {
            var access = await Windows.Devices.Radios.Radio.RequestAccessAsync();
            var radios = await Windows.Devices.Radios.Radio.GetRadiosAsync();
            var bt = radios.FirstOrDefault(r => r.Kind == RadioKind.Bluetooth);
            return new Dictionary<string, object> { { "present", bt != null }, { "on", bt != null && bt.State == RadioState.On }, { "access", access.ToString() } };
        }

        public static async Task<object> Power(bool on)
        {
            await Windows.Devices.Radios.Radio.RequestAccessAsync();
            var radios = await Windows.Devices.Radios.Radio.GetRadiosAsync();
            var bt = radios.FirstOrDefault(r => r.Kind == RadioKind.Bluetooth);
            if (bt == null) throw new Exception("This PC has no Bluetooth");
            var res = await bt.SetStateAsync(on ? RadioState.On : RadioState.Off);
            if (res != RadioAccessStatus.Allowed) throw new Exception("Windows didn't allow that (" + res + ")");
            return true;
        }

        public static async Task<object> Paired()
        {
            var list = new List<object>();
            var infos = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true));
            foreach (var info in infos)
            {
                bool connected = false, pad = false;
                try
                {
                    var dev = await BluetoothDevice.FromIdAsync(info.Id);
                    if (dev != null)
                    {
                        connected = dev.ConnectionStatus == BluetoothConnectionStatus.Connected;
                        var cod = dev.ClassOfDevice;
                        pad = (cod.MajorClass == BluetoothMajorClass.Peripheral && (cod.MinorClass == BluetoothMinorClass.PeripheralGamepad || cod.MinorClass == BluetoothMinorClass.PeripheralJoystick))
                              || LooksLikePad(info.Name, null, null);
                        dev.Dispose();
                    }
                }
                catch { pad = LooksLikePad(info.Name, null, null); }
                list.Add(new Dictionary<string, object> { { "addr", info.Id }, { "name", info.Name }, { "connected", connected }, { "pad", pad }, { "bonded", true } });
            }
            return list;
        }

        public static void Scan(bool on)
        {
            if (watcher != null)
            {
                try { if (watcher.Status == DeviceWatcherStatus.Started || watcher.Status == DeviceWatcherStatus.EnumerationCompleted) watcher.Stop(); } catch { }
                watcher = null;
            }
            if (!on) { Program.Event("bt.scan", new Dictionary<string, object> { { "scanning", false } }); return; }
            // Unpaired classic Bluetooth devices nearby (controllers are classic Bluetooth HID)
            string aqs = "System.Devices.Aep.ProtocolId:=\"{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}\" AND System.Devices.Aep.IsPaired:=System.StructuredQueryType.Boolean#False";
            watcher = DeviceInformation.CreateWatcher(aqs, new[] { CodMajor, CodMinor, "System.Devices.Aep.IsPresent" }, DeviceInformationKind.AssociationEndpoint);
            var w = watcher;
            w.Added += (s, info) => Found(info);
            w.Updated += (s, upd) => { };
            w.EnumerationCompleted += (s, o) => { };
            w.Stopped += (s, o) => Program.Event("bt.scan", new Dictionary<string, object> { { "scanning", false } });
            w.Start();
            Program.Event("bt.scan", new Dictionary<string, object> { { "scanning", true } });
            // Stop after a minute so the radio isn't left searching
            Task.Delay(60000).ContinueWith(t => { if (watcher == w) Scan(false); });
        }

        static void Found(DeviceInformation info)
        {
            if (string.IsNullOrWhiteSpace(info.Name)) return;
            object major = null, minor = null;
            info.Properties.TryGetValue(CodMajor, out major);
            info.Properties.TryGetValue(CodMinor, out minor);
            Program.Event("bt.found", new Dictionary<string, object> { { "addr", info.Id }, { "name", info.Name }, { "pad", LooksLikePad(info.Name, major, minor) } });
        }

        public static async Task<object> Pair(string id)
        {
            var info = await DeviceInformation.CreateFromIdAsync(id);
            if (info.Pairing.IsPaired) return "AlreadyPaired";
            var custom = info.Pairing.Custom;
            custom.PairingRequested += (s, a) =>
            {
                if (a.PairingKind == DevicePairingKinds.ProvidePin) a.Accept("0000");
                else a.Accept();
            };
            var res = await custom.PairAsync(DevicePairingKinds.ConfirmOnly | DevicePairingKinds.DisplayPin | DevicePairingKinds.ConfirmPinMatch | DevicePairingKinds.ProvidePin, DevicePairingProtectionLevel.Default);
            if (res.Status != DevicePairingResultStatus.Paired && res.Status != DevicePairingResultStatus.AlreadyPaired)
                throw new Exception("Pairing didn't finish (" + res.Status + "). Put the controller in pairing mode and try again.");
            return res.Status.ToString();
        }

        public static async Task<object> Unpair(string id)
        {
            var info = await DeviceInformation.CreateFromIdAsync(id);
            var res = await info.Pairing.UnpairAsync();
            if (res.Status != DeviceUnpairingResultStatus.Unpaired && res.Status != DeviceUnpairingResultStatus.AlreadyUnpaired)
                throw new Exception("Windows couldn't forget it (" + res.Status + ")");
            return true;
        }
    }

    // ------------------------------------------------------------------ volume (Core Audio)

    internal static class Audio
    {
        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumerator { }

        [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceEnumerator
        {
            int NotImpl1();
            [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppDevice);
        }

        [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
        }

        [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioEndpointVolume
        {
            int RegisterControlChangeNotify(IntPtr p);
            int UnregisterControlChangeNotify(IntPtr p);
            int GetChannelCount(out int c);
            int SetMasterVolumeLevel(float level, ref Guid ctx);
            int SetMasterVolumeLevelScalar(float level, ref Guid ctx);
            int GetMasterVolumeLevel(out float level);
            int GetMasterVolumeLevelScalar(out float level);
            int SetChannelVolumeLevel(uint ch, float level, ref Guid ctx);
            int SetChannelVolumeLevelScalar(uint ch, float level, ref Guid ctx);
            int GetChannelVolumeLevel(uint ch, out float level);
            int GetChannelVolumeLevelScalar(uint ch, out float level);
            int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid ctx);
            int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
        }

        static IAudioEndpointVolume Endpoint()
        {
            var en = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            IMMDevice dev;
            Marshal.ThrowExceptionForHR(en.GetDefaultAudioEndpoint(0 /*render*/, 1 /*multimedia*/, out dev));
            var iid = typeof(IAudioEndpointVolume).GUID;
            object o;
            Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23 /*CLSCTX_ALL*/, IntPtr.Zero, out o));
            return (IAudioEndpointVolume)o;
        }

        public static object Get()
        {
            var ep = Endpoint();
            float v; bool mute;
            ep.GetMasterVolumeLevelScalar(out v);
            ep.GetMute(out mute);
            return new Dictionary<string, object> { { "v", (int)Math.Round(v * 100) }, { "max", 100 }, { "muted", mute } };
        }

        public static void Set(int v)
        {
            var g = Guid.Empty;
            var ep = Endpoint();
            ep.SetMasterVolumeLevelScalar(Math.Max(0, Math.Min(100, v)) / 100f, ref g);
            if (v > 0) ep.SetMute(false, ref g);
        }

        public static void ToggleMute()
        {
            var g = Guid.Empty;
            var ep = Endpoint();
            bool m; ep.GetMute(out m);
            ep.SetMute(!m, ref g);
        }
    }

    // ------------------------------------------------------------------ screen brightness (laptops and handhelds)

    internal static class Brightness
    {
        public static object Get()
        {
            try
            {
                using (var s = new ManagementObjectSearcher("root\\WMI", "SELECT CurrentBrightness FROM WmiMonitorBrightness"))
                    foreach (ManagementObject o in s.Get())
                        return new Dictionary<string, object> { { "v", Convert.ToInt32(o["CurrentBrightness"]) }, { "max", 100 }, { "canWrite", true }, { "auto", false } };
            }
            catch { }
            return new Dictionary<string, object> { { "v", 0 }, { "max", 100 }, { "canWrite", false }, { "auto", false } };
        }

        public static void Set(int v)
        {
            using (var s = new ManagementObjectSearcher("root\\WMI", "SELECT * FROM WmiMonitorBrightnessMethods"))
                foreach (ManagementObject o in s.Get())
                    o.InvokeMethod("WmiSetBrightness", new object[] { (uint)1, (byte)Math.Max(0, Math.Min(100, v)) });
        }
    }

    // ------------------------------------------------------------------ now playing (Windows media sessions)

    internal static class Media
    {
        static async Task<GlobalSystemMediaTransportControlsSession> Session()
        {
            var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var all = mgr.GetSessions();
            var spotify = all.FirstOrDefault(s => (s.SourceAppUserModelId ?? "").IndexOf("spotify", StringComparison.OrdinalIgnoreCase) >= 0);
            return spotify ?? mgr.GetCurrentSession();
        }

        public static async Task<object> Get(bool withArt)
        {
            var s = await Session();
            if (s == null) return null;
            var p = await s.TryGetMediaPropertiesAsync();
            var info = s.GetPlaybackInfo();
            var tl = s.GetTimelineProperties();
            string art = null;
            if (withArt && p.Thumbnail != null)
            {
                try
                {
                    using (var stream = await p.Thumbnail.OpenReadAsync())
                    {
                        var reader = new DataReader(stream.GetInputStreamAt(0));
                        uint n = await reader.LoadAsync((uint)Math.Min(stream.Size, 4 * 1024 * 1024));
                        var bytes = new byte[n];
                        reader.ReadBytes(bytes);
                        art = "data:" + (string.IsNullOrEmpty(stream.ContentType) ? "image/png" : stream.ContentType) + ";base64," + Convert.ToBase64String(bytes);
                    }
                }
                catch { }
            }
            return new Dictionary<string, object> {
                { "app", s.SourceAppUserModelId }, { "title", p.Title }, { "artist", p.Artist }, { "album", p.AlbumTitle },
                { "playing", info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing },
                { "position", tl.Position.TotalSeconds }, { "duration", tl.EndTime.TotalSeconds }, { "updated", tl.LastUpdatedTime.ToUnixTimeMilliseconds() },
                { "art", art },
            };
        }

        // Tells NSXL when another app (Spotify, a browser…) starts or stops playing, so its ambient music can step aside.
        static bool? lastPlaying;
        static bool Ours(string id) =>
            id.IndexOf("com.nsxl", StringComparison.OrdinalIgnoreCase) >= 0 || id.IndexOf("NSXL", StringComparison.OrdinalIgnoreCase) >= 0
            || id.IndexOf("electron", StringComparison.OrdinalIgnoreCase) >= 0;

        public static void Watch()
        {
            Task.Run(async () =>
            {
                GlobalSystemMediaTransportControlsSessionManager mgr = null;
                while (true)
                {
                    try
                    {
                        if (mgr == null) mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
                        string app = null;
                        foreach (var s in mgr.GetSessions())
                        {
                            var id = s.SourceAppUserModelId ?? "";
                            if (Ours(id)) continue;
                            if (s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) { app = id; break; }
                        }
                        bool on = app != null;
                        if (lastPlaying != on)
                        {
                            lastPlaying = on;
                            Program.Event("media.playing", new Dictionary<string, object> { { "playing", on }, { "app", app ?? "" } });
                        }
                    }
                    catch { mgr = null; }   // media service restarted: ask again next time
                    await Task.Delay(1500);
                }
            });
        }

        public static async Task<object> Command(string c)
        {
            var s = await Session();
            if (s == null) throw new Exception("Nothing is playing");
            switch (c)
            {
                case "next": return await s.TrySkipNextAsync();
                case "prev": return await s.TrySkipPreviousAsync();
                case "play": return await s.TryPlayAsync();
                case "pause": return await s.TryPauseAsync();
                default: return await s.TryTogglePlayPauseAsync();
            }
        }
    }

    // ------------------------------------------------------------------ the game in front

    internal static class Foreground
    {
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int n);

        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
        [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);

        /// Brings a window (the quick menu) to the front even while a game has focus.
        /// Windows only lets the app with the last input do that; a tap of Alt counts as input.
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hWnd);
        [DllImport("user32.dll")] static extern bool ShowWindowAsync(IntPtr hWnd, int cmd);
        /// Win + F11: Windows' own shortcut that switches between Xbox mode and the desktop, no restart needed.
        public static bool XboxMode()
        {
            keybd_event(0x5B, 0, 0, UIntPtr.Zero);   // left Windows key
            keybd_event(0x7A, 0, 0, UIntPtr.Zero);   // F11
            Thread.Sleep(40);
            keybd_event(0x7A, 0, 2, UIntPtr.Zero);
            keybd_event(0x5B, 0, 2, UIntPtr.Zero);
            return true;
        }
        public static bool Minimise(long hwnd) { return hwnd != 0 && ShowWindowAsync(new IntPtr(hwnd), 6); }   // SW_MINIMIZE
        public static bool Bring(long hwnd)
        {
            if (hwnd != 0 && IsIconic(new IntPtr(hwnd))) ShowWindowAsync(new IntPtr(hwnd), 9);   // SW_RESTORE
            keybd_event(0x12, 0, 0, UIntPtr.Zero);
            keybd_event(0x12, 0, 2, UIntPtr.Zero);
            return SetForegroundWindow(new IntPtr(hwnd));
        }

        public static object Get()
        {
            IntPtr h = GetForegroundWindow();
            uint pid; GetWindowThreadProcessId(h, out pid);
            var sb = new StringBuilder(256); GetWindowText(h, sb, 256);
            string name = "", path = "";
            try { var p = Process.GetProcessById((int)pid); name = p.ProcessName; try { path = p.MainModule.FileName; } catch { } } catch { }
            return new Dictionary<string, object> { { "pid", (int)pid }, { "name", name }, { "path", path }, { "title", sb.ToString() }, { "hwnd", h.ToInt64() } };
        }
    }

    // ------------------------------------------------------------------ Xbox controllers: menu shortcuts, even while a game is in front

    internal static class Pads
    {
        [StructLayout(LayoutKind.Sequential)]
        // The Ex call writes one extra DWORD past the normal XINPUT_STATE, so the struct carries room for it.
        struct XInputState { public uint Packet; public ushort Buttons; public byte LT, RT; public short LX, LY, RX, RY; public uint Extra; }

        // Ordinal 100 is XInputGetStateEx, the only way to read the Guide (Xbox) button.
        [DllImport("xinput1_4.dll", EntryPoint = "#100")] static extern int XInputGetStateEx(int i, out XInputState s);
        [DllImport("xinput1_4.dll", EntryPoint = "XInputGetState")] static extern int XInputGetState14(int i, out XInputState s);
        [DllImport("xinput9_1_0.dll", EntryPoint = "XInputGetState")] static extern int XInputGetState910(int i, out XInputState s);
        static int mode = 0;   // 0 = Ex (with Guide), 1 = plain xinput1_4, 2 = xinput9_1_0, 3 = none
        static int Get(int i, out XInputState s)
        {
            s = default(XInputState);
            while (mode < 3)
            {
                try
                {
                    if (mode == 0) return XInputGetStateEx(i, out s);
                    if (mode == 1) return XInputGetState14(i, out s);
                    return XInputGetState910(i, out s);
                }
                catch { mode++; }
            }
            return 1167;   // ERROR_DEVICE_NOT_CONNECTED
        }

        const ushort BtnStart = 0x0010, BtnBack = 0x0020, BtnGuide = 0x0400;

        public static void Start()
        {
            var t = new Thread(Loop) { IsBackground = true, Name = "pads" };
            t.Start();
        }

        static void Loop()
        {
            var last = new ushort[4];
            var connected = new bool[4];
            while (true)
            {
                try
                {
                for (int i = 0; i < 4; i++)
                {
                    XInputState s;
                    bool on;
                    on = Get(i, out s) == 0;
                    if (on != connected[i]) { connected[i] = on; Program.Event("pads", new Dictionary<string, object> { { "count", connected.Count(c => c) } }); }
                    if (!on) { last[i] = 0; continue; }
                    ushort b = s.Buttons, was = last[i];
                    if ((b & BtnGuide) != 0 && (was & BtnGuide) == 0) Program.Event("pad", new Dictionary<string, object> { { "combo", "guide" } });
                    bool combo = (b & BtnStart) != 0 && (b & BtnBack) != 0, wasCombo = (was & BtnStart) != 0 && (was & BtnBack) != 0;
                    if (combo && !wasCombo) Program.Event("pad", new Dictionary<string, object> { { "combo", "viewmenu" } });
                    last[i] = b;
                }
                }
                catch (Exception e) { Console.Error.WriteLine("xinput read failed: " + e.Message); Thread.Sleep(1000); }
                if (mode >= 3) { Console.Error.WriteLine("xinput: none on this PC"); return; }
                Thread.Sleep(connected.Any(c => c) ? 30 : 500);
            }
        }
    }

    /// <summary>
    /// PlayStation controllers (DualShock 4, DualSense, DualSense Edge) read straight from HID, so the quick-menu
    /// buttons work without Steam or DS4Windows: PS = the "guide" combo, Create/Share + Options = the "viewmenu" combo.
    /// Reads are shared, so games, Steam and NSXL's own Gamepad API keep working alongside.
    /// </summary>
    internal static class PsPads
    {
        static readonly ushort[] Pids = { 0x05C4, 0x09CC, 0x0BA0, 0x0CE6, 0x0DF2 };   // DS4 v1, DS4 v2, DS4 dongle, DualSense, DualSense Edge
        static readonly HashSet<string> open = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        static readonly object gate = new object();

        [StructLayout(LayoutKind.Sequential)] struct SP_DEVICE_INTERFACE_DATA { public int cbSize; public Guid InterfaceClassGuid; public int Flags; public IntPtr Reserved; }
        [StructLayout(LayoutKind.Sequential)] struct HIDP_CAPS { public ushort Usage, UsagePage, InputReportByteLength, OutputReportByteLength, FeatureReportByteLength; [MarshalAs(UnmanagedType.ByValArray, SizeConst = 17)] public ushort[] Reserved; public ushort a, b, c, d, e, f, g, h, i, j; }
        [DllImport("hid.dll")] static extern void HidD_GetHidGuid(out Guid g);
        [DllImport("hid.dll")] static extern bool HidD_GetPreparsedData(Microsoft.Win32.SafeHandles.SafeFileHandle h, out IntPtr data);
        [DllImport("hid.dll")] static extern bool HidD_FreePreparsedData(IntPtr data);
        [DllImport("hid.dll")] static extern int HidP_GetCaps(IntPtr data, out HIDP_CAPS caps);
        [DllImport("setupapi.dll", SetLastError = true)] static extern IntPtr SetupDiGetClassDevs(ref Guid g, IntPtr e, IntPtr w, int flags);
        [DllImport("setupapi.dll", SetLastError = true)] static extern bool SetupDiEnumDeviceInterfaces(IntPtr set, IntPtr info, ref Guid g, int i, ref SP_DEVICE_INTERFACE_DATA d);
        [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr set, ref SP_DEVICE_INTERFACE_DATA d, IntPtr detail, int size, out int need, IntPtr info);
        [DllImport("setupapi.dll")] static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern Microsoft.Win32.SafeHandles.SafeFileHandle CreateFile(string name, uint access, uint share, IntPtr sec, uint disp, uint flags, IntPtr tpl);

        public static int Count { get { lock (gate) return open.Count; } }

        public static void Start()
        {
            new Thread(() => { while (true) { try { Scan(); } catch { } Thread.Sleep(2500); } }) { IsBackground = true, Name = "ps-scan" }.Start();
        }

        static IEnumerable<string> Paths()
        {
            Guid g; HidD_GetHidGuid(out g);
            IntPtr set = SetupDiGetClassDevs(ref g, IntPtr.Zero, IntPtr.Zero, 0x12);   // DIGCF_PRESENT | DIGCF_DEVICEINTERFACE
            if (set == IntPtr.Zero || set == new IntPtr(-1)) yield break;
            try
            {
                for (int i = 0; ; i++)
                {
                    var d = new SP_DEVICE_INTERFACE_DATA { cbSize = Marshal.SizeOf(typeof(SP_DEVICE_INTERFACE_DATA)) };
                    if (!SetupDiEnumDeviceInterfaces(set, IntPtr.Zero, ref g, i, ref d)) break;
                    int need;
                    SetupDiGetDeviceInterfaceDetail(set, ref d, IntPtr.Zero, 0, out need, IntPtr.Zero);
                    if (need <= 0) continue;
                    IntPtr buf = Marshal.AllocHGlobal(need);
                    try
                    {
                        Marshal.WriteInt32(buf, IntPtr.Size == 8 ? 8 : 6);   // cbSize of SP_DEVICE_INTERFACE_DETAIL_DATA_W
                        if (SetupDiGetDeviceInterfaceDetail(set, ref d, buf, need, out need, IntPtr.Zero))
                            yield return Marshal.PtrToStringUni(buf + 4);
                    }
                    finally { Marshal.FreeHGlobal(buf); }
                }
            }
            finally { SetupDiDestroyDeviceInfoList(set); }
        }

        static readonly HashSet<string> trying = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        static readonly Dictionary<string, DateTime> retryAt = new Dictionary<string, DateTime>(StringComparer.OrdinalIgnoreCase);

        /// USB: vid_054c&pid_0ce6. Bluetooth: {00001124-...}_vid&0002054c_pid&0ce6.
        static bool IsSony(string low) { return low.Contains("vid_054c") || low.Contains("vid&0002054c") || low.Contains("vid&0001054c"); }
        static bool IsBluetooth(string low) { return low.Contains("00001124-0000-1000-8000-00805f9b34fb") || low.Contains("vid&0002") || low.Contains("bthenum"); }

        static void Scan()
        {
            foreach (var p in Paths())
            {
                string low = p.ToLowerInvariant();
                if (!IsSony(low)) continue;
                if (!Pids.Any(id => low.Contains("pid_" + id.ToString("x4")) || low.Contains("pid&" + id.ToString("x4")))) continue;
                lock (gate)
                {
                    if (open.Contains(p) || trying.Contains(p)) continue;
                    DateTime t; if (retryAt.TryGetValue(p, out t) && DateTime.UtcNow < t) continue;
                    trying.Add(p);
                }
                var path = p;
                new Thread(() => Read(path, IsBluetooth(low))) { IsBackground = true, Name = "ps-read" }.Start();
            }
        }

        static void Changed() { Program.Event("pspads", new Dictionary<string, object> { { "count", Count } }); }

        static void Read(string path, bool bt)
        {
            bool opened = false;
            try
            {
                using (var h = CreateFile(path, 0x80000000, 0x3, IntPtr.Zero, 3, 0, IntPtr.Zero))   // GENERIC_READ, share read+write, OPEN_EXISTING
                {
                    if (h.IsInvalid)
                    {
                        int err = Marshal.GetLastWin32Error();
                        bool first; lock (gate) { first = !retryAt.ContainsKey(path); retryAt[path] = DateTime.UtcNow.AddSeconds(20); }
                        if (first) Console.Error.WriteLine("PlayStation controller (" + (bt ? "Bluetooth" : "USB") + ") couldn't be read, error " + err + (err == 5 || err == 32 ? ": another app (DS4Windows/HidHide?) has it to itself" : ""));
                        return;
                    }
                    opened = true;
                    lock (gate) { open.Add(path); retryAt.Remove(path); }
                    Changed();
                    Console.Error.WriteLine("PlayStation controller connected over " + (bt ? "Bluetooth" : "USB"));
                    int len = 64;
                    IntPtr pre;
                    if (HidD_GetPreparsedData(h, out pre)) { HIDP_CAPS caps; if (HidP_GetCaps(pre, out caps) >= 0 && caps.InputReportByteLength > 0) len = caps.InputReportByteLength; HidD_FreePreparsedData(pre); }
                    using (var fs = new FileStream(h, FileAccess.Read, 1, false))   // unbuffered: one read = one report
                    {
                        var buf = new byte[Math.Max(len, 64)];
                        bool ps = false, combo = false;
                        int lastState = 0;
                        string pad = "p" + System.Threading.Interlocked.Increment(ref padIds);
                        try
                        {
                        while (true)
                        {
                            int n = fs.Read(buf, 0, buf.Length);
                            if (n <= 0) break;
                            int b0, b1, b2, lx, ly;   // b0: d-pad + face buttons. b1: Create/Share 0x10, Options 0x20. b2: PS 0x01
                            if (!Buttons(path, bt, buf, n, out b0, out b1, out b2, out lx, out ly)) continue;
                            // the whole controller, for NSXL's own menus: Windows stops passing these controllers to
                            // NSXL's page once a game or Steam switches them to their full reports, so NSXL reads them here
                            int st = State(b0, b1, b2, lx, ly);
                            if (st != lastState) { Program.Event("psstate", new Dictionary<string, object> { { "pad", pad }, { "s", st } }); lastState = st; }
                            bool nowPs = (b2 & 0x01) != 0, nowCombo = (b1 & 0x30) == 0x30;
                            if (nowPs && !ps) Program.Event("pad", new Dictionary<string, object> { { "combo", "guide" }, { "ps", true } });
                            if (nowCombo && !combo) Program.Event("pad", new Dictionary<string, object> { { "combo", "viewmenu" }, { "ps", true } });
                            ps = nowPs; combo = nowCombo;
                        }
                        }
                        finally { Program.Event("psstate", new Dictionary<string, object> { { "pad", pad }, { "s", 0 } }); }
                    }
                }
            }
            catch (Exception e) { if (opened) Console.Error.WriteLine("PlayStation controller disconnected (" + e.Message + ")"); }   // unplugged
            finally { bool was; lock (gate) { trying.Remove(path); was = open.Remove(path); } if (was) Changed(); }
        }

        /// <summary>Where the button bytes sit in each controller's and connection's input report.</summary>
        static int padIds = 0;

        // Bits NSXL's page understands: 0 a, 1 b, 2 x, 3 y, 4 l, 5 r, 6 minus, 7 plus, 8 up, 9 down, 10 left, 11 right, 12 home
        static int State(int b0, int b1, int b2, int lx, int ly)
        {
            int s = 0;
            if ((b0 & 0x20) != 0) s |= 1 << 0;   // Cross
            if ((b0 & 0x40) != 0) s |= 1 << 1;   // Circle
            if ((b0 & 0x10) != 0) s |= 1 << 2;   // Square
            if ((b0 & 0x80) != 0) s |= 1 << 3;   // Triangle
            if ((b1 & 0x01) != 0) s |= 1 << 4;   // L1
            if ((b1 & 0x02) != 0) s |= 1 << 5;   // R1
            if ((b1 & 0x10) != 0) s |= 1 << 6;   // Create / Share
            if ((b1 & 0x20) != 0) s |= 1 << 7;   // Options
            int hat = b0 & 0x0F;
            bool up = hat == 7 || hat == 0 || hat == 1 || ly < 50, right = hat >= 1 && hat <= 3 || lx > 205;
            bool down = hat >= 3 && hat <= 5 || ly > 205, left = hat >= 5 && hat <= 7 || lx < 50;
            if (up) s |= 1 << 8; if (down) s |= 1 << 9; if (left) s |= 1 << 10; if (right) s |= 1 << 11;
            if ((b2 & 0x01) != 0) s |= 1 << 12;   // PS
            return s;
        }

        static bool Buttons(string path, bool bt, byte[] r, int n, out int b0, out int b1, out int b2, out int lx, out int ly)
        {
            b0 = b1 = b2 = 0; lx = ly = 128;
            bool sense = path.IndexOf("pid_0ce6", StringComparison.OrdinalIgnoreCase) >= 0 || path.IndexOf("pid_0df2", StringComparison.OrdinalIgnoreCase) >= 0;
            int at;   // index of the byte holding Share/Create + Options; PS is the next byte
            if (r[0] == 0x01 && sense && !bt && n >= 12) at = 9;   // DualSense over USB
            else if (r[0] == 0x31 && sense && n >= 12) at = 10;   // DualSense over Bluetooth (full reports)
            else if (r[0] == 0x11 && !sense && n >= 10) at = 8;   // DualShock 4 over Bluetooth (full reports)
            else if (r[0] == 0x01 && n >= 8) at = 6;   // DualShock 4 over USB, or either over Bluetooth in simple mode
            else return false;
            b0 = r[at - 1]; b1 = r[at]; b2 = r[at + 1];
            int stick = sense && at >= 9 ? at - 8 : at - 5;   // left stick X, then Y
            lx = r[stick]; ly = r[stick + 1];
            return true;
        }
    }

    /// <summary>Finds and closes a game's processes by the folder it's installed in (works for 32- and 64-bit games).</summary>
    internal static class Procs
    {
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder name, ref int size);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);

        static string PathOf(int pid)
        {
            IntPtr h = OpenProcess(0x1000, false, pid);   // PROCESS_QUERY_LIMITED_INFORMATION
            if (h == IntPtr.Zero) return null;
            try { var sb = new StringBuilder(1024); int n = sb.Capacity; return QueryFullProcessImageName(h, 0, sb, ref n) ? sb.ToString() : null; }
            finally { CloseHandle(h); }
        }
        static IEnumerable<Process> In(string dir)
        {
            if (string.IsNullOrEmpty(dir)) yield break;
            string root = Path.GetFullPath(dir).TrimEnd('\\') + "\\";
            foreach (var p in Process.GetProcesses())
            {
                string f = null;
                try { f = PathOf(p.Id); } catch { }
                if (f != null && f.StartsWith(root, StringComparison.OrdinalIgnoreCase)) yield return p;
            }
        }
        public static object ListIn(string dir) { return In(dir).Select(p => (object)p.Id).ToList(); }
        public static object KillIn(string dir)
        {
            int n = 0;
            int me = Process.GetCurrentProcess().Id;
            foreach (var p in In(dir).ToList())
            {
                string nm = ""; try { nm = p.ProcessName; } catch { }
                if (p.Id == me || nm.Equals("NSXL", StringComparison.OrdinalIgnoreCase) || nm.StartsWith("nsxl-", StringComparison.OrdinalIgnoreCase)) continue;
                try { p.Kill(); n++; } catch { }
            }
            return n;
        }
    }
}
