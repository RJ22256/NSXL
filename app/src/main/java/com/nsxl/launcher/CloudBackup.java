package com.nsxl.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A backup is a zip with:
 *   nsxl.json       settings the page handed us (API keys, theme, music settings) + who made it
 *   music.bin/.mime your music file, if you picked one
 *   wallpaper.mp4   your live wallpaper video, only when asked for (it can be big)
 */
final class CloudBackup {

    private CloudBackup() { }

    static String deviceName() {
        String m = Build.MODEL == null ? "Android" : Build.MODEL;
        return m.replaceAll("[^A-Za-z0-9 _-]", "").trim();
    }

    static File create(Context c, boolean includeVideo) throws IOException {
        SharedPreferences prefs = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        File out = new File(c.getCacheDir(), "nsxl-backup.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            JSONObject meta = new JSONObject();
            try {
                meta.put("app", "NSXL");
                meta.put("format", 1);
                meta.put("created", System.currentTimeMillis());
                meta.put("device", deviceName());
                meta.put("settings", new JSONObject(prefs.getString("cloudSettings", "{}")));
                meta.put("hasVideo", includeVideo && Storage.videoFile(c).exists());
            } catch (Exception ignored) { }
            put(zip, "nsxl.json", meta.toString().getBytes(StandardCharsets.UTF_8));
            File music = Storage.musicFile(c);
            if (music.exists()) {
                putFile(zip, "music.bin", music);
                put(zip, "music.mime", prefs.getString("musicMime", "audio/mpeg").getBytes(StandardCharsets.UTF_8));
            }
            File video = Storage.videoFile(c);
            if (includeVideo && video.exists()) putFile(zip, "wallpaper.mp4", video);
        }
        return out;
    }

    /** Unpacks a backup onto this device. Returns the nsxl.json text so the page can apply the settings. */
    static String restore(Context c, File zipFile) throws IOException {
        SharedPreferences prefs = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        String json = null;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if ("nsxl.json".equals(name)) json = new String(readAll(zip), StandardCharsets.UTF_8);
                else if ("music.mime".equals(name)) prefs.edit().putString("musicMime", new String(readAll(zip), StandardCharsets.UTF_8)).apply();
                else if ("music.bin".equals(name)) writeAtomically(zip, Storage.musicFile(c));
                else if ("wallpaper.mp4".equals(name)) writeAtomically(zip, Storage.videoFile(c));
            }
        }
        if (json == null) throw new IOException("That isn't an NSXL backup");
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception ex) { throw new IOException("That backup is damaged"); }
        if (!"NSXL".equals(o.optString("app"))) throw new IOException("That isn't an NSXL backup");
        prefs.edit().putString("pendingRestore", json).apply();
        return json;
    }

    // ------------------------------------------------------------------

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) { copy(in, zip); }
        zip.closeEntry();
    }

    private static void writeAtomically(InputStream in, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".restore");
        try (OutputStream os = new FileOutputStream(tmp)) { copy(in, os); }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("Couldn't save " + dest.getName());
    }

    static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        return total;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }
}
