package com.nsxl.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Minimal SteamGridDB client (https://www.steamgriddb.com/api/v2).
 * Needs the user's own free API key. All calls block, so run them off the UI thread.
 */
final class SteamGridDB {

    private static final String API = "https://www.steamgriddb.com/api/v2";

    private SteamGridDB() { }

    /** Returns a JSON array literal: [{id, name, year}] */
    static String search(String key, String term) throws Exception {
        String q = URLEncoder.encode(term.trim(), "UTF-8").replace("+", "%20");
        JSONArray data = data(get(API + "/search/autocomplete/" + q, key));
        JSONArray out = new JSONArray();
        for (int i = 0; i < data.length(); i++) {
            JSONObject g = data.getJSONObject(i);
            JSONObject o = new JSONObject();
            o.put("id", g.optLong("id"));
            o.put("name", g.optString("name"));
            long released = g.optLong("release_date", 0);
            if (released > 0) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTimeInMillis(released * 1000L);
                o.put("year", cal.get(java.util.Calendar.YEAR));
            }
            out.put(o);
        }
        return out.toString();
    }

    /** Square covers first; falls back to any static grid (it gets centre-cropped). JSON array literal. */
    static String grids(String key, long gameId) throws Exception {
        JSONArray data = data(get(API + "/grids/game/" + gameId + "?dimensions=512x512,1024x1024&types=static", key));
        if (data.length() == 0) data = data(get(API + "/grids/game/" + gameId + "?types=static", key));
        JSONArray out = new JSONArray();
        for (int i = 0; i < data.length() && out.length() < 40; i++) {
            JSONObject g = data.getJSONObject(i);
            String url = g.optString("url", "");
            if (url.isEmpty()) continue;
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("thumb", g.optString("thumb", url));
            o.put("width", g.optInt("width"));
            o.put("height", g.optInt("height"));
            o.put("style", g.optString("style", ""));
            out.put(o);
        }
        return out.toString();
    }

    static void download(String url, File dest) throws IOException {
        HttpURLConnection c = open(url, null);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Download failed (" + code + ")");
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------

    private static JSONArray data(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        if (!o.optBoolean("success", false)) {
            JSONArray errs = o.optJSONArray("errors");
            throw new IOException(errs != null && errs.length() > 0 ? errs.optString(0) : "SteamGridDB request failed");
        }
        JSONArray d = o.optJSONArray("data");
        return d == null ? new JSONArray() : d;
    }

    private static String get(String url, String key) throws IOException {
        if (key == null || key.trim().isEmpty()) throw new IOException("No SteamGridDB API key set");
        HttpURLConnection c = open(url, key.trim());
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("SteamGridDB didn't accept the API key (" + code + ").");
            if (code == 404) return "{\"success\":true,\"data\":[]}";
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : read(in);
            if (code >= 400) throw new IOException("SteamGridDB error " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "NSXL-Launcher/1.0");
        if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
