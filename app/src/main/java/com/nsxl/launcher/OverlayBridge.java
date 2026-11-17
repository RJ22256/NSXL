package com.nsxl.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/** What the quick menu (overlay.html) can ask for. Exposed to it as window.Android. */
final class OverlayBridge {

    private final OverlayService s;

    OverlayBridge(OverlayService service) {
        this.s = service;
    }

    // ---------------------------------------------------------------- menu

    @JavascriptInterface
    public void close() {
        s.ui.post(s::hideMenu);
    }

    @JavascriptInterface
    public void goHome() {
        s.ui.post(() -> {
            s.hideMenu();
            Intent i = new Intent(s, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { s.startActivity(i); } catch (Exception ignored) { }
        });
    }

    @JavascriptInterface
    public void launch(final String pkg) {
        s.ui.post(() -> {
            s.hideMenu();
            Intent i = s.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try { s.startActivity(i); } catch (Exception ignored) { }
        });
    }

    @JavascriptInterface
    public String getApps() {
        return AppList.json(s);
    }

    @JavascriptInterface
    public void hideHandle() {
        s.prefs.edit().putBoolean("overlayHideHandle", true).apply();
        s.ui.post(s::updateHandle);
    }

    @JavascriptInterface
    public void stopOverlay() {
        s.prefs.edit().putBoolean("overlay", false).apply();
        s.ui.post(s::stopSelf);
    }

    @JavascriptInterface
    public void haptic() {
        s.ui.post(s::buzz);
    }

    @JavascriptInterface
    public void openPanel(final String which) {
        s.ui.post(() -> {
            s.hideMenu();
            Intent i;
            if ("wifi".equals(which)) {
                i = new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS);
            } else if ("volume".equals(which)) {
                i = new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_VOLUME : Settings.ACTION_SOUND_SETTINGS);
            } else {
                i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { s.startActivity(i); } catch (Exception ignored) { }
        });
    }

    // ---------------------------------------------------------------- extras (need the controller shortcut service)

    @JavascriptInterface
    public boolean hasShortcutService() {
        return ShortcutService.instance != null && Build.VERSION.SDK_INT >= 28;
    }

    @JavascriptInterface
    public void screenshot() {
        s.ui.post(() -> {
            s.hideMenu();
            s.ui.postDelayed(ShortcutService::takeScreenshot, 450);   // let the menu fade out first
        });
    }

    @JavascriptInterface
    public void sleep() {
        s.ui.post(() -> {
            s.hideMenu();
            s.ui.postDelayed(ShortcutService::sleep, 250);
        });
    }

    // ---------------------------------------------------------------- media + sound

    @JavascriptInterface
    public void media(String cmd) {
        int code = "next".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_NEXT
                : "prev".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        AudioManager am = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    @JavascriptInterface
    public String getVolume() {
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
            o.put("v", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            o.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            o.put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC));
        } catch (Exception ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public void setVolume(int v) {
        try {
            AudioManager am = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void toggleMute() {
        try {
            AudioManager am = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- brightness

    @JavascriptInterface
    public String getBrightness() {
        JSONObject o = new JSONObject();
        try {
            ContentResolver cr = s.getContentResolver();
            o.put("v", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128));
            o.put("auto", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            o.put("canWrite", Settings.System.canWrite(s));
        } catch (Exception ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public void setBrightness(int v) {
        if (!Settings.System.canWrite(s)) return;
        ContentResolver cr = s.getContentResolver();
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.min(255, v)));
    }

    @JavascriptInterface
    public void setAutoBrightness(boolean on) {
        if (!Settings.System.canWrite(s)) return;
        Settings.System.putInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
    }

    @JavascriptInterface
    public void requestWriteSettings() {
        s.ui.post(() -> {
            s.hideMenu();
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + s.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { s.startActivity(i); } catch (Exception ignored) { }
        });
    }

    // ---------------------------------------------------------------- status

    @JavascriptInterface
    public String getStatus() {
        JSONObject o = new JSONObject();
        try {
            BatteryManager bm = (BatteryManager) s.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                o.put("charging", bm.isCharging());
            }
            String net = "none";
            int bars = 0;
            ConnectivityManager cm = (ConnectivityManager) s.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    net = "wifi";
                    bars = 3;
                    if (Build.VERSION.SDK_INT >= 29) {
                        int rssi = nc.getSignalStrength();
                        if (rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                            bars = rssi > -62 ? 3 : rssi > -72 ? 2 : rssi > -82 ? 1 : 0;
                        }
                    }
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) net = "ethernet";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cell";
            }
            o.put("net", net);
            o.put("bars", bars);
            o.put("time24", DateFormat.is24HourFormat(s));

            JSONArray pads = new JSONArray();
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                int src = d.getSources();
                if ((src & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                        && (src & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) continue;
                JSONObject p = new JSONObject();
                p.put("name", d.getName());
                int level = -1;
                if (Build.VERSION.SDK_INT >= 31) {
                    android.hardware.BatteryState bs = d.getBatteryState();
                    if (bs.isPresent() && !Float.isNaN(bs.getCapacity())) level = Math.round(bs.getCapacity() * 100);
                }
                p.put("battery", level);
                pads.put(p);
            }
            o.put("controllers", pads);
        } catch (Exception ignored) { }
        return o.toString();
    }
}
