package com.nsxl.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Small HTTPS text fetcher for news feeds. Follows up to five redirects, HTTPS only. */
final class Net {

    private Net() { }

    static String getText(String url, int maxBytes) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NSXL-Launcher/1.0");
            c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = c.getHeaderField("Location");
                    if (next == null) throw new IOException("Bad redirect");
                    url = new URL(new URL(url), next).toString();
                    if (!url.startsWith("https://")) throw new IOException("Insecure redirect");
                    continue;
                }
                if (code >= 400) throw new IOException("Feed error " + code);
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > maxBytes) break;
                        bos.write(buf, 0, n);
                    }
                    return bos.toString("UTF-8");
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}
