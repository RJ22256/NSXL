package com.nsxl.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds the list of launchable apps for the page, flagging which ones are games. */
final class AppList {

    /** Emulators, front-ends and streaming apps that don't mark themselves as games. */
    private static final Set<String> KNOWN_GAME_APPS = new HashSet<>(Arrays.asList(
            "com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32",
            "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold",
            "org.dolphinemu.dolphinemu", "com.github.stenzek.duckstation",
            "com.swordfish.lemuroid", "com.dsemu.drastic", "com.magneticchen.daijishou",
            "org.es_de.frontend", "com.valvesoftware.steamlink", "com.limelight",
            "com.nvidia.geforcenow", "com.gamepass", "com.microsoft.xboxone.smartglass",
            "com.playstation.remoteplay"
    ));

    private AppList() { }

    static String json(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            long installed = 0;
            try {
                installed = pm.getPackageInfo(pkg, 0).firstInstallTime;
            } catch (Exception ignored) { }
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", pkg);
                o.put("label", String.valueOf(ri.loadLabel(pm)));
                o.put("game", isGame(pkg, ri.activityInfo.applicationInfo));
                o.put("installed", installed);
                o.put("cover", Storage.coverFile(c, pkg).exists() || Storage.coverVideoFile(c, pkg).exists());
                o.put("coverVideo", Storage.coverVideoFile(c, pkg).exists());
            } catch (JSONException ignored) { }
            arr.put(o);
        }
        return arr.toString();
    }

    @SuppressWarnings("deprecation")
    private static boolean isGame(String pkg, ApplicationInfo ai) {
        if (KNOWN_GAME_APPS.contains(pkg)) return true;
        if (ai == null) return false;
        if (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) return true;
        return (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }
}
