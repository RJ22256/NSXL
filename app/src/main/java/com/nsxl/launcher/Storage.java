package com.nsxl.launcher;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Where the launcher keeps your wallpaper video, cover art and profile picture. */
final class Storage {

    private Storage() { }

    static File videoFile(Context c) {
        return new File(c.getFilesDir(), "wallpaper.mp4");
    }

    static File avatarFile(Context c) {
        return new File(c.getFilesDir(), "avatar.jpg");
    }

    static File coverFile(Context c, String pkg) {
        File dir = new File(c.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String safe = (pkg == null ? "_" : pkg).replaceAll("[^A-Za-z0-9._]", "_");
        return new File(dir, safe + ".jpg");
    }

    static long querySize(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }

    /** Decodes a picked image, fixes its rotation, crops the centre square and saves it as a JPEG. */
    static boolean saveSquareImage(Context c, Uri uri, File out, int size) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int sample = 1;
            int shortSide = Math.min(bounds.outWidth, bounds.outHeight);
            while (shortSide / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap src;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in, null, opts);
            }
            if (src == null) return false;

            int rotation = 0;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                    else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                    else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                }
            } catch (Exception ignored) { }

            int w = src.getWidth(), h = src.getHeight(), s = Math.min(w, h);
            Matrix m = new Matrix();
            float scale = Math.min(1f, size / (float) s);
            m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            Bitmap square = Bitmap.createBitmap(src, (w - s) / 2, (h - s) / 2, s, s, m, true);

            // Flatten transparency onto white so PNGs don't turn black as JPEGs.
            Bitmap flat = Bitmap.createBitmap(square.getWidth(), square.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(flat);
            cv.drawColor(Color.WHITE);
            cv.drawBitmap(square, 0, 0, null);

            File tmp = new File(out.getPath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                flat.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return tmp.renameTo(out);
        } catch (Exception e) {
            return false;
        }
    }
}
