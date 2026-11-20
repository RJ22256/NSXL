package com.nsxl.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The launcher is one activity:
 *   - a VideoWallpaperView at the back (only visible with the Live Wallpaper theme)
 *   - a transparent WebView on top that draws the whole home screen (assets/www/index.html)
 * The page talks to Android through {@link LauncherBridge}; controller input is forwarded to the
 * page as simple button names ("left", "a", "b", "plus"...).
 */
public class MainActivity extends Activity {

    static final String ORIGIN = "https://launcher.local/";
    static final int REQ_VIDEO = 41, REQ_COVER = 42, REQ_AVATAR = 43, REQ_MUSIC = 44, REQ_ALBUM = 45, REQ_ALBUM_DELETE = 46, REQ_PADS = 47;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    FrameLayout root;
    VideoWallpaperView video;
    WebView web;
    LocalServer server;
    /** Tells the page when another app (Spotify, say) plays or pauses, so ambient music can step aside. */
    MediaWatch mediaWatch;
    Pads pads;
    LauncherBridge bridge;
    private BroadcastReceiver packageReceiver, screenReceiver;
    private boolean resumed, sleptWhileShowing, wakePending;
    private long pausedAt;

    String mode = "light";
    boolean videoEnabled;
    volatile boolean swapAB;
    volatile boolean typing;
    boolean immersive = true;
    String pendingCoverPkg;
    private String stickDir;

    // ------------------------------------------------------------------ lifecycle

    static MainActivity current;

    /** A backup was restored (from this device's cloud page or another device): reload video and tell the page. */
    static void notifyRestored(String json) {
        MainActivity a = current;
        if (a == null) return;
        a.ui.post(() -> {
            a.video.stop();
            a.video.setVideoFile(Storage.videoFile(a));
            if (a.videoEnabled) a.video.play();
            a.prefs.edit().remove("pendingRestore").apply();
            a.js("window.onCloudRestore&&window.onCloudRestore(" + json + ")");
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        swapAB = prefs.getBoolean("swapAB", false);
        immersive = prefs.getBoolean("immersive", true);
        applyOrientation(prefs.getString("orientation", "landscape"));
        mode = prefs.getString("mode", "light");
        getWindow().setBackgroundDrawable(new ColorDrawable(backgroundFor(mode)));
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        root = new FrameLayout(this);
        video = new VideoWallpaperView(this);
        video.setVideoFile(Storage.videoFile(this));
        root.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH));

        server = new LocalServer(this);
        mediaWatch = new MediaWatch(this, (playing, pkg) -> js("window.onOtherMusic&&window.onOtherMusic(" + playing + ")"));
        pads = new Pads(this);
        bridge = new LauncherBridge(this);
        createWebView();
        setContentView(root);
        holdSplashUntilReady();

        applySystemBars();
        setMode(mode);
        registerPackageReceiver();
        registerScreenReceiver();
        if (prefs.getBoolean("overlay", false) && android.provider.Settings.canDrawOverlays(this)) {
            try { OverlayService.start(this, null); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ start-up: no half-drawn screens

    /** True once the page has drawn its first real frame (the start-up animation or Home). */
    boolean pageReady;

    /** Keeps the splash screen up until the page is ready, so the wallpaper never shows on its own first. */
    private void holdSplashUntilReady() {
        final View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (!pageReady) return false;
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
        });
        ui.postDelayed(this::onPageReady, 3000);   // never wait longer than this
    }

    void onPageReady() {
        if (pageReady) return;
        pageReady = true;
        if (videoEnabled && resumed) video.play();
        View content = findViewById(android.R.id.content);
        if (content != null) content.invalidate();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.onResume();
        if (videoEnabled && pageReady) video.play();
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.setLauncherVisible(true);
        resumed = true;
        mediaWatch.start();   // also picks up notification access the moment it's turned on
        Play.finish(this);
        if (sleptWhileShowing) {          // screen came back on with NSXL showing: play the wake animation
            sleptWhileShowing = false;     // ...but only once it can be seen, not behind the lock screen
            wakePending = true;
        }
        if (hasWindowFocus()) showWoken();
        applySystemBars();
        js("window.onResumeApp&&window.onResumeApp()");
    }

    @Override
    protected void onPause() {
        js("window.onPauseApp&&window.onPauseApp()");   // stop the music when a game opens
        OverlayService.setLauncherVisible(false);
        resumed = false;
        pausedAt = android.os.SystemClock.uptimeMillis();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Free the video decoder and pause the page's animations while a game is running.
        video.stop();
        if (web != null) web.onPause();
        setStick(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (pads != null) pads.release();
        if (mediaWatch != null) mediaWatch.stop();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        video.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { applySystemBars(); showWoken(); }
    }

    /** NSXL is really on screen (resumed, focused, lock screen gone): play the wake animation if it slept on Home. */
    private void showWoken() {
        if (!resumed) return;
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) return;   // focus comes back after unlocking
        if (wakePending) { wakePending = false; js("window.onWake&&window.onWake()"); }
        else js("window.onShown&&window.onShown()");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while we're already on screen: go back to the first tile.
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())) {
            js("window.onHome&&window.onHome()");
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // A launcher never closes itself; Back is just the B button.
        sendPad("b", false);
    }

