package com.vironite.retrievex.extract;

import com.vironite.retrievex.util.Prefs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the id X currently uses for its "TweetResultByRestId" web operation. X rotates these ids every
 * few weeks, so the value is read from X's own public JavaScript (downloaded without any login or
 * cookies), cached for 12 hours, and re-read when X answers that the id is unknown.
 */
final class QueryIds {
    private static final long TTL_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_SCRIPT_BYTES = 6 * 1024 * 1024;
    private static final int MAX_SCRIPTS = 8;

    private static final Pattern ID = Pattern.compile(
            "queryId:\"([A-Za-z0-9_-]{10,40})\",operationName:\"TweetResultByRestId\"");
    private static final Pattern SCRIPT = Pattern.compile(
            "https://abs\\.twimg\\.com/responsive-web/client-web[A-Za-z0-9_-]*/[A-Za-z0-9_./-]*\\.js");
    private static final Pattern API_CHUNK = Pattern.compile("\"api\":\"([a-f0-9]{6,12})\"");
    private static final Pattern ID_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{10,40}$");

    private QueryIds() {
    }

    static synchronized String tweetResult(boolean forceRefresh) throws IOException {
        String cached = Prefs.getString(Prefs.X_QUERY_ID, null);
        long at = Prefs.getLong(Prefs.X_QUERY_ID_TIME, 0);
        boolean fresh = cached != null && ID_FORMAT.matcher(cached).matches()
                && System.currentTimeMillis() - at < TTL_MS;
        if (fresh && !forceRefresh) return cached;
        try {
            String id = resolve();
            Prefs.putString(Prefs.X_QUERY_ID, id);
            Prefs.putLong(Prefs.X_QUERY_ID_TIME, System.currentTimeMillis());
            return id;
        } catch (IOException e) {
            // Better a possibly stale id than none at all; X will say so if it no longer works.
            if (cached != null && ID_FORMAT.matcher(cached).matches()) return cached;
            throw e;
        }
    }

    private static String resolve() throws IOException {
        String html = Http.getText("https://x.com/", null, 4 * 1024 * 1024);
        Set<String> found = new LinkedHashSet<>();
        Matcher sm = SCRIPT.matcher(html);
        while (sm.find()) found.add(sm.group());

        // Try the main bundle first; skip bundles that never hold operation ids.
        List<String> scripts = new ArrayList<>();
        for (String s : found) if (s.contains("/main.")) scripts.add(s);
        for (String s : found) {
            if (scripts.size() >= MAX_SCRIPTS) break;
            if (s.contains("/main.") || s.contains("/vendor.") || s.contains("/polyfills") || s.contains("/i18n/")) continue;
            scripts.add(s);
        }

        String mainBody = null;
        for (String url : scripts) {
            String body;
            try {
                body = Http.getText(url, null, MAX_SCRIPT_BYTES);
            } catch (IOException e) {
                continue;
            }
            Matcher m = ID.matcher(body);
            if (m.find()) return m.group(1);
            if (mainBody == null && url.contains("/main.")) mainBody = body;
        }
        if (mainBody != null) {
            Matcher am = API_CHUNK.matcher(mainBody);
            if (am.find()) {
                String apiUrl = "https://abs.twimg.com/responsive-web/client-web/api." + am.group(1) + "a.js";
                try {
                    Matcher m = ID.matcher(Http.getText(apiUrl, null, MAX_SCRIPT_BYTES));
                    if (m.find()) return m.group(1);
                } catch (IOException ignored) {
                    // fall through to the error below
                }
            }
        }
        throw new IOException("Couldn't find X's current lookup id");
    }
}
