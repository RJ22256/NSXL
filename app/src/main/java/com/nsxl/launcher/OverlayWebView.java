package com.nsxl.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;

/** The quick menu's WebView: turns controller buttons and sticks into window.onPad('…') calls. */
final class OverlayWebView extends WebView {

    private final SharedPreferences prefs;
    private String stickDir;

    OverlayWebView(Context c, SharedPreferences prefs) {
        super(c);
        this.prefs = prefs;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setLongClickable(false);
        setOnLongClickListener(v -> true);
    }

    void js(String script) {
        post(() -> evaluateJavascript(script, null));
    }

    private final int[] lastPad = {-1};

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            String kind = PadKeys.noteDevice(prefs, e.getDevice(), lastPad);
            if (kind != null) js(kind);
        }
        String b = PadKeys.map(e.getKeyCode(), prefs.getBoolean("swapAB", false));
        if (b == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            js("window.onPad&&window.onPad('" + b + "'," + (e.getRepeatCount() > 0) + ")");
        }
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        int src = ev.getSource();
        boolean pad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (pad && ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
            String kind = PadKeys.noteDevice(prefs, ev.getDevice(), lastPad);
            if (kind != null) js(kind);
            String dir = PadKeys.direction(ev);
            if (dir == null ? stickDir != null : !dir.equals(stickDir)) {
                stickDir = dir;
                if (dir != null) js("window.onPad&&window.onPad('" + dir + "',false)");
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }
}
