package com.nsxl.launcher;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Controller button → the names the web pages use ("a", "b", "left", "plus"…). */
final class PadKeys {

    private PadKeys() { }

    static String map(int code, boolean swapAB) {
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

    /** D-pad hat or left stick → a direction, or null when centred. */
    static String direction(MotionEvent ev) {
        float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X), hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float x = ev.getAxisValue(MotionEvent.AXIS_X), y = ev.getAxisValue(MotionEvent.AXIS_Y);
        if (Math.abs(hx) > 0.5f || Math.abs(hy) > 0.5f) {
            return Math.abs(hx) >= Math.abs(hy) ? (hx < 0 ? "left" : "right") : (hy < 0 ? "up" : "down");
        }
        if (Math.abs(x) > 0.55f || Math.abs(y) > 0.55f) {
            return Math.abs(x) >= Math.abs(y) ? (x < 0 ? "left" : "right") : (y < 0 ? "up" : "down");
        }
        return null;
    }

    // ---------------------------------------------------------------- which button labels to show

    /** "xbox", "ps", "nintendo" or "generic", from the controller's maker and name. */
    static String family(InputDevice d) {
        if (d == null) return "generic";
        int v = d.getVendorId();
        String n = d.getName() == null ? "" : d.getName().toLowerCase(java.util.Locale.ROOT);
        if (v == 0x054C || n.contains("dualsense") || n.contains("dualshock") || n.contains("playstation")
                || n.contains("ps4") || n.contains("ps5") || n.equals("wireless controller")) return "ps";
        if (v == 0x057E || n.contains("pro controller") || n.contains("joy-con") || n.contains("nintendo")) return "nintendo";
        if (v == 0x045E || n.contains("xbox") || n.contains("x-box")) return "xbox";
        return "generic";
    }

    static boolean isPad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** The family of the first connected controller, or null when none is connected. */
    static String connectedFamily() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (isRealPad(d)) return family(d);
        }
        return null;
    }

    /** A controller with face buttons: some phones list sensors or keys as joysticks, which shouldn't count. */
    static boolean isRealPad(InputDevice d) {
        if (!isPad(d)) return false;
        if ((d.getSources() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return false;
        boolean[] has = d.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B);
        return has[0] && has[1];
    }

    /** Remembers the controller in use and tells the page when it changes. Returns the JS to run, or null. */
    static String noteDevice(android.content.SharedPreferences prefs, InputDevice d, int[] lastId) {
        if (!isPad(d)) return null;
        if (d.getId() == lastId[0]) return null;
        lastId[0] = d.getId();
        String fam = family(d);
        prefs.edit().putString("padFamily", fam).apply();
        return "window.onPadKind&&window.onPadKind('" + fam + "')";
    }
}
