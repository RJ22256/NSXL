package com.nsxl.launcher;

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
}
