package com.nsxl.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.HapticFeedbackConstants;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * Everything the home-screen page can ask Android to do. Exposed to JavaScript as window.Android.
 * These methods run on a WebView background thread, so anything touching views hops to the UI thread.
 */
final class LauncherBridge {

    private final MainActivity a;

    LauncherBridge(MainActivity activity) {
        this.a = activity;
    }

    // ---------------------------------------------------------------- apps

    @JavascriptInterface
    public String getApps() {
        return AppList.json(a);
    }

    @JavascriptInterface
    public void launch(final String pkg, final float x, final float y, final float w, final float h) {
        a.runOnUiThread(() -> a.launchApp(pkg, x, y, w, h));
    }

    @JavascriptInterface
    public void appInfo(String pkg) {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)));
    }

    @SuppressWarnings("deprecation")
    @JavascriptInterface
    public void uninstall(String pkg) {
        start(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)));
    }

    /** Dock buttons: open the default app of a kind, with a few well-known fallbacks. */
    @JavascriptInterface
    public void openCategory(final String which) {
        a.runOnUiThread(() -> {
            String cat;
            String[] fallbacks;
            switch (which) {
                case "messaging":
                    cat = Intent.CATEGORY_APP_MESSAGING;
                    fallbacks = new String[]{"com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"};
                    break;
                case "browser":
                    cat = Intent.CATEGORY_APP_BROWSER;
                    fallbacks = new String[]{"com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser"};
                    break;
                case "market":
                    cat = Intent.CATEGORY_APP_MARKET;
                    fallbacks = new String[]{"com.android.vending"};
                    break;
                case "gallery":
                    cat = Intent.CATEGORY_APP_GALLERY;
                    fallbacks = new String[]{"com.google.android.apps.photos", "com.sec.android.gallery3d",
                            "com.miui.gallery", "com.coloros.gallery3d", "com.oneplus.gallery"};
                    break;
                case "music":
                    cat = Intent.CATEGORY_APP_MUSIC;
                    fallbacks = new String[]{"com.google.android.apps.youtube.music", "com.spotify.music", "com.sec.android.app.music"};
                    break;
                default:
                    return;
            }
            try {
                Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                return;
            } catch (Exception ignored) { }
            PackageManager pm = a.getPackageManager();
            for (String p : fallbacks) {
                Intent li = pm.getLaunchIntentForPackage(p);
                if (li == null) continue;
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    a.startActivity(li);
                    return;
                } catch (Exception ignored) { }
            }
            a.jsToast("No app found. Press + (or long-press) on this button to pick one.");
        });
    }

    @JavascriptInterface
    public void openSettings(String which) {
        String action;
        switch (which == null ? "" : which) {
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "home": action = Settings.ACTION_HOME_SETTINGS; break;
            case "battery": action = Intent.ACTION_POWER_USAGE_SUMMARY; break;
            default: action = Settings.ACTION_SETTINGS; break;
        }
        start(new Intent(action));
    }

    // ---------------------------------------------------------------- status bar

    @JavascriptInterface
    public String getStatus() {
        JSONObject o = new JSONObject();
        try {
            BatteryManager bm = (BatteryManager) a.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                o.put("charging", bm.isCharging());
            }
            String net = "none";
            int bars = 0;
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
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
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    net = "ethernet";
                    bars = 3;
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    net = "cell";
                    bars = 4;
                }
            }
            o.put("net", net);
            o.put("bars", bars);
            o.put("pads", a.connectedControllers());
            o.put("time24", DateFormat.is24HourFormat(a));
        } catch (Exception ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = a.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && a.getPackageName().equals(ri.activityInfo.packageName);
    }

    // ---------------------------------------------------------------- themes / wallpaper

    @JavascriptInterface
    public void setMode(final String mode) {
        a.runOnUiThread(() -> a.setMode(mode));
    }

    @JavascriptInterface
    public boolean hasVideo() {
        return Storage.videoFile(a).exists();
    }

    @JavascriptInterface
    public void pickVideo() {
        a.runOnUiThread(() -> a.pickContent("video/*", MainActivity.REQ_VIDEO));
    }

    @JavascriptInterface
    public void setVideoBlur(final int radius) {
        a.runOnUiThread(() -> a.video.setBlur(radius));
    }

    // ---------------------------------------------------------------- cover art / avatar

    @JavascriptInterface
    public void pickCover(final String pkg) {
        a.runOnUiThread(() -> {
            a.pendingCoverPkg = pkg;
            a.pickContent("image/*", MainActivity.REQ_COVER);
        });
    }

    @JavascriptInterface
    public void removeCover(String pkg) {
        //noinspection ResultOfMethodCallIgnored
        Storage.coverFile(a, pkg).delete();
        a.server.invalidate(pkg);
    }

    @JavascriptInterface
    public void pickAvatar() {
        a.runOnUiThread(() -> a.pickContent("image/*", MainActivity.REQ_AVATAR));
    }

    @JavascriptInterface
    public void removeAvatar() {
        //noinspection ResultOfMethodCallIgnored
        Storage.avatarFile(a).delete();
    }

    // ---------------------------------------------------------------- settings

    @JavascriptInterface
    public void setOrientation(final String o) {
        a.prefs.edit().putString("orientation", o).apply();
        a.runOnUiThread(() -> a.applyOrientation(o));
    }

    @JavascriptInterface
    public void setImmersive(final boolean on) {
        a.prefs.edit().putBoolean("immersive", on).apply();
        a.runOnUiThread(() -> {
            a.immersive = on;
            a.applySystemBars();
        });
    }

    @JavascriptInterface
    public void setSwapAB(boolean on) {
        a.swapAB = on;
        a.prefs.edit().putBoolean("swapAB", on).apply();
    }

    @JavascriptInterface
    public void haptic(final String kind) {
        a.runOnUiThread(() -> {
            if (a.web == null) return;
            int c;
            if ("confirm".equals(kind)) {
                c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
            } else if ("long".equals(kind)) {
                c = HapticFeedbackConstants.LONG_PRESS;
            } else {
                c = HapticFeedbackConstants.CLOCK_TICK;
            }
            a.web.performHapticFeedback(c);
        });
    }

    @JavascriptInterface
    public int sdk() {
        return Build.VERSION.SDK_INT;
    }

    @JavascriptInterface
    public String version() {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    @JavascriptInterface
    public void reload() {
        a.runOnUiThread(a::reloadPage);
    }

    // ---------------------------------------------------------------- helpers

    private void start(final Intent i) {
        a.runOnUiThread(() -> {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            } catch (Exception e) {
                a.jsToast("Not available on this device");
            }
        });
    }
}
