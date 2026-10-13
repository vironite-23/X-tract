package com.vironite.retrievex.extract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/** Minimal HTTPS client helpers with timeouts, manual redirect handling and a response size cap. */
public final class Http {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    public static final int CONNECT_TIMEOUT = 15000;
    public static final int READ_TIMEOUT = 20000;
    private static final int MAX_REDIRECTS = 6;

    /** Thrown when the server answers with an HTTP error status. */
    public static class StatusException extends IOException {
        public final int code;

        public StatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Http() {
    }

    /** Validates that a URL is HTTPS with a host, so untrusted URLs cannot reach file:// or cleartext endpoints. */
    public static URL requireHttps(String url) throws IOException {
        URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getHost() == null || u.getHost().isEmpty()) {
            throw new IOException("Only HTTPS addresses are supported");
        }
        return u;
    }

    /** Opens a connection and follows redirects (HTTPS only). The caller must close/disconnect it. */
    public static HttpURLConnection open(String url, Map<String, String> headers, int connectMs, int readMs)
            throws IOException {
        return open(url, headers, connectMs, readMs, "GET");
    }

    /** Same as {@link #open(String, Map, int, int)} with an explicit HTTP method such as HEAD. */
    public static HttpURLConnection open(String url, Map<String, String> headers, int connectMs, int readMs,
                                         String method) throws IOException {
        String current = url;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            URL u = requireHttps(current);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Redirect without location");
                current = new URL(u, loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    /** GET returning the body as text. HTTP error statuses are returned, not thrown. */
    public static Response get(String url, Map<String, String> headers, int maxBytes) throws IOException {
        HttpURLConnection c = open(url, headers, CONNECT_TIMEOUT, READ_TIMEOUT);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                try {
                    body = readAll(in, maxBytes);
                } finally {
                    in.close();
                }
            }
            return new Response(code, body);
        } finally {
            c.disconnect();
        }
    }

    /** GET returning the body; throws {@link StatusException} for HTTP errors. */
    public static String getText(String url, Map<String, String> headers, int maxBytes) throws IOException {
        Response r = get(url, headers, maxBytes);
        if (r.code >= 400) throw new StatusException(r.code);
        return r.body;
    }

    private static String readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) throw new IOException("Response too large");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), Charset.forName("UTF-8"));
    }
}
