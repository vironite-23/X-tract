package com.vironite.retrievex.auth;

import com.vironite.retrievex.extract.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The only place that attaches the X session to a request. Safeguards:
 * <ul>
 *   <li>HTTPS only, default port, no user-info, and the host must be on a short allow-list.</li>
 *   <li>Redirects are never followed, so credentials cannot be forwarded to another host.</li>
 *   <li>Response size is capped, and nothing about the request is ever logged.</li>
 * </ul>
 */
public final class AuthHttp {
    /** Public bearer token that the x.com web client itself embeds; it identifies the web app, not a user. */
    private static final String WEB_BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA";
    private static final Set<String> HOSTS = Collections.unmodifiableSet(new HashSet<String>(
            java.util.Arrays.asList("x.com")));
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Response {
        public final int code;
        public final String body;
        /** A refreshed ct0 value sent by the server, or null. */
        public final String newCt0;

        Response(int code, String body, String newCt0) {
            this.code = code;
            this.body = body;
            this.newCt0 = newCt0;
        }
    }

    private AuthHttp() {
    }

    public static Response get(String url, Session s, int maxBytes) throws IOException {
        return request("GET", url, s, null, maxBytes, Http.READ_TIMEOUT);
    }

    public static Response request(String method, String url, Session s, String formBody, int maxBytes, int timeoutMs)
            throws IOException {
        URL u = new URL(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(u.getProtocol()) || !HOSTS.contains(host)
                || (u.getPort() != -1 && u.getPort() != 443) || u.getUserInfo() != null) {
            throw new IOException("Blocked: the X session can only be sent to x.com over HTTPS");
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(Math.min(timeoutMs, Http.CONNECT_TIMEOUT));
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", Http.USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setRequestProperty("Referer", "https://x.com/");
            c.setRequestProperty("Authorization", "Bearer " + WEB_BEARER);
            c.setRequestProperty("X-Csrf-Token", s.ct0);
            c.setRequestProperty("Cookie", s.cookieHeader());
            c.setRequestProperty("X-Twitter-Auth-Type", "OAuth2Session");
            c.setRequestProperty("X-Twitter-Active-User", "yes");
            c.setRequestProperty("X-Twitter-Client-Language", "en");
            if (formBody != null) {
                byte[] data = formBody.getBytes(UTF8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream out = c.getOutputStream();
                try {
                    out.write(data);
                } finally {
                    out.close();
                }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new IOException("Unexpected redirect");
            String newCt0 = readCt0(c);
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                try {
                    body = readAll(in, maxBytes);
                } finally {
                    in.close();
                }
            }
            return new Response(code, body, newCt0);
        } finally {
            c.disconnect();
        }
    }

    private static String readCt0(HttpURLConnection c) {
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String v : e.getValue()) {
                if (v != null && v.startsWith("ct0=")) {
                    int end = v.indexOf(';');
                    String val = end < 0 ? v.substring(4) : v.substring(4, end);
                    if (Session.validCt0(val)) return val;
                }
            }
        }
        return null;
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
        return new String(out.toByteArray(), UTF8);
    }
}
