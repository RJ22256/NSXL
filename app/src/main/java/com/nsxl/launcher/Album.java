package com.nsxl.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The Album: reads the device's screenshots from MediaStore so NSXL can show them in its own gallery.
 * Nothing is copied; images are streamed from MediaStore when the page asks for them.
 */
final class Album {

    private Album() { }

    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 34) return new String[]{Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"};
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /** "full", "partial" (Android 14 "selected photos only") or "none". */
    static String access(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) return "full";
            if (Build.VERSION.SDK_INT >= 34 && c.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED) return "partial";
            return "none";
        }
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ? "full" : "none";
    }

    static Uri uri(long id) {
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    /** Newest first. Each entry: id, name, date (ms), w, h, tag (the game NSXL had open when the quick menu took it, if known). */
    @SuppressWarnings("deprecation")
    static String list(Context c) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String pathCol = Build.VERSION.SDK_INT >= 29 ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA;
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT};
        String where = pathCol + " LIKE ?";
        String[] args = {"%Screenshot%"};
        JSONArray tags = tags(c);
        JSONArray out = new JSONArray();
        try (Cursor q = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, where, args, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (q == null) return "[]";
            while (q.moveToNext() && out.length() < 600) {
                long taken = q.getLong(2);
                long date = taken > 0 ? taken : q.getLong(3) * 1000L;
                JSONObject o = new JSONObject();
                o.put("id", q.getLong(0));
                o.put("name", q.getString(1) == null ? "" : q.getString(1));
                o.put("date", date);
                o.put("w", q.getInt(4));
                o.put("h", q.getInt(5));
                String tag = tagFor(tags, date);
                if (tag != null) o.put("tag", tag);
                out.put(o);
            }
        }
        return out.toString();
    }

    /** A JPEG thumbnail for the grid. */
    @SuppressWarnings("deprecation")
    static byte[] thumb(Context c, long id, int width) throws Exception {
        Bitmap b;
        if (Build.VERSION.SDK_INT >= 29) {
            b = c.getContentResolver().loadThumbnail(uri(id), new Size(width, width * 9 / 16), null);
        } else {
            b = MediaStore.Images.Thumbnails.getThumbnail(c.getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
        }
        if (b == null) throw new Exception("No thumbnail");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 82, bos);
        return bos.toByteArray();
    }

    /** The full image, scaled down to fit the screen so huge captures don't stall the viewer. */
    static byte[] full(Context c, long id, int maxSide) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri(id))) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri(id))) { b = BitmapFactory.decodeStream(in, null, d); }
        if (b == null) throw new Exception("Can't read image");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 92, bos);
        return bos.toByteArray();
    }

    static void share(Activity a, long id) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("image/*")
                .putExtra(Intent.EXTRA_STREAM, uri(id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, "Share screenshot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static void open(Activity a, long id) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri(id), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    // ---------------------------------------------------------------- tags: which game was open when the quick menu took a screenshot

    static void tag(Context c) {
        SharedPreferences p = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        String pkg = p.getString("lastLaunched", "");
        if (pkg.isEmpty()) return;
        try {
            JSONArray t = tags(c), keep = new JSONArray();
            for (int i = Math.max(0, t.length() - 299); i < t.length(); i++) keep.put(t.get(i));
            keep.put(new JSONObject().put("t", System.currentTimeMillis()).put("pkg", pkg));
            p.edit().putString("shotTags", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static JSONArray tags(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("shotTags", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static String tagFor(JSONArray tags, long date) {
        for (int i = tags.length() - 1; i >= 0; i--) {
            JSONObject o = tags.optJSONObject(i);
            if (o == null) continue;
            long d = date - o.optLong("t");
            if (d >= -2000 && d < 15000) return o.optString("pkg", null);
        }
        return null;
    }
}
