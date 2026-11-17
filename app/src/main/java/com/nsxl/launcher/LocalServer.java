package com.nsxl.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the page and its images from the fake origin https://launcher.local/ :
 *   /index.html ...     files from assets/www
 *   /art/PKG?s=512      square tile art (your custom cover, or one generated from the app icon)
 *   /icon/PKG?s=192     the app's normal icon
 *   /avatar             your profile picture
 */
final class LocalServer {

    private final Context ctx;
    private final Object drawLock = new Object();
    private final LruCache<String, byte[]> cache = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    LocalServer(Context c) {
        ctx = c.getApplicationContext();
    }

    WebResourceResponse handle(Uri uri) {
        if (uri == null || !"launcher.local".equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
        try {
            if (path.startsWith("/art/")) {
                return image(art(path.substring(5), size(uri, 512)), "image/jpeg");
            }
            if (path.startsWith("/icon/")) {
                return image(icon(path.substring(6), size(uri, 192)), "image/png");
            }
            if (path.equals("/music")) {
                File f = Storage.musicFile(ctx);
                if (!f.exists()) return notFound();
                String type = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("musicMime", "audio/mpeg");
                return new WebResourceResponse(type, null, 200, "OK", headers(), new java.io.FileInputStream(f));
            }
            if (path.equals("/avatar")) {
                File f = Storage.avatarFile(ctx);
                return f.exists() ? image(readAll(new FileInputStream(f)), "image/jpeg") : notFound();
            }
            if (path.contains("..")) return notFound();
            InputStream in = ctx.getAssets().open("www" + path);
            return new WebResourceResponse(mime(path), "utf-8", 200, "OK", headers(), in);
        } catch (Exception e) {
            return notFound();
        }
    }

    void invalidate(String pkg) {
        if (pkg == null) return;
        for (String k : cache.snapshot().keySet()) {
            if (k.startsWith(pkg + "|")) cache.remove(k);
        }
    }

    void clear() {
        cache.evictAll();
    }

    // ---------------------------------------------------------------- images

    private byte[] art(String pkg, int size) throws Exception {
        File cover = Storage.coverFile(ctx, pkg);
        if (cover.exists()) return readAll(new FileInputStream(cover));

        String key = pkg + "|art|" + size;
        byte[] cached = cache.get(key);
        if (cached != null) return cached;

        byte[] out;
        synchronized (drawLock) {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 26 && d instanceof AdaptiveIconDrawable) {
                // Draw the icon's full-bleed layers without the round mask, so it fills the square tile.
                AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
                int pad = Math.round(size * 0.12f);
                Drawable bg = ad.getBackground(), fg = ad.getForeground();
                if (bg != null) {
                    bg.setBounds(-pad, -pad, size + pad, size + pad);
                    bg.draw(c);
                }
                if (fg != null) {
                    fg.setBounds(-pad, -pad, size + pad, size + pad);
                    fg.draw(c);
                }
            } else {
                // Older icons: a soft colour wash taken from the icon, with the icon in the middle.
                Paint wash = new Paint(Paint.FILTER_BITMAP_FLAG);
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(1.35f);
                wash.setColorFilter(new ColorMatrixColorFilter(cm));
                c.drawBitmap(softBackground(render(d, 64), size), 0, 0, wash);
                int s = Math.round(size * 0.58f), o = (size - s) / 2;
                c.drawBitmap(render(d, s), o, o, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos);
            bmp.recycle();
            out = bos.toByteArray();
        }
        cache.put(key, out);
        return out;
    }

    private byte[] icon(String pkg, int size) throws Exception {
        String key = pkg + "|icon|" + size;
        byte[] cached = cache.get(key);
        if (cached != null) return cached;
        byte[] out;
        synchronized (drawLock) {
            Bitmap bmp = render(ctx.getPackageManager().getApplicationIcon(pkg), size);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            bmp.recycle();
            out = bos.toByteArray();
        }
        cache.put(key, out);
        return out;
    }

    private static Bitmap render(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, s, s);
        d.draw(c);
        d.setBounds(old);
        return b;
    }

    /** Shrinks the icon to a few pixels then scales it back up: a cheap, smooth blur. */
    private static Bitmap softBackground(Bitmap src, int size) {
        Bitmap b = src;
        while (b.getWidth() > 4) {
            b = Bitmap.createScaledBitmap(b, Math.max(4, b.getWidth() / 2), Math.max(4, b.getHeight() / 2), true);
        }
        return Bitmap.createScaledBitmap(b, size, size, true);
    }

    // ---------------------------------------------------------------- http helpers

    private static int size(Uri uri, int def) {
        try {
            String s = uri.getQueryParameter("s");
            int v = s == null ? def : Integer.parseInt(s);
            return Math.max(48, Math.min(1024, v));
        } catch (Exception e) {
            return def;
        }
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        h.put("Access-Control-Allow-Origin", "*");
        return h;
    }

    private static WebResourceResponse image(byte[] data, String mime) {
        return new WebResourceResponse(mime, null, 200, "OK", headers(), new ByteArrayInputStream(data));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
