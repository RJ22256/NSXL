package com.nsxl.launcher;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * The in-game quick menu. Runs as a foreground service so it stays available while a game is open.
 * Shows a small handle on the screen edge; tapping it (or the notification / Quick Settings tile)
 * opens a full-screen, see-through menu drawn by assets/www/overlay.html.
 */
public class OverlayService extends Service {

    static final String ACTION_TOGGLE = "com.nsxl.launcher.TOGGLE_MENU";
    static final String ACTION_SHOW = "com.nsxl.launcher.SHOW_MENU";
    static final String ACTION_STOP = "com.nsxl.launcher.STOP_OVERLAY";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    static OverlayService instance;
    private static boolean launcherVisible;

    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    LocalServer server;
    private WindowManager wm;
    private FrameLayout handle;
    private WindowManager.LayoutParams handleLp;
    private OverlayWebView menu;
    private boolean menuShown;

    // ------------------------------------------------------------------ control from elsewhere

    static void start(Context c, String action) {
        Intent i = new Intent(c, OverlayService.class);
        if (action != null) i.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, OverlayService.class));
    }

    /** The launcher itself doesn't need the edge handle. */
    static void setLauncherVisible(boolean visible) {
        launcherVisible = visible;
        if (instance != null) instance.ui.post(instance::updateHandle);
    }

    static void refreshHandle() {
        if (instance != null) instance.ui.post(() -> {
            instance.removeHandle();
            instance.updateHandle();
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        server = new LocalServer(this);
        goForeground();
        updateHandle();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();   // every start must re-confirm the foreground notification
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            prefs.edit().putBoolean("overlay", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(a)) {
            if (menuShown) hideMenu();
            else showMenu();
        } else if (ACTION_SHOW.equals(a)) {
            showMenu();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hideMenu();
        removeHandle();
        if (menu != null) {
            menu.destroy();
            menu = null;
        }
        instance = null;
        super.onDestroy();
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("overlay", "In-game menu", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setDescription("Keeps the NSXL quick menu ready while you play");
        nm.createNotificationChannel(ch);

        // An activity trampoline closes the notification shade before the menu appears.
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MenuTrampolineActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, "overlay")
                .setSmallIcon(R.drawable.ic_stat_menu)
                .setContentTitle("NSXL quick menu")
                .setContentText("Tap to open the menu over your game")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_menu), "Turn off", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, n);
    }

    // ------------------------------------------------------------------ edge handle

    void updateHandle() {
        boolean want = !menuShown && !launcherVisible
                && !prefs.getBoolean("overlayHideHandle", false)
                && Settings.canDrawOverlays(this);
        if (want && handle == null) addHandle();
        else if (!want && handle != null) removeHandle();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addHandle() {
        final float d = getResources().getDisplayMetrics().density;
        final boolean right = !"left".equals(prefs.getString("overlaySide", "right"));

        handle = new FrameLayout(this);
        View pill = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3EC5FF, 0xFF6F7BFF, 0xFFC6A6FF});
        g.setCornerRadius(4 * d);
        g.setStroke(Math.max(1, Math.round(d)), 0x66FFFFFF);
        pill.setBackground(g);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Math.round(6 * d), Math.round(64 * d),
                Gravity.CENTER_VERTICAL | (right ? Gravity.END : Gravity.START));
        plp.setMargins(Math.round(3 * d), 0, Math.round(3 * d), 0);
        handle.addView(pill, plp);
        handle.setAlpha(prefs.getFloat("overlayAlpha", 0.75f));
        handle.setContentDescription("Open NSXL quick menu");

        handleLp = new WindowManager.LayoutParams(Math.round(24 * d), Math.round(88 * d),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        handleLp.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        handleLp.y = prefs.getInt("overlayY", Math.round(140 * d));

        handle.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            int startY;
            boolean drag;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        startY = handleLp.y;
                        drag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dy) > 10 * d) drag = true;
                        if (drag) {
                            handleLp.y = Math.max(0, startY + Math.round(dy));
                            try { wm.updateViewLayout(handle, handleLp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (drag) prefs.edit().putInt("overlayY", handleLp.y).apply();
                        else {
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            showMenu();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            wm.addView(handle, handleLp);
        } catch (Exception e) {
            handle = null;
        }
    }

    void removeHandle() {
        if (handle == null) return;
        try { wm.removeView(handle); } catch (Exception ignored) { }
        handle = null;
    }

    // ------------------------------------------------------------------ the menu

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    void showMenu() {
        if (menuShown || !Settings.canDrawOverlays(this)) return;
        if (menu == null) {
            menu = new OverlayWebView(this, prefs);
            menu.setBackgroundColor(Color.TRANSPARENT);
            WebSettings s = menu.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setTextZoom(100);
            menu.addJavascriptInterface(new OverlayBridge(this), "Android");
            menu.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    return server.handle(request.getUrl());
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (menuShown) menu.js("window.onOpen&&window.onOpen()");
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    ui.post(() -> {
                        hideMenu();
                        if (menu != null) { menu.destroy(); menu = null; }
                    });
                    return true;
                }
            });
            menu.loadUrl(MainActivity.ORIGIN + "overlay.html");
        } else {
            menu.js("window.onOpen&&window.onOpen()");
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(MATCH, MATCH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(menu, lp);
            menuShown = true;
        } catch (Exception e) {
            return;
        }
        menu.requestFocus();
        updateHandle();
    }

    void hideMenu() {
        if (!menuShown) return;
        menuShown = false;
        try { wm.removeView(menu); } catch (Exception ignored) { }
        updateHandle();
    }

    void buzz() {
        if (menu != null && menuShown) menu.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    boolean isMenuShown() {
        return menuShown;
    }
}
