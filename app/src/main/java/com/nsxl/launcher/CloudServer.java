package com.nsxl.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NSXL Cloud: a tiny HTTP server on this device (port 8765) that other NSXL devices and any browser
 * on the same Wi-Fi can back up to and restore from. Everything except the info call needs the PIN.
 *
 *   GET  /                      browser page (assets/www/cloud.html)
 *   GET  /api/info              device name (no PIN)
 *   GET  /api/backup            a fresh backup of THIS device
 *   GET  /api/backups           backups stored here
 *   GET  /api/backups/NAME      download a stored backup
 *   POST /api/backups?device=X  store a backup sent from another device
 *   DELETE /api/backups/NAME    delete a stored backup
 *   POST /api/restore           restore a backup onto THIS device
 */
final class CloudServer {

    static final int PORT = 8765;
    private static final int KEEP = 12;
    private static final long MAX_UPLOAD = 1536L * 1024 * 1024;
    static CloudServer instance;

    final String pin;
    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket server;
    private volatile boolean running;

    private CloudServer(Context c) {
        ctx = c.getApplicationContext();
        pin = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    static synchronized CloudServer start(Context c) throws IOException {
        if (instance != null) return instance;
        CloudServer s = new CloudServer(c);
        s.server = new ServerSocket();
        s.server.setReuseAddress(true);
        s.server.bind(new InetSocketAddress(PORT));
        s.running = true;
        Thread t = new Thread(s::loop, "nsxl-cloud");
        t.setDaemon(true);
        t.start();
        instance = s;
        return s;
    }

    static synchronized void stop() {
        if (instance == null) return;
        instance.running = false;
        try { instance.server.close(); } catch (Exception ignored) { }
        instance.pool.shutdownNow();
        instance = null;
    }

    static File storeDir(Context c) {
        File d = new File(c.getFilesDir(), "cloud");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** This device's Wi-Fi/LAN IPv4 address, or null. */
    static String localAddress(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                    return la.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static JSONArray listStored(Context c) {
        JSONArray arr = new JSONArray();
        File[] files = storeDir(c).listFiles((d, n) -> n.endsWith(".zip"));
        if (files == null) return arr;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("time", f.lastModified());
                String n = f.getName().replaceFirst("^\\d+-", "").replaceFirst("\\.zip$", "");
                o.put("device", n.replace('_', ' '));
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr;
    }

    static String safeName(String raw) {
        String n = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "");
        if (n.contains("..") || !n.endsWith(".zip")) return null;
        return n;
    }

    // ------------------------------------------------------------------ server loop

    private void loop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(60_000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String request = readLine(in);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> params = parseQuery(query);
            long length = 0;
            try { length = Long.parseLong(headers.getOrDefault("content-length", "0")); } catch (Exception ignored) { }

            if ("OPTIONS".equals(method)) { send(out, 204, "text/plain", new byte[0], null); return; }

            // Public bits: the page, its font, and the device name.
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendAsset(out, "www/cloud.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && "/fonts/Montserrat.ttf".equals(path)) {
                sendAsset(out, "www/fonts/Montserrat.ttf", "font/ttf");
                return;
            }
            if ("GET".equals(method) && "/api/info".equals(path)) {
                JSONObject o = new JSONObject();
                o.put("app", "NSXL");
                o.put("device", CloudBackup.deviceName());
                sendJson(out, 200, o.toString());
                return;
            }

            String given = headers.containsKey("x-nsxl-pin") ? headers.get("x-nsxl-pin") : params.get("pin");
            if (!pin.equals(given)) {
                sendJson(out, 401, "{\"error\":\"Wrong PIN\"}");
                return;
            }

            if ("GET".equals(method) && "/api/backup".equals(path)) {
                File zip = CloudBackup.create(ctx, "1".equals(params.get("video")));
                sendFile(out, zip, "nsxl-" + CloudBackup.deviceName().replace(' ', '_') + ".zip");
            } else if ("GET".equals(method) && "/api/backups".equals(path)) {
                sendJson(out, 200, listStored(ctx).toString());
            } else if ("GET".equals(method) && path.startsWith("/api/backups/")) {
                String name = safeName(path.substring("/api/backups/".length()));
                File f = name == null ? null : new File(storeDir(ctx), name);
                if (f == null || !f.exists()) sendJson(out, 404, "{\"error\":\"Not found\"}");
                else sendFile(out, f, name);
            } else if ("DELETE".equals(method) && path.startsWith("/api/backups/")) {
                String name = safeName(path.substring("/api/backups/".length()));
                boolean ok = name != null && new File(storeDir(ctx), name).delete();
                sendJson(out, ok ? 200 : 404, ok ? "{\"ok\":true}" : "{\"error\":\"Not found\"}");
            } else if ("POST".equals(method) && "/api/backups".equals(path)) {
                if (length <= 0 || length > MAX_UPLOAD) { sendJson(out, 413, "{\"error\":\"Backup is empty or too big\"}"); return; }
                String device = params.getOrDefault("device", "Unknown").replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '_');
                if (device.isEmpty()) device = "Unknown";
                File f = new File(storeDir(ctx), System.currentTimeMillis() + "-" + device + ".zip");
                receive(in, length, f);
                prune();
                sendJson(out, 200, "{\"ok\":true,\"name\":" + JSONObject.quote(f.getName()) + "}");
            } else if ("POST".equals(method) && "/api/restore".equals(path)) {
                if (length <= 0 || length > MAX_UPLOAD) { sendJson(out, 413, "{\"error\":\"Backup is empty or too big\"}"); return; }
                File f = new File(ctx.getCacheDir(), "incoming.zip");
                receive(in, length, f);
                try {
                    String json = CloudBackup.restore(ctx, f);
                    MainActivity.notifyRestored(json);
                    sendJson(out, 200, "{\"ok\":true}");
                } catch (IOException e) {
                    sendJson(out, 400, "{\"error\":" + JSONObject.quote(e.getMessage()) + "}");
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } else {
                sendJson(out, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception ignored) {
        }
    }

    private void prune() {
        File[] files = storeDir(ctx).listFiles((d, n) -> n.endsWith(".zip"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < files.length; i++) //noinspection ResultOfMethodCallIgnored
            files[i].delete();
    }

    // ------------------------------------------------------------------ tiny HTTP helpers

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static void receive(InputStream in, long length, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".part");
        try (OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[128 * 1024];
            long left = length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("Upload cut short");
                os.write(buf, 0, n);
                left -= n;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("Couldn't save upload");
    }

    private static void writeHead(OutputStream out, int code, String type, long len, String filename) throws IOException {
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : code == 413 ? "Too Large" : "Bad Request";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(len).append("\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: X-NSXL-PIN, Content-Type\r\n")
                .append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n");
        if (filename != null) h.append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String filename) throws IOException {
        writeHead(out, code, type, body.length, filename);
        out.write(body);
        out.flush();
    }

    private static void sendJson(OutputStream out, int code, String json) throws IOException {
        send(out, code, "application/json", json.getBytes(StandardCharsets.UTF_8), null);
    }

    private static void sendFile(OutputStream out, File f, String name) throws IOException {
        writeHead(out, 200, "application/zip", f.length(), name);
        try (InputStream in = new FileInputStream(f)) { CloudBackup.copy(in, out); }
        out.flush();
    }

    private void sendAsset(OutputStream out, String asset, String type) throws IOException {
        try (InputStream in = ctx.getAssets().open(asset)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            CloudBackup.copy(in, b);
            send(out, 200, type, b.toByteArray(), null);
        }
    }
}
