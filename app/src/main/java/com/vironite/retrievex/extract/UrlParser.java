package com.vironite.retrievex.extract;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates and normalises X / Twitter post URLs. */
public final class UrlParser {
    public static final class Parsed {
        public final String username;   // may be null for /i/status/ links
        public final String statusId;
        public final String normalizedUrl;

        Parsed(String username, String statusId, String normalizedUrl) {
            this.username = username;
            this.statusId = statusId;
            this.normalizedUrl = normalizedUrl;
        }
    }

    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "x.com", "www.x.com", "mobile.x.com", "m.x.com",
            "twitter.com", "www.twitter.com", "mobile.twitter.com", "m.twitter.com",
            "fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com", "girlcockx.com"));

    private static final Pattern URL_IN_TEXT = Pattern.compile(
            "(?i)((?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}/[^\\s<>\"']+)");
    private static final Pattern PATH = Pattern.compile(
            "^/(?:i/(?:web/)?|([A-Za-z0-9_]{1,15})/)status(?:es)?/(\\d{1,20})(?:[/?#].*)?$");

    private UrlParser() {
    }

    /** Pulls the first candidate link out of arbitrary text (for example a shared message). */
    public static String findUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_IN_TEXT.matcher(text);
        while (m.find()) {
            String candidate = m.group(1);
            // Strip trailing punctuation commonly attached to links in prose.
            while (candidate.length() > 0 && ".,;:)!?]".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            try {
                parse(candidate);
                return candidate;
            } catch (ExtractException ignored) {
                // keep looking
            }
        }
        return null;
    }

    public static Parsed parse(String raw) throws ExtractException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new ExtractException(ExtractException.Kind.INVALID_URL, "Enter the link of an X post.");
        }
        String s = raw.trim();
        // Allow text that contains a link (e.g. shared text) by taking the first token that looks like one.
        if (s.contains(" ") || s.contains("\n")) {
            String found = null;
            Matcher m = URL_IN_TEXT.matcher(s);
            if (m.find()) found = m.group(1);
            if (found == null) {
                throw new ExtractException(ExtractException.Kind.INVALID_URL, "That doesn't look like a link.");
            }
            s = found;
        }
        if (!s.toLowerCase(Locale.ROOT).startsWith("http://") && !s.toLowerCase(Locale.ROOT).startsWith("https://")) {
            s = "https://" + s;
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (Exception e) {
            throw new ExtractException(ExtractException.Kind.INVALID_URL, "That link is not valid.");
        }
        if (uri.getUserInfo() != null) {
            throw new ExtractException(ExtractException.Kind.UNSUPPORTED_URL, "That link is not supported.");
        }
        String host = uri.getHost();
        if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new ExtractException(ExtractException.Kind.UNSUPPORTED_URL,
                    "Only links to posts on x.com or twitter.com are supported.");
        }
        String path = uri.getRawPath();
        if (path == null) path = "";
        Matcher m = PATH.matcher(path);
        if (!m.matches()) {
            throw new ExtractException(ExtractException.Kind.UNSUPPORTED_URL,
                    "This is not a link to a single post. Open the post and copy its link.");
        }
        String user = m.group(1);
        String id = m.group(2);
        String normalized = "https://x.com/" + (user != null ? user : "i") + "/status/" + id;
        return new Parsed(user, id, normalized);
    }
}
