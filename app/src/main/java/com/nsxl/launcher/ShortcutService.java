package com.nsxl.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Optional accessibility service that watches controller buttons so a chosen shortcut
 * (for example holding Select) opens the NSXL quick menu over any game.
 * It only looks at gamepad buttons, never reads the screen, and lets every button
 * through to the game except a dedicated Guide-button shortcut.
 * It also lets the quick menu take screenshots and put the device to sleep.
 */
public class ShortcutService extends AccessibilityService {

    static ShortcutService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<Integer> held = new HashSet<>();
    private SharedPreferences prefs;
    private boolean comboFired;
    private long lastGuide;
    private final Runnable fire = this::openMenu;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean pad = KeyEvent.isGamepadButton(code)
                || (e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (!pad || prefs == null) return false;
        String mode = prefs.getString("shortcut", "hold_select");
        if ("off".equals(mode)) return false;
        // While the menu is open it handles its own buttons.
        if (OverlayService.instance != null && OverlayService.instance.isMenuShown()) return false;

        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down) held.add(code); else held.remove(code);
        if (held.isEmpty()) comboFired = false;

        switch (mode) {
            case "hold_select":
                return hold(code, KeyEvent.KEYCODE_BUTTON_SELECT, e);
            case "hold_start":
                return hold(code, KeyEvent.KEYCODE_BUTTON_START, e);
            case "select_start":
                return combo(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START);
            case "l3_r3":
                return combo(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR);
            case "guide":
                if (code != KeyEvent.KEYCODE_BUTTON_MODE) return false;
                if (down && e.getRepeatCount() == 0) ui.post(fire);
                return true;   // a Guide shortcut owns that button
            case "double_guide":
                if (code != KeyEvent.KEYCODE_BUTTON_MODE) return false;
                if (down && e.getRepeatCount() == 0) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastGuide < 450) { lastGuide = 0; ui.post(fire); return true; }
                    lastGuide = now;
                }
                return false;
            default:
                return false;
        }
    }

    private boolean hold(int code, int target, KeyEvent e) {
        if (code != target) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) ui.postDelayed(fire, 650);
        else if (e.getAction() == KeyEvent.ACTION_UP) ui.removeCallbacks(fire);
        return false;   // the game still sees the button
    }

    private boolean combo(int a, int b) {
        if (!comboFired && held.contains(a) && held.contains(b)) {
            comboFired = true;
            ui.post(fire);
        }
        return false;
    }

    private void openMenu() {
        if (prefs == null || !prefs.getBoolean("overlay", false) || !Settings.canDrawOverlays(this)) return;
        try {
            OverlayService.start(this, OverlayService.ACTION_TOGGLE);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ extras for the quick menu

    static boolean takeScreenshot() {
        return instance != null && android.os.Build.VERSION.SDK_INT >= 28
                && instance.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
    }

    static boolean sleep() {
        return instance != null && android.os.Build.VERSION.SDK_INT >= 28
                && instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }
}
