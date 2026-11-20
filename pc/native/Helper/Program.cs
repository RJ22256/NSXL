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
            Console.OutputEncoding = new UTF8Encoding(false);
            Console.InputEncoding = new UTF8Encoding(false);
            Pads.Start();
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
                case "proc.kill": Process.GetProcessById(Int(m, "pid")).Kill(); return true;
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
        public static bool Bring(long hwnd)
        {
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
            return new Dictionary<string, object> { { "pid", (int)pid }, { "name", name }, { "path", path }, { "title", sb.ToString() } };
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
                if (mode >= 3) return;   // no XInput on this PC
                Thread.Sleep(connected.Any(c => c) ? 30 : 500);
            }
        }
    }
}
