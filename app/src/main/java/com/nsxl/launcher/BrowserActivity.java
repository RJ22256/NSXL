package com.nsxl.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * NSXL's built-in web browser. Works with touch, and with a controller like a console browser:
 * the left stick (or D-pad) moves a pointer, A clicks, the right stick scrolls, B goes back,
 * L/R go back/forward, Y types an address, X reloads and + opens the menu.
 */
public class BrowserActivity extends Activity {

    static final String START = "https://launcher.local/browser/start.html";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final int REQ_FILE = 71;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean light;
    private Typeface font;

    private FrameLayout root, page;
    private LinearLayout bar, hints;
    private WebView web;
    private EditText address;
    private View progress, cursor, fullView;
    private WebChromeClient.CustomViewCallback fullCb;
    private ImageView backBtn, fwdBtn, starBtn;
    private String mobileUa;
    private ValueCallback<Uri[]> fileCallback;

    // pointer
    private float cx = -1, cy = -1;
    private float lx, ly, rx, ry, hx, hy;   // left stick, right stick, hat / d-pad
    private boolean ticking, padMode, cursorShown;
    private String family = "generic";
    private final int[] lastPad = {-1};

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        light = "light".equals(prefs.getString("mode", "light"));
        try { font = Typeface.createFromAsset(getAssets(), "www/fonts/Montserrat.ttf"); } catch (Exception e) { font = Typeface.DEFAULT; }
        setRequestedOrientation(getIntent().getIntExtra("orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        // Button hints only when a controller is actually in use (NSXL tells us), else after the first button press.
        padMode = getIntent().getBooleanExtra("pad", false) && PadKeys.connectedFamily() != null;
        family = prefs.getString("padFamily", "generic");

        int bg = light ? 0xFFEBEBEB : 0xFF15151B, fg = light ? 0xFF1D1D1D : 0xFFF2F2F5;
        getWindow().getDecorView().setBackgroundColor(bg);
        hideSystemBars();

        root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // --- top bar
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setBackgroundColor(bg);
        backBtn = barButton(R.drawable.ic_b_back, "Back", v -> goBack());
        fwdBtn = barButton(R.drawable.ic_b_forward, "Forward", v -> { if (web.canGoForward()) web.goForward(); });
        ImageView reload = barButton(R.drawable.ic_b_reload, "Reload", v -> web.reload());
        bar.addView(backBtn); bar.addView(fwdBtn); bar.addView(reload);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTypeface(font);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        address.setTextColor(fg);
        address.setHintTextColor(light ? 0xFF8A8A92 : 0xFF8A8A96);
        address.setHint("Search or type an address");
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT);
        address.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(20));
        pill.setColor(light ? 0xFFFFFFFF : 0xFF26262F);
        address.setBackground(pill);
        address.setOnEditorActionListener((v, id, e) -> { go(address.getText().toString()); return true; });
        address.setOnFocusChangeListener((v, has) -> { if (!has) showUrl(web.getUrl()); });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(40), 1);
        ap.setMargins(dp(8), 0, dp(8), 0);
        bar.addView(address, ap);

        starBtn = barButton(R.drawable.ic_b_star, "Bookmark", v -> toggleBookmark());
        ImageView home = barButton(R.drawable.ic_b_home, "Start page", v -> web.loadUrl(START));
        ImageView close = barButton(R.drawable.ic_b_close, "Close browser", v -> finish());
        bar.addView(starBtn); bar.addView(home); bar.addView(close);
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        // --- page
        page = new FrameLayout(this);
        col.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this);
        page.addView(web, new FrameLayout.LayoutParams(-1, -1));
        progress = new View(this);
        GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2F6DFF, 0xFFC6A6FF, 0xFFFFBFE8, 0xFF63A0FF});
        progress.setBackground(pg);
        progress.setPivotX(0);
        progress.setScaleX(0);
        page.addView(progress, new FrameLayout.LayoutParams(-1, dp(3)));

        // --- controller hints
        hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        hints.setPadding(dp(16), dp(5), dp(16), dp(6));
        hints.setBackgroundColor(bg);
        col.addView(hints, new LinearLayout.LayoutParams(-1, -2));

        // --- pointer
        cursor = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xCCFFFFFF);
        dot.setStroke(dp(3), 0xFF3D7BFF);
        cursor.setBackground(dot);
        cursor.setElevation(dp(6));
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(dp(22), dp(22)));

        setContentView(root);
        setupWeb(bg);
        drawHints();

        String url = getIntent().getStringExtra("url");
        web.loadUrl(url == null || url.isEmpty() ? START : url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb(int bg) {
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        mobileUa = s.getUserAgentString();
        if (prefs.getBoolean("browserDesktop", false)) s.setUserAgentString(DESKTOP_UA);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"launcher.local".equals(u.getHost())) return null;
                return localFile(u.getPath());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                // intent:, market:, mailto: etc. go to the app that handles them
                try {
                    Intent i = "intent".equals(scheme) ? Intent.parseUri(r.getUrl().toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, r.getUrl());
                    i.addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null).setSelector(null);
                    startActivity(i);
                } catch (Exception e) {
                    toast("No app can open that link");
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap fav) {
                showUrl(url);
                updateButtons();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                showUrl(url);
                updateButtons();
                if (url != null && url.startsWith(START)) feedStartPage();
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                showUrl(url);
                updateButtons();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.animate().cancel();
                progress.setAlpha(1);
                progress.animate().scaleX(p / 100f).setDuration(160).start();
                if (p >= 100) progress.animate().alpha(0).setStartDelay(250).setDuration(250).withEndAction(() -> progress.setScaleX(0)).start();
            }

            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {   // full-screen video
                if (fullView != null) { cb.onCustomViewHidden(); return; }
                fullView = v;
                fullCb = cb;
                v.setBackgroundColor(Color.BLACK);
                root.addView(v, new FrameLayout.LayoutParams(-1, -1));
                cursor.bringToFront();
                setChrome(false);
            }

            @Override
            public void onHideCustomView() {
                if (fullView == null) return;
                root.removeView(fullView);
                fullView = null;
                fullCb = null;
                setChrome(true);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            // Hand downloads to the phone's own handler (usually its browser or download manager).
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                toast("Can't download that here");
            }
        });
        web.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_DOWN) setPadMode(false); return false; });
    }

    /** Serves only the start page and its font: no other launcher data is reachable from websites. */
    private WebResourceResponse localFile(String path) {
        if (path == null) return null;
        String asset;
        if (path.startsWith("/browser/") && !path.contains("..")) asset = "www" + path;
        else if (path.equals("/fonts/Montserrat.ttf")) asset = "www/fonts/Montserrat.ttf";
        else return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
        try {
            InputStream in = getAssets().open(asset);
            String mime = asset.endsWith(".html") ? "text/html" : asset.endsWith(".ttf") ? "font/ttf" : asset.endsWith(".js") ? "text/javascript" : "application/octet-stream";
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
        }
    }

    // ------------------------------------------------------------------ start page, bookmarks, search

    private String engine() {
        return prefs.getString("browserEngine", "google");
    }

    private String searchUrl(String q) {
        String e = Uri.encode(q);
        switch (engine()) {
            case "duckduckgo": return "https://duckduckgo.com/?q=" + e;
            case "bing": return "https://www.bing.com/search?q=" + e;
            default: return "https://www.google.com/search?q=" + e;
        }
    }

    private void go(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        String url;
        if (text.matches("(?i)^https?://.*")) url = text;
        else if (!text.contains(" ") && text.matches("(?i)^[\\w.-]+\\.[a-z]{2,}(:\\d+)?(/.*)?$")) url = "https://" + text;
        else url = searchUrl(text);
        web.loadUrl(url);
        address.clearFocus();
        hideKeyboard();
        web.requestFocus();
    }

    private JSONArray bookmarks() {
        try { return new JSONArray(prefs.getString("bookmarks", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private int bookmarkIndex(String url) {
        JSONArray b = bookmarks();
        for (int i = 0; i < b.length(); i++) if (url != null && url.equals(b.optJSONObject(i).optString("url"))) return i;
        return -1;
    }

    private void toggleBookmark() {
        String url = web.getUrl();
        if (url == null || url.startsWith(START)) { toast("Open a page to bookmark it"); return; }
        JSONArray b = bookmarks();
        int i = bookmarkIndex(url);
        if (i >= 0) {
            b.remove(i);
            toast("Bookmark removed");
        } else {
            try { b.put(new JSONObject().put("url", url).put("title", TextUtils.isEmpty(web.getTitle()) ? Uri.parse(url).getHost() : web.getTitle())); } catch (Exception ignored) { }
            toast("Bookmarked");
        }
        prefs.edit().putString("bookmarks", b.toString()).apply();
        updateButtons();
    }

    private void feedStartPage() {
        try {
            JSONObject o = new JSONObject();
            o.put("bookmarks", bookmarks());
            o.put("engine", engine());
            o.put("light", light);
            web.evaluateJavascript("window.nsxlStart&&window.nsxlStart(" + o + ")", null);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ chrome

    private ImageView barButton(int icon, String label, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        v.setColorFilter(light ? 0xFF2A2A30 : 0xFFE8E8EE);
        v.setContentDescription(label);
        v.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.TRANSPARENT);
        v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x333D7BFF), null, g));
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return v;
    }

    private void showUrl(String url) {
        if (address.hasFocus() || url == null) return;
        address.setText(url.startsWith(START) ? "" : url);
    }

    private void updateButtons() {
        backBtn.setAlpha(web.canGoBack() ? 1f : .35f);
        fwdBtn.setAlpha(web.canGoForward() ? 1f : .35f);
        String url = web.getUrl();
        starBtn.setImageResource(bookmarkIndex(url) >= 0 ? R.drawable.ic_b_star_on : R.drawable.ic_b_star);
        starBtn.setColorFilter(bookmarkIndex(url) >= 0 ? 0xFF3D7BFF : (light ? 0xFF2A2A30 : 0xFFE8E8EE));
    }

    private void setChrome(boolean on) {
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        hints.setVisibility(on && padMode ? View.VISIBLE : View.GONE);
    }

    private void drawHints() {
        hints.removeAllViews();
        String[][] list = {{"a", "Click"}, {"b", "Back"}, {"y", "Address"}, {"x", "Reload"}, {"lr", "Back / Forward"}, {"plus", "Menu"}};
        for (String[] h : list) {
            LinearLayout one = new LinearLayout(this);
            one.setGravity(Gravity.CENTER_VERTICAL);
            one.setPadding(dp(14), 0, 0, 0);
            if (h[0].equals("lr")) { one.addView(chip(glyph("l"))); one.addView(chip(glyph("r"))); }
            else one.addView(chip(glyph(h[0])));
            TextView t = new TextView(this);
            t.setText(h[1]);
            t.setTypeface(font);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTextColor(light ? 0xFF3A3A40 : 0xFFD8D8DE);
            t.setPadding(dp(6), 0, 0, 0);
            one.addView(t);
            hints.addView(one);
        }
        hints.setVisibility(padMode && bar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    /** Button label for the controller in use, by position (respects Swap A and B). */
    private String glyph(String b) {
        boolean swap = prefs.getBoolean("swapAB", false);
        String pos;
        switch (b) {
            case "a": pos = swap ? "E" : "S"; break;
            case "b": pos = swap ? "S" : "E"; break;
            case "x": pos = swap ? "N" : "W"; break;
            case "y": pos = swap ? "W" : "N"; break;
            default: pos = b;
        }
        switch (family) {
            case "ps":
                switch (pos) { case "S": return "✕"; case "E": return "○"; case "W": return "□"; case "N": return "△"; case "l": return "L1"; case "r": return "R1"; case "plus": return "≡"; }
                break;
            case "xbox":
                switch (pos) { case "S": return "A"; case "E": return "B"; case "W": return "X"; case "N": return "Y"; case "l": return "LB"; case "r": return "RB"; case "plus": return "≡"; }
                break;
            case "nintendo":
                switch (pos) { case "S": return "B"; case "E": return "A"; case "W": return "Y"; case "N": return "X"; case "l": return "L"; case "r": return "R"; case "plus": return "+"; }
                break;
        }
        switch (pos) { case "S": return "A"; case "E": return "B"; case "W": return "X"; case "N": return "Y"; case "l": return "L"; case "r": return "R"; case "plus": return "+"; }
        return b.toUpperCase(Locale.ROOT);
    }

    private TextView chip(String label) {
        TextView c = new TextView(this);
        c.setText(label);
        c.setTypeface(font, Typeface.BOLD);
        c.setGravity(Gravity.CENTER);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 1 ? 9 : 11);
        c.setTextColor(light ? Color.WHITE : 0xFF15151B);
        GradientDrawable g = new GradientDrawable();
        g.setColor(light ? 0xFF2A2A30 : 0xFFF2F2F5);
        g.setCornerRadius(dp(10));
        c.setBackground(g);
        c.setMinWidth(dp(20));
        c.setPadding(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(20));
        lp.setMargins(dp(2), 0, 0, 0);
        c.setLayoutParams(lp);
        return c;
    }

    private void menu() {
        String url = web.getUrl();
        boolean marked = bookmarkIndex(url) >= 0;
        boolean desktop = prefs.getBoolean("browserDesktop", false);
        String eng = engine();
        String[] items = {
                marked ? "Remove bookmark" : "Bookmark this page",
                "Bookmarks…",
                "Search with: " + ("duckduckgo".equals(eng) ? "DuckDuckGo" : "bing".equals(eng) ? "Bing" : "Google"),
                desktop ? "Use mobile site" : "Request desktop site",
                "Zoom in", "Zoom out",
                "Share link…",
                "Open in another browser",
                "Clear history and cookies",
                "Close browser",
        };
        new AlertDialog.Builder(this, light ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: toggleBookmark(); break;
                        case 1: bookmarkList(); break;
                        case 2:
                            String next = "google".equals(eng) ? "duckduckgo" : "duckduckgo".equals(eng) ? "bing" : "google";
                            prefs.edit().putString("browserEngine", next).apply();
                            toast("Searching with " + ("duckduckgo".equals(next) ? "DuckDuckGo" : "bing".equals(next) ? "Bing" : "Google"));
                            if (url != null && url.startsWith(START)) feedStartPage();
                            break;
                        case 3:
                            prefs.edit().putBoolean("browserDesktop", !desktop).apply();
                            web.getSettings().setUserAgentString(desktop ? mobileUa : DESKTOP_UA);
                            web.reload();
                            break;
                        case 4: web.zoomIn(); break;
                        case 5: web.zoomOut(); break;
                        case 6:
                            if (url != null && !url.startsWith(START))
                                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share link"));
                            break;
                        case 7:
                            if (url != null && !url.startsWith(START)) {
                                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (Exception e) { toast("No other browser found"); }
                            }
                            break;
                        case 8:
                            web.clearHistory();
                            web.clearCache(true);
                            CookieManager.getInstance().removeAllCookies(null);
                            android.webkit.WebStorage.getInstance().deleteAllData();
                            toast("History and cookies cleared");
                            web.loadUrl(START);
                            break;
                        case 9: finish(); break;
                    }
                }).show();
    }

    private void bookmarkList() {
        JSONArray b = bookmarks();
        if (b.length() == 0) { toast("No bookmarks yet"); return; }
        String[] names = new String[b.length()];
        for (int i = 0; i < b.length(); i++) names[i] = b.optJSONObject(i).optString("title");
        new AlertDialog.Builder(this, light ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Bookmarks")
                .setItems(names, (d, w) -> web.loadUrl(b.optJSONObject(w).optString("url")))
                .show();
    }

    private void exitFullscreen() {
        if (fullCb != null) fullCb.onCustomViewHidden();
        WebChromeClient c = web.getWebChromeClient();
        if (fullView != null && c != null) c.onHideCustomView();
    }

    private void goBack() {
        if (web.canGoBack()) web.goBack();
        else finish();
    }

    // ------------------------------------------------------------------ controller

    private void setPadMode(boolean on) {
        if (padMode == on) return;
        padMode = on;
        hints.setVisibility(on && bar.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        if (!on) showCursor(false);
    }

    private void notePad(InputDevice d) {
        if (PadKeys.noteDevice(prefs, d, lastPad) != null) {
            family = prefs.getString("padFamily", "generic");
            drawHints();
        }
        setPadMode(true);
    }

    private void showCursor(boolean on) {
        if (on && cx < 0) { cx = root.getWidth() / 2f; cy = root.getHeight() / 2f; }
        cursorShown = on;
        cursor.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) placeCursor();
    }

    private void placeCursor() {
        cursor.setTranslationX(cx - cursor.getWidth() / 2f);
        cursor.setTranslationY(cy - cursor.getHeight() / 2f);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean pad = KeyEvent.isGamepadButton(k) || ((e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((e.getSource() & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD && e.getDevice() != null && !e.getDevice().isVirtual()
                    && (e.getDevice().getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
        if (!pad) return super.dispatchKeyEvent(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down) notePad(e.getDevice());

        // typing an address: let the text box have everything except B
        if (address.hasFocus()) {
            if ((k == KeyEvent.KEYCODE_BUTTON_B && !prefs.getBoolean("swapAB", false)) || (k == KeyEvent.KEYCODE_BUTTON_A && prefs.getBoolean("swapAB", false))) {
                if (down) { address.clearFocus(); hideKeyboard(); web.requestFocus(); }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (prefs.getBoolean("swapAB", false)) {
            if (k == KeyEvent.KEYCODE_BUTTON_A) k = KeyEvent.KEYCODE_BUTTON_B;
            else if (k == KeyEvent.KEYCODE_BUTTON_B) k = KeyEvent.KEYCODE_BUTTON_A;
            else if (k == KeyEvent.KEYCODE_BUTTON_X) k = KeyEvent.KEYCODE_BUTTON_Y;
            else if (k == KeyEvent.KEYCODE_BUTTON_Y) k = KeyEvent.KEYCODE_BUTTON_X;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: hx = down ? -1 : 0; startTicking(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: hx = down ? 1 : 0; startTicking(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: hy = down ? -1 : 0; startTicking(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: hy = down ? 1 : 0; startTicking(); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (down && e.getRepeatCount() == 0) { if (!cursorShown) showCursor(true); else click(); }
                return true;
        }
        if (!down || e.getRepeatCount() > 0) return true;
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK:
                if (fullView != null) { exitFullscreen(); return true; }
                goBack(); return true;
            case KeyEvent.KEYCODE_BUTTON_L1: if (web.canGoBack()) web.goBack(); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: if (web.canGoForward()) web.goForward(); return true;
            case KeyEvent.KEYCODE_BUTTON_L2: web.zoomOut(); return true;
            case KeyEvent.KEYCODE_BUTTON_R2: web.zoomIn(); return true;
            case KeyEvent.KEYCODE_BUTTON_X: web.reload(); return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (bar.getVisibility() != View.VISIBLE) return true;
                address.requestFocus();
                address.selectAll();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
                return true;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_MENU: menu(); return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT: web.loadUrl(START); return true;
            case KeyEvent.KEYCODE_BUTTON_MODE: finish(); return true;
        }
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        int s = ev.getSource();
        boolean pad = (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK || (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (!pad || ev.getActionMasked() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(ev);
        notePad(ev.getDevice());
        lx = dz(ev.getAxisValue(MotionEvent.AXIS_X));
        ly = dz(ev.getAxisValue(MotionEvent.AXIS_Y));
        rx = dz(ev.getAxisValue(MotionEvent.AXIS_Z));
        ry = dz(ev.getAxisValue(MotionEvent.AXIS_RZ));
        hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
        hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        // analog triggers as zoom
        startTicking();
        return true;
    }

    private static float dz(float v) {
        float a = Math.abs(v);
        if (a < .18f) return 0;
        float n = (a - .18f) / .82f;
        return Math.signum(v) * n * n;   // gentle near the centre, fast at the edge
    }

    private long lastTick;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(48, Math.max(8, now - lastTick)) / 16f;
            lastTick = now;
            float mx = lx + hx * .55f, my = ly + hy * .55f;
            boolean moving = mx != 0 || my != 0, scrolling = rx != 0 || ry != 0;
            if (!moving && !scrolling) { ticking = false; return; }
            if (moving) {
                if (!cursorShown) showCursor(true);
                float speed = dp(16) * dt;
                cx = Math.max(0, Math.min(root.getWidth() - 1, cx + mx * speed));
                cy = Math.max(0, Math.min(root.getHeight() - 1, cy + my * speed));
                placeCursor();
                hover();
                // pushing against the top or bottom of the page scrolls it, like a console browser
                int[] wl = new int[2];
                web.getLocationInWindow(wl);
                if (my > 0 && cy >= wl[1] + web.getHeight() - dp(6)) scrollAt(0, my * dp(26) * dt);
                if (my < 0 && cy <= wl[1] + dp(6)) scrollAt(0, my * dp(26) * dt);
            }
            if (scrolling) scrollAt(rx * dp(30) * dt, ry * dp(30) * dt);
            ui.postDelayed(this, 16);
        }
    };

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        lastTick = SystemClock.uptimeMillis();
        ui.post(tick);
    }

    /** Page coordinates under the pointer. */
    private float[] inWeb() {
        int[] wl = new int[2], rl = new int[2];
        web.getLocationInWindow(wl);
        root.getLocationInWindow(rl);
        return new float[]{cx + rl[0] - wl[0], cy + rl[1] - wl[1]};
    }

    private void click() {
        float[] p = inWeb();
        if (p[1] < 0) {   // over the bar: press whatever button is there
            View hit = findBarChild(cx, cy);
            if (hit != null) {
                if (hit == address) { address.requestFocus(); InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE); if (imm != null) imm.showSoftInput(address, 0); }
                else hit.performClick();
            }
            return;
        }
        if (p[1] > web.getHeight()) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p[0], p[1], 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(d);
        d.recycle();
        ui.postDelayed(() -> {
            MotionEvent u = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, p[0], p[1], 0);
            u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(u);
            u.recycle();
        }, 40);
        cursor.animate().scaleX(.75f).scaleY(.75f).setDuration(70).withEndAction(() -> cursor.animate().scaleX(1).scaleY(1).setDuration(120).start()).start();
    }

    private View findBarChild(float x, float y) {
        int[] l = new int[2], rl = new int[2];
        root.getLocationInWindow(rl);
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            c.getLocationInWindow(l);
            float ax = x + rl[0], ay = y + rl[1];
            if (ax >= l[0] && ax < l[0] + c.getWidth() && ay >= l[1] && ay < l[1] + c.getHeight()) return c;
        }
        return null;
    }

    private void hover() {
        float[] p = inWeb();
        if (p[1] < 0 || p[1] > web.getHeight()) return;
        MotionEvent h = obtainPointer(MotionEvent.ACTION_HOVER_MOVE, p[0], p[1], 0, 0);
        web.dispatchGenericMotionEvent(h);
        h.recycle();
    }

    /** Scrolls whatever is under the pointer (inner panels too), the way a mouse wheel does. */
    private void scrollAt(float dx, float dy) {
        float[] p = cursorShown ? inWeb() : new float[]{web.getWidth() / 2f, web.getHeight() / 2f};
        p[1] = Math.max(1, Math.min(web.getHeight() - 1, p[1]));
        float unit = dp(64);
        MotionEvent sc = obtainPointer(MotionEvent.ACTION_SCROLL, p[0], p[1], -dx / unit, -dy / unit);
        boolean used = web.dispatchGenericMotionEvent(sc);
        sc.recycle();
        if (!used) web.scrollBy(Math.round(dx), Math.round(dy));
    }

    private static MotionEvent obtainPointer(int action, float x, float y, float hScroll, float vScroll) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, 1, new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    // ------------------------------------------------------------------ misc

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.home_settle, R.anim.browser_out);
    }

    @Override
    public void onBackPressed() {
        if (fullView != null) { exitFullscreen(); return; }
        if (address.hasFocus()) { address.clearFocus(); hideKeyboard(); return; }
        goBack();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
