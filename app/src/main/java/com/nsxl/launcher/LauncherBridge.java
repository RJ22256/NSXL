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

    /** The controller family to label buttons for: the one connected now, else the last one used, else "none". */
    @JavascriptInterface
    public String padFamily() {
        String now = PadKeys.connectedFamily();
        if (now == null) return "none";
        return a.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).getString("padFamily", now);
    }

    @JavascriptInterface
    public String getApps() {
        return AppList.json(a);
    }

    @JavascriptInterface
    public void launch(final String pkg, final float x, final float y, final float w, final float h) {
        a.runOnUiThread(() -> a.launchApp(pkg, x, y, w, h));
    }

    /** "zoom" (grow out of the tile), "fade" or "instant". */
    @JavascriptInterface
    public void setLaunchStyle(String style) {
        a.launchStyle = style == null ? "zoom" : style;
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
    public boolean hasMusic() {
        return Storage.musicFile(a).exists();
    }

    @JavascriptInterface
    public void pickMusic() {
        a.runOnUiThread(() -> a.pickContent("audio/*", MainActivity.REQ_MUSIC));
    }

    /** While a text box has focus, keyboard keys go to it instead of being treated as controller input. */
    @JavascriptInterface
    public void setTyping(boolean on) {
        a.typing = on;
    }

    /** The page has drawn its first frame: drop the splash screen and start the wallpaper. */
    @JavascriptInterface
    public void pageReady() {
        a.runOnUiThread(a::onPageReady);
    }

    // ---------------------------------------------------------------- Play time

    @JavascriptInterface
    public String playStats() {
        return Play.stats(a);
    }

    @JavascriptInterface
    public void playReset(String pkg) {
        Play.reset(a, pkg);
    }

    // ---------------------------------------------------------------- Controllers (Bluetooth pairing)

    @JavascriptInterface
    public String padsState() {
        return a.pads.state();
    }

    @JavascriptInterface
    public void padsPermission() {
        a.runOnUiThread(a.pads::requestPermission);
    }

    @JavascriptInterface
    public void padsEnable() {
        a.runOnUiThread(a.pads::enableBluetooth);
    }

    @JavascriptInterface
    public void padsScan() {
        a.runOnUiThread(a.pads::startScan);
    }

    @JavascriptInterface
    public void padsStop() {
        a.runOnUiThread(a.pads::stopScan);
    }

    @JavascriptInterface
    public void padsPair(final String addr) {
        a.runOnUiThread(() -> a.pads.pair(addr));
    }

    @JavascriptInterface
    public void padsForget(final String addr) {
        a.runOnUiThread(() -> a.pads.forget(addr));
    }

    @JavascriptInterface
    public void padsRumble(final double id) {
        a.runOnUiThread(() -> a.pads.rumble((int) id));
    }

    // ---------------------------------------------------------------- Album (screenshots)

    @JavascriptInterface
    public String albumAccess() {
        return Album.access(a);
    }

    @JavascriptInterface
    public void albumRequest() {
        a.runOnUiThread(a::albumRequest);
    }

    @JavascriptInterface
    public void albumList(final String id) {
        new Thread(() -> {
            try { a.asyncResult(id, Album.list(a)); } catch (Exception e) { a.asyncError(id, e); }
        }, "album").start();
    }

    @JavascriptInterface
    public void albumShare(final double shot) {
        a.runOnUiThread(() -> { try { Album.share(a, (long) shot); } catch (Exception e) { a.jsToast("Couldn't share that screenshot"); } });
    }

    @JavascriptInterface
    public void albumOpen(final double shot) {
        a.runOnUiThread(() -> { try { Album.open(a, (long) shot); } catch (Exception e) { a.jsToast("No gallery app found"); } });
    }

    @JavascriptInterface
    public void albumDelete(final double shot) {
        a.runOnUiThread(() -> a.albumDelete((long) shot));
    }

    /** Uses a screenshot as a game's Home tile (cropped to a square from the middle). */
    @JavascriptInterface
    public void albumAsCover(final String id, final double shot, final String pkg) {
        new Thread(() -> {
            try {
                if (!Storage.saveSquareImage(a, Album.uri((long) shot), Storage.coverFile(a, pkg), 720)) throw new java.io.IOException("Couldn't read that screenshot");
                //noinspection ResultOfMethodCallIgnored
                Storage.coverVideoFile(a, pkg).delete();
                //noinspection ResultOfMethodCallIgnored
                Storage.coverAnimFile(a, pkg).delete();
                a.server.invalidate(pkg);
                a.asyncResult(id, "true");
            } catch (Exception e) { a.asyncError(id, e); }
        }, "album").start();
    }

    // ---------------------------------------------------------------- News (fetches feeds natively: most news sites don't allow cross-site requests)

    @JavascriptInterface
    public void fetchText(final String id, final String url) {
        new Thread(() -> {
            try {
                if (url == null || !url.startsWith("https://")) throw new java.io.IOException("Bad address");
                a.asyncResult(id, JSONObject.quote(Net.getText(url, 3 * 1024 * 1024)));
            } catch (Exception e) { a.asyncError(id, e); }
        }, "news").start();
    }

    // ---------------------------------------------------------------- SteamGridDB (async: answers via window.onAsync)

    @JavascriptInterface
    public void sgdbSearch(final String id, final String key, final String term) {
        new Thread(() -> {
            try {
                a.asyncResult(id, SteamGridDB.search(key, term));
            } catch (Exception e) {
                a.asyncError(id, e);
            }
        }, "sgdb").start();
    }

    @JavascriptInterface
    public void sgdbGrids(final String id, final String key, final double gameId) {
        new Thread(() -> {
            try {
                a.asyncResult(id, SteamGridDB.grids(key, (long) gameId));
            } catch (Exception e) {
                a.asyncError(id, e);
            }
        }, "sgdb").start();
    }

    @JavascriptInterface
    public void sgdbApply(final String id, final String pkg, final String url) {
        new Thread(() -> {
            java.io.File tmp = new java.io.File(a.getCacheDir(), "sgdb-download");
            try {
                if (!url.startsWith("https://")) throw new java.io.IOException("Bad image address");
                SteamGridDB.download(url, tmp);
                boolean ok = Storage.saveSquareImage(a, Uri.fromFile(tmp), Storage.coverFile(a, pkg), 720);
                //noinspection ResultOfMethodCallIgnored
                if (ok) { Storage.coverVideoFile(a, pkg).delete(); Storage.coverAnimFile(a, pkg).delete(); }
                if (!ok) throw new java.io.IOException("Couldn't read that image");
                a.server.invalidate(pkg);
                a.asyncResult(id, "true");
            } catch (Exception e) {
                a.asyncError(id, e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }, "sgdb").start();
    }

    /** A live (animated) SteamGridDB cover, kept as it came so it keeps moving. */
    @JavascriptInterface
    public void sgdbApplyLive(final String id, final String pkg, final String url) {
        new Thread(() -> {
            java.io.File out = Storage.coverAnimFile(a, pkg), tmp = new java.io.File(out.getPath() + ".tmp");
            try {
                if (!url.startsWith("https://")) throw new java.io.IOException("Bad image address");
                SteamGridDB.download(url, tmp);
                if ("image/jpeg".equals(Storage.sniffImage(tmp))) throw new java.io.IOException("That one isn't animated");
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                if (!tmp.renameTo(out)) throw new java.io.IOException("Couldn't save it");
                //noinspection ResultOfMethodCallIgnored
                Storage.coverVideoFile(a, pkg).delete();
                a.server.invalidate(pkg);
                a.asyncResult(id, "true");
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                a.asyncError(id, e);
            }
        }, "sgdb").start();
    }

    // ---------------------------------------------------------------- game logos (for the launch intro)

    @JavascriptInterface
    public void sgdbLogos(final String id, final String key, final double gameId) {
        new Thread(() -> {
            try { a.asyncResult(id, SteamGridDB.logos(key, (long) gameId)); } catch (Exception e) { a.asyncError(id, e); }
        }, "sgdb").start();
    }

    @JavascriptInterface
    public void logoApply(final String id, final String pkg, final String url) {
        new Thread(() -> {
            try {
                if (!url.startsWith("https://")) throw new java.io.IOException("Bad image address");
                SteamGridDB.download(url, Storage.logoFile(a, pkg));
                a.asyncResult(id, "true");
            } catch (Exception e) { a.asyncError(id, e); }
        }, "sgdb").start();
    }

    @JavascriptInterface
    public boolean logoHas(String pkg) { return Storage.logoFile(a, pkg).exists(); }

    @JavascriptInterface
    public void logoRemove(String pkg) {
        //noinspection ResultOfMethodCallIgnored
        Storage.logoFile(a, pkg).delete();
    }

    /** Fetches a logo by the game's name the first time it's needed (quietly; nothing happens without a key). */
    @JavascriptInterface
    public void logoAuto(final String pkg, final String name, final String key) {
        if (key == null || key.isEmpty() || Storage.logoFile(a, pkg).exists()) return;
        new Thread(() -> {
            try { String u = SteamGridDB.firstLogo(key, name); if (u != null) SteamGridDB.download(u, Storage.logoFile(a, pkg)); }
            catch (Exception ignored) { }
        }, "sgdb-logo").start();
    }

    // ---------------------------------------------------------------- what's playing (any app) and its controls

    /** The media session to show, as JSON (needs notification access), or "null". */
    @JavascriptInterface
    public String mediaNow(boolean withArt) {
        try { org.json.JSONObject o = a.mediaWatch == null ? null : a.mediaWatch.now(withArt); return o == null ? "null" : o.toString(); }
        catch (Exception e) { return "null"; }
    }

    @JavascriptInterface
    public void mediaCmd(final String c) { a.runOnUiThread(() -> { if (a.mediaWatch != null) a.mediaWatch.command(c); }); }

    @JavascriptInterface
    public void setVideoBlur(final int radius) {
        a.runOnUiThread(() -> a.video.setBlur(radius));
    }

    // ---------------------------------------------------------------- cover art / avatar

    @JavascriptInterface
    public void pickCover(final String pkg) {
        a.runOnUiThread(() -> {
            a.pendingCoverPkg = pkg;
            a.pickContent("*/*", new String[]{"image/*", "video/*"}, MainActivity.REQ_COVER);
        });
    }

    @JavascriptInterface
    public void removeCover(String pkg) {
        //noinspection ResultOfMethodCallIgnored
        Storage.coverFile(a, pkg).delete();
        //noinspection ResultOfMethodCallIgnored
        Storage.coverVideoFile(a, pkg).delete();
        //noinspection ResultOfMethodCallIgnored
        Storage.coverAnimFile(a, pkg).delete();
        a.server.invalidate(pkg);
    }

    // ---------------------------------------------------------------- other apps' music

    /** True when NSXL may see whether Spotify or another app is playing (notification access). */
    @JavascriptInterface
    public boolean mediaAccess() { return MediaWatch.allowed(a); }

    /** Whether another app is playing right now. */
    @JavascriptInterface
    public boolean otherMusic() { return a.mediaWatch != null && a.mediaWatch.playing(); }

    @JavascriptInterface
    public void openMediaAccess() {
        a.runOnUiThread(() -> {
            try { a.startActivity(MediaWatch.settingsIntent(a)); }
            catch (Exception e) { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        });
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

    /** Two-letter country for TMDB's watch region: SIM country first, then the phone's locale. */
    @JavascriptInterface
    public String region() {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) a.getSystemService(Context.TELEPHONY_SERVICE);
            String c = tm == null ? null : tm.getSimCountryIso();
            if (c != null && c.length() == 2) return c.toUpperCase(java.util.Locale.ROOT);
        } catch (Exception ignored) { }
        String c = java.util.Locale.getDefault().getCountry();
        return c == null || c.length() != 2 ? "GB" : c;
    }

    @JavascriptInterface
    public void copyText(final String text) {
        a.runOnUiThread(() -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("title", text));
        });
    }

    /** Opens a web link, preferring the given app (so a streaming app can catch its own search links). */
    /** Opens NSXL's own browser, at the start page or at a link. */
    @JavascriptInterface
    public void openBrowser(final String url, final boolean pad) {
        a.runOnUiThread(() -> {
            Intent i = new Intent(a, BrowserActivity.class).putExtra("orientation", a.getRequestedOrientation()).putExtra("pad", pad);
            if (url != null && url.startsWith("https://")) i.putExtra("url", url);
            a.startActivity(i);
            a.overridePendingTransition(R.anim.browser_in, R.anim.home_recede);
        });
    }

    @JavascriptInterface
    public void openUrl(final String url, final String pkg) {
        if (url == null || !url.startsWith("https://")) return;
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg != null && !pkg.isEmpty()) {
                try {
                    a.startActivity(new Intent(i).setPackage(pkg));
                    return;
                } catch (Exception ignored) { }
            }
            try {
                a.startActivity(i);
            } catch (Exception e) {
                a.jsToast("Couldn't open that link");
            }
        });
    }

    // ---------------------------------------------------------------- in-game quick menu

    @JavascriptInterface
    public String overlayState() {
        JSONObject o = new JSONObject();
        try {
            o.put("permission", Settings.canDrawOverlays(a));
            o.put("running", OverlayService.instance != null);
            o.put("enabled", a.prefs.getBoolean("overlay", false));
            o.put("handleHidden", a.prefs.getBoolean("overlayHideHandle", false));
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** Returns "ok", or "permission" when Android's "display over other apps" screen was opened instead. */
    @JavascriptInterface
    public String overlayEnable(boolean on) {
        if (!on) {
            a.prefs.edit().putBoolean("overlay", false).apply();
            a.runOnUiThread(() -> OverlayService.stop(a));
            return "ok";
        }
        if (!Settings.canDrawOverlays(a)) {
            start(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName())));
            return "permission";
        }
        a.prefs.edit().putBoolean("overlay", true).putBoolean("overlayHideHandle", false).apply();
        a.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 33
                    && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 51);
            }
            OverlayService.start(a, null);
        });
        return "ok";
    }

    @JavascriptInterface
    public void overlayConfig(String side, float alpha, boolean hideHandle) {
        a.prefs.edit().putString("overlaySide", side).putFloat("overlayAlpha", alpha).putBoolean("overlayHideHandle", hideHandle).apply();
        OverlayService.refreshHandle();
    }

    // ---------------------------------------------------------------- NSXL Cloud

    /** The page keeps NSXL's backup-worthy settings here so the server can hand them out without the page. */
    @JavascriptInterface
    public void cloudSnapshot(String json) {
        a.prefs.edit().putString("cloudSettings", json).apply();
    }

    @JavascriptInterface
    public String cloudHostState() {
        JSONObject o = new JSONObject();
        try {
            CloudServer s = CloudServer.instance;
            o.put("running", s != null);
            if (s != null) {
                String ip = CloudServer.localAddress(a);
                o.put("address", ip == null ? "" : ip + ":" + CloudServer.PORT);
                o.put("pin", s.pin);
            }
            o.put("stored", CloudServer.listStored(a));
        } catch (Exception ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public String cloudHost(boolean on) {
        if (!on) { CloudServer.stop(); return cloudHostState(); }
        try { CloudServer.start(a); } catch (Exception e) { a.jsToast("Couldn't start NSXL Cloud: " + e.getMessage()); }
        return cloudHostState();
    }

    @JavascriptInterface
    public String cloudPending() {
        return a.prefs.getString("pendingRestore", "");
    }

    @JavascriptInterface
    public void cloudClearPending() {
        a.prefs.edit().remove("pendingRestore").apply();
    }

    @JavascriptInterface
    public void cloudPush(final String id, final String address, final String pin, final boolean video) {
        new Thread(() -> {
            try { CloudClient.push(a, address, pin, video); a.asyncResult(id, "true"); }
            catch (Exception e) { a.asyncError(id, e); }
        }, "cloud").start();
    }

    @JavascriptInterface
    public void cloudList(final String id, final String address, final String pin) {
        new Thread(() -> {
            try { a.asyncResult(id, CloudClient.list(address, pin)); }
            catch (Exception e) { a.asyncError(id, e); }
        }, "cloud").start();
    }

    /** name: a stored backup's file name, or "" for the other device's current setup. */
    @JavascriptInterface
    public void cloudPull(final String id, final String address, final String pin, final String name) {
        new Thread(() -> {
            try {
                String json = CloudClient.pull(a, address, pin, name == null || name.isEmpty() ? null : name);
                MainActivity.notifyRestored(json);
                a.asyncResult(id, "true");
            } catch (Exception e) { a.asyncError(id, e); }
        }, "cloud").start();
    }

    /** Restore one of the backups other devices stored on this one. */
    @JavascriptInterface
    public void cloudRestoreLocal(final String id, final String name) {
        new Thread(() -> {
            try {
                String n = CloudServer.safeName(name);
                if (n == null) throw new java.io.IOException("Not found");
                String json = CloudBackup.restore(a, new java.io.File(CloudServer.storeDir(a), n));
                MainActivity.notifyRestored(json);
                a.asyncResult(id, "true");
            } catch (Exception e) { a.asyncError(id, e); }
        }, "cloud").start();
    }

    // ---------------------------------------------------------------- start-up / sleep

    private static boolean loadedOnce;

    /** True only the first time the page loads in this app process, so the start-up logo plays once. */
    @JavascriptInterface
    public boolean firstLoad() {
        synchronized (LauncherBridge.class) {
            boolean first = !loadedOnce;
            loadedOnce = true;
            return first;
        }
    }

    @JavascriptInterface
    public boolean canSleep() {
        return ShortcutService.instance != null && Build.VERSION.SDK_INT >= 28;
    }

    @JavascriptInterface
    public void sleepNow() {
        a.runOnUiThread(ShortcutService::sleep);
    }

    @JavascriptInterface
    public boolean shortcutEnabled() {
        return ShortcutService.instance != null;
    }

    @JavascriptInterface
    public void setShortcut(String mode) {
        a.prefs.edit().putString("shortcut", mode).apply();
    }

    @JavascriptInterface
    public void openAccessibility() {
        start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    @JavascriptInterface
    public void openAppInfoSelf() {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.getPackageName(), null)));
    }

    @JavascriptInterface
    public void overlayTest() {
        a.runOnUiThread(() -> {
            if (Settings.canDrawOverlays(a)) OverlayService.start(a, OverlayService.ACTION_SHOW);
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
