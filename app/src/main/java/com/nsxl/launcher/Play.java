package com.nsxl.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Play time: counts how long each game stays open after NSXL starts it, leaving out time with the
 * screen off. Kept in prefs as {"pkg": {"ms": total, "n": sessions, "first": time, "last": time}}.
 */
final class Play {

    private Play() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    static void start(Context c, String pkg) {
        prefs(c).edit().putString("sessPkg", pkg).putLong("sessStart", SystemClock.elapsedRealtime())
                .putLong("sessOff", 0).putLong("sessOffAt", 0).apply();
    }

    static void screenOff(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getString("sessPkg", null) != null && p.getLong("sessOffAt", 0) == 0)
            p.edit().putLong("sessOffAt", SystemClock.elapsedRealtime()).apply();
    }

    static void screenOn(Context c) {
        SharedPreferences p = prefs(c);
        long at = p.getLong("sessOffAt", 0);
        if (at > 0) p.edit().putLong("sessOff", p.getLong("sessOff", 0) + SystemClock.elapsedRealtime() - at).putLong("sessOffAt", 0).apply();
    }

    /** Called when NSXL comes back to the front: closes the open session, if any. */
    static void finish(Context c) {
        SharedPreferences p = prefs(c);
        String pkg = p.getString("sessPkg", null);
        if (pkg == null) return;
        long now = SystemClock.elapsedRealtime(), start = p.getLong("sessStart", now), offAt = p.getLong("sessOffAt", 0);
        long end = offAt > 0 ? offAt : now;
        long ms = end - start - p.getLong("sessOff", 0);
        p.edit().remove("sessPkg").remove("sessStart").remove("sessOff").remove("sessOffAt").apply();
        if (start > now || ms < 5000 || ms > 12L * 3600 * 1000) return;   // reboot, a mis-tap, or the phone left on all night
        add(c, pkg, ms);
    }

    static synchronized void add(Context c, String pkg, long ms) {
        try {
            JSONObject all = new JSONObject(prefs(c).getString("playtime", "{}"));
            JSONObject o = all.optJSONObject(pkg);
            if (o == null) { o = new JSONObject(); o.put("first", System.currentTimeMillis() - ms); }
            o.put("ms", o.optLong("ms") + ms);
            o.put("n", o.optInt("n") + 1);
            o.put("last", System.currentTimeMillis());
            all.put(pkg, o);
            prefs(c).edit().putString("playtime", all.toString()).apply();
        } catch (Exception ignored) { }
    }

    static String stats(Context c) {
        return prefs(c).getString("playtime", "{}");
    }

    static void reset(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) { prefs(c).edit().remove("playtime").apply(); return; }
        try {
            JSONObject all = new JSONObject(prefs(c).getString("playtime", "{}"));
            all.remove(pkg);
            prefs(c).edit().putString("playtime", all.toString()).apply();
        } catch (Exception ignored) { }
    }
}
