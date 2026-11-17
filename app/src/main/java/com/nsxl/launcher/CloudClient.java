package com.nsxl.launcher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Talks to another device running NSXL Cloud. All calls block; run them off the UI thread. */
final class CloudClient {

    private CloudClient() { }

    static String base(String address) {
        String a = address == null ? "" : address.trim();
        a = a.replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (a.isEmpty()) throw new IllegalArgumentException("Enter the other device's address");
        if (!a.contains(":")) a = a + ":" + CloudServer.PORT;
        return "http://" + a;
    }

    /** Sends this device's backup to the other device's cloud. */
    static void push(Context c, String address, String pin, boolean video) throws IOException {
        File zip = CloudBackup.create(c, video);
        HttpURLConnection h = open(base(address) + "/api/backups?device=" + URLEncoder.encode(CloudBackup.deviceName(), "UTF-8"), pin);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/zip");
        h.setFixedLengthStreamingMode(zip.length());
        try {
            try (OutputStream os = h.getOutputStream(); InputStream in = new FileInputStream(zip)) {
                CloudBackup.copy(in, os);
            }
            check(h);
        } catch (IOException e) {
            throw friendly(e);
        } finally {
            h.disconnect();
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
        }
    }

    /** JSON array of backups stored on the other device. */
    static String list(String address, String pin) throws IOException {
        HttpURLConnection h = open(base(address) + "/api/backups", pin);
        try {
            check(h);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                CloudBackup.copy(in, b);
                return b.toString("UTF-8");
            }
        } catch (IOException e) {
            throw friendly(e);
        } finally {
            h.disconnect();
        }
    }

    /** Downloads a stored backup (or, with name == null, the other device's own current setup) and restores it here. */
    static String pull(Context c, String address, String pin, String name) throws IOException {
        String path = name == null ? "/api/backup" : "/api/backups/" + URLEncoder.encode(name, "UTF-8");
        HttpURLConnection h = open(base(address) + path, pin);
        File tmp = new File(c.getCacheDir(), "download.zip");
        try {
            check(h);
            try (InputStream in = h.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                CloudBackup.copy(in, os);
            }
            return CloudBackup.restore(c, tmp);
        } catch (IOException e) {
            throw friendly(e);
        } finally {
            h.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------

    private static HttpURLConnection open(String url, String pin) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(60000);
        h.setRequestProperty("X-NSXL-PIN", pin == null ? "" : pin.trim());
        return h;
    }

    private static void check(HttpURLConnection h) throws IOException {
        int code = h.getResponseCode();
        if (code == 401) throw new IOException("Wrong PIN");
        if (code == 413) throw new IOException("That backup is too big");
        if (code >= 400) throw new IOException("The other device said no (" + code + ")");
    }

    private static IOException friendly(IOException e) {
        if (e instanceof ConnectException || e instanceof SocketTimeoutException || e instanceof java.net.UnknownHostException
                || e instanceof java.net.NoRouteToHostException) {
            return new IOException("Couldn't reach that device. Check NSXL Cloud is on there and you're on the same Wi-Fi.");
        }
        return e;
    }

    static String bytesToString(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
