package dev.fzkc.fzhud.stats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small JSON-over-HTTP helper for the stats and tag services. Error text never contains the URL (keys live there). */
final class Http {
    private Http() {}

    /** Thrown for a non-200 answer; status 429 means the service wants us to slow down. */
    static final class Status extends IOException {
        final int code;

        Status(String service, int code) {
            super(service + " HTTP " + code);
            this.code = code;
        }
    }

    static String get(String service, String url, String headerName, String headerValue) throws IOException {
        return send(service, url, null, headerName, headerValue);
    }

    static String post(String service, String url, String json, String headerName, String headerValue) throws IOException {
        return send(service, url, json, headerName, headerValue);
    }

    private static String send(String service, String url, String json, String headerName, String headerValue) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(json == null ? "GET" : "POST");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "FzClient/" + dev.fzkc.fzhud.feature.Branding.VERSION);
            if (headerName != null && headerValue != null) c.setRequestProperty(headerName, headerValue);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new Status(service, code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Status s) {
            throw s;
        } catch (IOException e) {
            // the exception text can contain the URL, and with it an API key
            throw new IOException(service + ": connection failed");
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