    // ------------------------------------------------------------------ web view

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(true);
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);   // no text-selection popups

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(bridge, "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return server.handle(request.getUrl(), request.getRequestHeaders());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the home screen never navigates away
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Android may kill the page's renderer when a game needs memory. Rebuild it.
                ui.post(MainActivity.this::recreateWebView);
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));
        web.loadUrl(ORIGIN + "index.html");
    }

    private void recreateWebView() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        createWebView();
    }

    void js(final String script) {
        ui.post(() -> {
            if (web != null) web.evaluateJavascript(script, null);
        });
    }

    /** Resolves a promise made by nbAsync() in the page. {@code jsonLiteral} must be valid JSON. */
    void asyncResult(String id, String jsonLiteral) {
        js("window.onAsync&&window.onAsync(" + JSONObject.quote(id) + ",true," + jsonLiteral + ")");
    }

    void asyncError(String id, Exception e) {
        String msg = e.getMessage();
        if (e instanceof java.net.UnknownHostException) msg = "No internet connection";
        if (msg == null || msg.isEmpty()) msg = "Something went wrong";
        js("window.onAsync&&window.onAsync(" + JSONObject.quote(id) + ",false," + JSONObject.quote(msg) + ")");
    }

    void jsToast(String msg) {
        js("window.toast&&window.toast(" + JSONObject.quote(msg) + ")");
    }

    void reloadPage() {
        if (web != null) web.reload();
    }

    // ------------------------------------------------------------------ theme / window

    static int backgroundFor(String mode) {
        return "light".equals(mode) ? 0xFFE9E9E9 : 0xFF000000;
    }

    void setMode(String newMode) {
        if (!"black".equals(newMode) && !"video".equals(newMode)) newMode = "light";
        mode = newMode;
        prefs.edit().putString("mode", newMode).apply();
        getWindow().setBackgroundDrawable(new ColorDrawable(backgroundFor(newMode)));
        boolean wantVideo = "video".equals(newMode) && Storage.videoFile(this).exists();
        videoEnabled = wantVideo;
        video.setVisibility(wantVideo ? View.VISIBLE : View.GONE);
        if (wantVideo && pageReady) video.play();
        else video.stop();
        applySystemBars();
    }

    void applyOrientation(String o) {
        setRequestedOrientation("auto".equals(o)
                ? ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @SuppressWarnings("deprecation")
    void applySystemBars() {
        Window w = getWindow();
        boolean light = "light".equals(mode);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(!immersive);
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(light ? lightBars : 0, lightBars);
            if (immersive) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = 0;
            if (immersive) {
                flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            } else if (light) {
                flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
            w.setStatusBarColor(backgroundFor(mode));
            w.setNavigationBarColor(backgroundFor(mode));
        }
    }

    // ------------------------------------------------------------------ launching

    String launchStyle = "zoom";

    void launchApp(String pkg, float x, float y, float w, float h) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            jsToast("Can't open this app");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("lastLaunched", pkg).apply();
        Bundle opts;
        if ("instant".equals(launchStyle)) {
            opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
        } else if (w > 0 && h > 0 && web != null && !"fade".equals(launchStyle)) {
            // Grow the app's window out of the tile it was started from.
            float d = getResources().getDisplayMetrics().density;
            opts = ActivityOptions.makeScaleUpAnimation(web,
                    Math.round(x * d), Math.round(y * d), Math.max(1, Math.round(w * d)), Math.max(1, Math.round(h * d))).toBundle();
        } else {
            opts = ActivityOptions.makeCustomAnimation(this, R.anim.launch_fade_in, R.anim.launch_hold).toBundle();
        }
        try {
            startActivity(i, opts);
            Play.start(this, pkg);
        } catch (Exception e) {
            jsToast("Can't open this app");
        }
    }

    // ------------------------------------------------------------------ controller input

    private final int[] lastPad = {-1};

    private void notePad(InputDevice d) {
        String js = PadKeys.noteDevice(getSharedPreferences("launcher", MODE_PRIVATE), d, lastPad);
        if (js != null) js(js);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) notePad(e.getDevice());
        if (typing && !KeyEvent.isGamepadButton(e.getKeyCode())) return super.dispatchKeyEvent(e);
        String b = mapKey(e.getKeyCode());
        if (b == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) sendPad(b, e.getRepeatCount() > 0);
        return true;
    }

    private String mapKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "a";
            case KeyEvent.KEYCODE_BUTTON_A: return swapAB ? "b" : "a";
            case KeyEvent.KEYCODE_BUTTON_B: return swapAB ? "a" : "b";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE: return "b";
            case KeyEvent.KEYCODE_BUTTON_X: return swapAB ? "y" : "x";
            case KeyEvent.KEYCODE_BUTTON_Y: return swapAB ? "x" : "y";
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU: return "plus";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "minus";
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2: return "l";
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2: return "r";
            default: return null;
        }
    }

    void sendPad(String button, boolean repeat) {
        js("window.onPad&&window.onPad('" + button + "'," + repeat + ")");
    }

    /** Analog sticks and hat-switch d-pads arrive as motion events. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        int src = ev.getSource();
        boolean pad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (pad && ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
            notePad(ev.getDevice());
            float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X), hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float x = ev.getAxisValue(MotionEvent.AXIS_X), y = ev.getAxisValue(MotionEvent.AXIS_Y);
            String dir = null;
            if (Math.abs(hx) > 0.5f || Math.abs(hy) > 0.5f) {
                dir = Math.abs(hx) >= Math.abs(hy) ? (hx < 0 ? "left" : "right") : (hy < 0 ? "up" : "down");
            } else if (Math.abs(x) > 0.55f || Math.abs(y) > 0.55f) {
                dir = Math.abs(x) >= Math.abs(y) ? (x < 0 ? "left" : "right") : (y < 0 ? "up" : "down");
            }
            setStick(dir);
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private final Runnable stickRepeat = new Runnable() {
        @Override public void run() {
            if (stickDir == null) return;
            sendPad(stickDir, true);
            ui.postDelayed(this, 110);
        }
    };

    private void setStick(String dir) {
        if (dir == null ? stickDir == null : dir.equals(stickDir)) return;
        stickDir = dir;
        ui.removeCallbacks(stickRepeat);
        if (dir != null) {
            sendPad(dir, false);
            ui.postDelayed(stickRepeat, 380);
        }
    }

    int connectedControllers() {
        int n = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ app install/remove

    private void registerPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                server.clear();
                js("window.onAppsChanged&&window.onAppsChanged()");
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packageReceiver, f);
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) { Play.screenOn(MainActivity.this); return; }
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    if (!resumed) Play.screenOff(MainActivity.this);
                    // Remember if NSXL was on screen when it went to sleep (onPause may already have run).
                    if (resumed || android.os.SystemClock.uptimeMillis() - pausedAt < 1500) sleptWhileShowing = true;
                    js("window.onScreenOff&&window.onScreenOff()");
                }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, f);
    }

    // ------------------------------------------------------------------ pickers (video, cover art, avatar)

    void pickContent(String mime, int request) { pickContent(mime, null, request); }

    void pickContent(String mime, String[] also, int request) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(mime);
        if (also != null) i.putExtra(Intent.EXTRA_MIME_TYPES, also);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, request);
        } catch (ActivityNotFoundException e) {
            jsToast("No file picker found on this device");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_VIDEO) importVideo(uri);
        else if (requestCode == REQ_COVER) {
            String type = uri == null ? null : getContentResolver().getType(uri);
            if (type != null && type.startsWith("video/")) importCoverVideo(uri, pendingCoverPkg);
            else importImage(uri, "cover", pendingCoverPkg);
        }
        else if (requestCode == REQ_AVATAR) importImage(uri, "avatar", "");
        else if (requestCode == REQ_MUSIC) importMusic(uri);
        else if (requestCode == REQ_ALBUM_DELETE) js("window.onAlbumDeleted&&window.onAlbumDeleted(" + (resultCode == RESULT_OK) + ")");
    }

    // ------------------------------------------------------------------ album

    void albumRequest() {
        if ("full".equals(Album.access(this)) || Build.VERSION.SDK_INT < 23) {
            js("window.onAlbumAccess&&window.onAlbumAccess('full')");
            return;
        }
        requestPermissions(Album.permissions(), REQ_ALBUM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ALBUM) js("window.onAlbumAccess&&window.onAlbumAccess('" + Album.access(this) + "')");
        if (requestCode == REQ_PADS) js("window.onPads&&window.onPads(" + pads.state() + ")");
    }

    void albumDelete(long id) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.app.PendingIntent pi = android.provider.MediaStore.createDeleteRequest(getContentResolver(),
                        java.util.Collections.singletonList(Album.uri(id)));
                startIntentSenderForResult(pi.getIntentSender(), REQ_ALBUM_DELETE, null, 0, 0, 0);
                return;
            }
            try {
                boolean ok = getContentResolver().delete(Album.uri(id), null, null) > 0;
                js("window.onAlbumDeleted&&window.onAlbumDeleted(" + ok + ")");
            } catch (SecurityException se) {
                if (Build.VERSION.SDK_INT >= 29 && se instanceof android.app.RecoverableSecurityException) {
                    startIntentSenderForResult(((android.app.RecoverableSecurityException) se).getUserAction().getActionIntent().getIntentSender(),
                            REQ_ALBUM_DELETE, null, 0, 0, 0);
                } else throw se;
            }
        } catch (Exception e) {
            jsToast("Couldn't delete that screenshot");
            js("window.onAlbumDeleted&&window.onAlbumDeleted(false)");
        }
    }

    /** Copies the chosen video into private storage so it keeps working even if the original moves. */
    private void importVideo(final Uri uri) {
        if (uri == null) {
            js("window.onVideoPicked&&window.onVideoPicked(false,'')");
            return;
        }
        js("window.onVideoProgress&&window.onVideoProgress(0)");
        new Thread(() -> {
            File out = Storage.videoFile(this);
            File tmp = new File(out.getParentFile(), "wallpaper.tmp");
            long total = Storage.querySize(this, uri);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Can't open video");
                byte[] buf = new byte[256 * 1024];
                long done = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int p = (int) Math.min(100, done * 100 / total);
                        if (p - last >= 2) {
                            last = p;
                            js("window.onVideoProgress&&window.onVideoProgress(" + p + ")");
                        }
                    }
                }
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                js("window.onVideoPicked&&window.onVideoPicked(false," + JSONObject.quote("Couldn't import that video") + ")");
                return;
            }
            ui.post(() -> {
                video.stop();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                if (!tmp.renameTo(out)) {
                    js("window.onVideoPicked&&window.onVideoPicked(false," + JSONObject.quote("Couldn't save that video") + ")");
                    return;
                }
                video.setVideoFile(out);
                js("window.onVideoPicked&&window.onVideoPicked(true,'')");
            });
        }, "video-import").start();
    }

    private void importMusic(final Uri uri) {
        if (uri == null) {
            js("window.onMusicPicked&&window.onMusicPicked(false,'')");
            return;
        }
        String type = getContentResolver().getType(uri);
        jsToast("Importing music…");
        new Thread(() -> {
            File out = Storage.musicFile(this);
            File tmp = new File(out.getParentFile(), "music.tmp");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Can't open file");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                js("window.onMusicPicked&&window.onMusicPicked(false," + JSONObject.quote("Couldn't import that file") + ")");
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            boolean ok = tmp.renameTo(out);
            prefs.edit().putString("musicMime", type != null && type.startsWith("audio/") ? type : "audio/mpeg").apply();
            js("window.onMusicPicked&&window.onMusicPicked(" + ok + ",'')");
        }, "music-import").start();
    }

    /** A moving tile: copies the chosen video next to the covers. */
    private void importCoverVideo(final Uri uri, final String pkg) {
        new Thread(() -> {
            File out = Storage.coverVideoFile(this, pkg), tmp = new File(out.getPath() + ".tmp");
            boolean ok = false;
            long size = Storage.querySize(this, uri);
            if (size > 300L * 1024 * 1024) {
                js("window.onImagePicked&&window.onImagePicked('coverVideo'," + JSONObject.quote(pkg) + ",false,'That video is over 300 MB. Pick a shorter clip.')");
                return;
            }
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Can't open file");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                ok = true;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            if (ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                ok = tmp.renameTo(out);
            }
            js("window.onImagePicked&&window.onImagePicked('coverVideo'," + JSONObject.quote(pkg) + "," + ok + ")");
        }, "video-cover-import").start();
    }

    private void importImage(final Uri uri, final String kind, final String pkg) {
        if (uri == null) return;
        new Thread(() -> {
            boolean avatar = "avatar".equals(kind);
            File out = avatar ? Storage.avatarFile(this) : Storage.coverFile(this, pkg);
            boolean ok = Storage.saveSquareImage(this, uri, out, avatar ? 320 : 720);
            if (ok && !avatar) {
                server.invalidate(pkg);
                //noinspection ResultOfMethodCallIgnored
                Storage.coverVideoFile(this, pkg).delete();   // a new picture replaces a moving tile
            }
            js("window.onImagePicked&&window.onImagePicked(" + JSONObject.quote(kind) + ","
                    + JSONObject.quote(pkg == null ? "" : pkg) + "," + ok + ")");
        }, "image-import").start();
    }
}
