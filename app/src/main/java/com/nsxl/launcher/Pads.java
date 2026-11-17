package com.nsxl.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Controllers page: lists connected and paired controllers, finds new ones over Bluetooth and pairs them,
 * all without leaving NSXL. Changes are pushed to the page as window.onPads(state).
 */
@SuppressLint("MissingPermission")   // every Bluetooth call is behind hasPermission()
final class Pads {

    private static final Pattern PAD_NAME = Pattern.compile(
            "(?i).*(controller|gamepad|game ?pad|joy-?con|dualsense|dualshock|xbox|8bitdo|gamesir|razer kishi|backbone|stadia|nimbus|moga|ipega|flydigi|shield|pro con|ps[345]|joystick).*");

    private final MainActivity a;
    private final Map<String, JSONObject> found = new LinkedHashMap<>();
    private BroadcastReceiver receiver;
    private android.hardware.input.InputManager.InputDeviceListener inputListener;
    private boolean scanning;
    private String pairing;

    Pads(MainActivity a) {
        this.a = a;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean hasPermission() {
        for (String p : permissions()) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    void requestPermission() {
        a.requestPermissions(permissions(), MainActivity.REQ_PADS);
    }

    void enableBluetooth() {
        try {
            a.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Exception e) {
            a.jsToast("Couldn't turn Bluetooth on");
        }
    }

    // ---------------------------------------------------------------- state

    String state() {
        JSONObject o = new JSONObject();
        try {
            BluetoothAdapter ad = adapter();
            boolean perm = hasPermission();
            o.put("supported", ad != null);
            o.put("permission", perm);
            o.put("enabled", ad != null && perm && ad.isEnabled());
            o.put("scanning", scanning);
            o.put("pairing", pairing == null ? JSONObject.NULL : pairing);

            JSONArray conn = new JSONArray();
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                int s = d.getSources();
                boolean pad = (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                if (!pad) continue;
                if (Build.VERSION.SDK_INT >= 29 && !d.isExternal()) continue;
                JSONObject c = new JSONObject();
                c.put("id", id);
                c.put("name", d.getName());
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        android.hardware.BatteryState b = d.getBatteryState();
                        if (b.isPresent() && !Float.isNaN(b.getCapacity())) c.put("battery", Math.round(b.getCapacity() * 100));
                    } catch (Exception ignored) { }
                }
                c.put("rumble", canRumble(d));
                conn.put(c);
            }
            o.put("connected", conn);

            JSONArray paired = new JSONArray();
            if (ad != null && perm && ad.isEnabled()) {
                for (BluetoothDevice d : ad.getBondedDevices()) paired.put(describe(d));
            }
            o.put("paired", paired);

            JSONArray f = new JSONArray();
            for (JSONObject x : found.values()) f.put(x);
            o.put("found", f);
        } catch (Exception ignored) { }
        return o.toString();
    }

    private JSONObject describe(BluetoothDevice d) throws Exception {
        JSONObject o = new JSONObject();
        String name = d.getName();
        o.put("addr", d.getAddress());
        o.put("name", name == null ? d.getAddress() : name);
        o.put("pad", isPad(d, name));
        o.put("connected", isConnected(d));
        o.put("bonded", d.getBondState() == BluetoothDevice.BOND_BONDED);
        return o;
    }

    private static boolean isPad(BluetoothDevice d, String name) {
        BluetoothClass c = d.getBluetoothClass();
        if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL) {
            int minor = c.getDeviceClass() & 0x3C;   // peripheral sub-type bits: 0x04 joystick, 0x08 gamepad
            if (minor == 0x04 || minor == 0x08) return true;
        }
        return name != null && PAD_NAME.matcher(name).matches();
    }

    private static boolean isConnected(BluetoothDevice d) {
        try {
            return (Boolean) d.getClass().getMethod("isConnected").invoke(d);
        } catch (Exception e) {
            return false;
        }
    }

    private void push() {
        a.js("window.onPads&&window.onPads(" + state() + ")");
    }

    // ---------------------------------------------------------------- scanning + pairing

    void startScan() {
        BluetoothAdapter ad = adapter();
        if (ad == null || !hasPermission() || !ad.isEnabled()) { push(); return; }
        listen();
        found.clear();
        if (ad.isDiscovering()) ad.cancelDiscovery();
        scanning = ad.startDiscovery();
        if (!scanning) a.jsToast(Build.VERSION.SDK_INT < 31 ? "Couldn't search. Turn on Location and try again." : "Couldn't start searching");
        push();
    }

    void stopScan() {
        BluetoothAdapter ad = adapter();
        try { if (ad != null && hasPermission() && ad.isDiscovering()) ad.cancelDiscovery(); } catch (Exception ignored) { }
        scanning = false;
    }

    void pair(String addr) {
        BluetoothAdapter ad = adapter();
        if (ad == null || !hasPermission()) return;
        listen();
        try {
            if (ad.isDiscovering()) ad.cancelDiscovery();   // discovery makes pairing slow and flaky
            scanning = false;
            BluetoothDevice d = ad.getRemoteDevice(addr);
            pairing = addr;
            if (d.getBondState() == BluetoothDevice.BOND_BONDED || !d.createBond()) {
                pairing = null;
                if (d.getBondState() != BluetoothDevice.BOND_BONDED) a.jsToast("Couldn't pair. Put the controller in pairing mode and try again.");
            }
        } catch (Exception e) {
            pairing = null;
            a.jsToast("Couldn't pair with that device");
        }
        push();
    }

    void forget(String addr) {
        BluetoothAdapter ad = adapter();
        if (ad == null || !hasPermission()) return;
        try {
            BluetoothDevice d = ad.getRemoteDevice(addr);
            d.getClass().getMethod("removeBond").invoke(d);
        } catch (Exception e) {
            a.jsToast("Android didn't allow that. Forget it in Bluetooth settings instead.");
            a.runOnUiThread(() -> {
                try { a.startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            });
        }
        push();
    }

    /** Buzzes a connected controller so you can tell which one is which. */
    void rumble(int deviceId) {
        InputDevice d = InputDevice.getDevice(deviceId);
        if (d == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = d.getVibratorManager();
                if (vm.getVibratorIds().length > 0) {
                    vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)));
                    return;
                }
            }
            Vibrator v = d.getVibrator();
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    private static boolean canRumble(InputDevice d) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && d.getVibratorManager().getVibratorIds().length > 0) return true;
            Vibrator v = d.getVibrator();
            return v != null && v.hasVibrator();
        } catch (Exception e) {
            return false;
        }
    }

    private void listen() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String act = i.getAction();
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                try {
                    if (BluetoothDevice.ACTION_FOUND.equals(act) && d != null) {
                        if (d.getBondState() != BluetoothDevice.BOND_BONDED) {
                            String name = d.getName();
                            if (name == null) name = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                            if (name != null && !name.trim().isEmpty()) {
                                JSONObject o = describe(d);
                                o.put("name", name);
                                o.put("pad", isPad(d, name));
                                found.put(d.getAddress(), o);
                            }
                        }
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(act)) {
                        scanning = false;
                    } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(act) && d != null) {
                        int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                        if (d.getAddress().equals(pairing) && st != BluetoothDevice.BOND_BONDING) {
                            pairing = null;
                            String name = d.getName() == null ? "Controller" : d.getName();
                            a.jsToast(st == BluetoothDevice.BOND_BONDED ? name + " paired" : "Pairing didn't finish. Try again.");
                        }
                        if (st == BluetoothDevice.BOND_BONDED) found.remove(d.getAddress());
                    } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(act)) {
                        if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) != BluetoothAdapter.STATE_ON) { scanning = false; found.clear(); }
                    }
                } catch (Exception ignored) { }
                push();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else a.registerReceiver(receiver, f);
        // A paired controller shows up as an input device a moment after it connects.
        android.hardware.input.InputManager im = (android.hardware.input.InputManager) a.getSystemService(Context.INPUT_SERVICE);
        if (im != null) {
            inputListener = new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) { push(); }
                @Override public void onInputDeviceRemoved(int id) { push(); }
                @Override public void onInputDeviceChanged(int id) { }
            };
            im.registerInputDeviceListener(inputListener, null);
        }
    }

    void release() {
        stopScan();
        if (receiver != null) {
            try { a.unregisterReceiver(receiver); } catch (Exception ignored) { }
            receiver = null;
        }
        if (inputListener != null) {
            android.hardware.input.InputManager im = (android.hardware.input.InputManager) a.getSystemService(Context.INPUT_SERVICE);
            if (im != null) im.unregisterInputDeviceListener(inputListener);
            inputListener = null;
        }
    }
}
