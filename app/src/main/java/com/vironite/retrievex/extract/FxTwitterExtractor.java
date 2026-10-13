package com.vironite.retrievex.extract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

/**
 * Optional fallback extractor backed by the public FxTwitter API (api.fxtwitter.com). It is only used
 * when the primary extractor fails and the user has not disabled the fallback in Settings. Only the post
 * URL is sent to the service; no media is ever uploaded.
 */
public class FxTwitterExtractor implements PostExtractor {
    private static final String BASE = "https://api.fxtwitter.com/";

    public static final class UserInfo {
        public String restId;
        public String username;
        public String displayName;
        public String avatarUrl;
    }

    @Override
    public String name() {
        return "fxtwitter";
    }

    @Override
    public PostInfo extract(UrlParser.Parsed p) throws ExtractException {
        String user = p.username != null ? p.username : "i";
        JSONObject root = fetch(BASE + user + "/status/" + p.statusId);
        try {
            return parse(root, p);
        } catch (JSONException e) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "Couldn't read the fallback service response.", e);
        }
    }

    /** Looks up public profile info (display name, avatar). Used when refreshing a stored account. */
    public static UserInfo fetchUser(String username) throws ExtractException {
        JSONObject root = fetch(BASE + username);
        JSONObject u = root.optJSONObject("user");
        if (u == null) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "No profile information was returned.");
        }
        UserInfo info = new UserInfo();
        info.restId = u.optString("id", null);
        info.username = u.optString("screen_name", username);
        info.displayName = u.optString("name", null);
        info.avatarUrl = bigAvatar(u.optString("avatar_url", null));
        return info;
    }

    private static JSONObject fetch(String url) throws ExtractException {
        Http.Response r;
        try {
            r = Http.get(url, null, 2 * 1024 * 1024);
        } catch (SocketTimeoutException e) {
            throw new ExtractException(ExtractException.Kind.TIMEOUT, "The fallback service timed out.", e);
        } catch (UnknownHostException e) {
            throw new ExtractException(ExtractException.Kind.NETWORK, "No internet connection.", e);
        } catch (IOException e) {
            throw new ExtractException(ExtractException.Kind.NETWORK, "Couldn't reach the fallback service.", e);
        }
        if (r.code == 404) {
            throw new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
        }
        if (r.code == 401 || r.code == 403) {
            throw new ExtractException(ExtractException.Kind.PRIVATE, "This post is private or not publicly accessible.");
        }
        if (r.code == 429) {
            throw new ExtractException(ExtractException.Kind.RATE_LIMITED, "The fallback service is limiting requests. Try again later.");
        }
        if (r.code >= 400 || r.body == null || r.body.trim().isEmpty()) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "The fallback service returned an error (" + r.code + ").");
        }
        try {
            return new JSONObject(r.body);
        } catch (JSONException e) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "The fallback service returned unexpected data.", e);
        }
    }

    private PostInfo parse(JSONObject root, UrlParser.Parsed p) throws JSONException, ExtractException {
        JSONObject t = root.optJSONObject("tweet");
        if (t == null) {
            throw new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
        }
        PostInfo info = new PostInfo();
        info.source = name();
        info.postId = t.optString("id", p.statusId);
        info.text = FormatFactory.unescapeHtml(t.optString("text", null));
        long ts = t.optLong("created_timestamp", 0);
        info.createdAtMs = ts > 0 ? ts * 1000L : 0;

        JSONObject author = t.optJSONObject("author");
        if (author != null) {
            info.accountRestId = author.optString("id", null);
            info.username = author.optString("screen_name", null);
            info.displayName = author.optString("name", null);
            info.avatarUrl = bigAvatar(author.optString("avatar_url", null));
        }
        if (info.username == null || info.username.isEmpty()) info.username = p.username;

        JSONObject media = t.optJSONObject("media");
        JSONArray all = media != null ? media.optJSONArray("all") : null;
        if (all != null) {
            for (int i = 0; i < all.length(); i++) {
                MediaEntry e = parseMedia(all.getJSONObject(i), info.media.size());
                if (e != null) info.media.add(e);
            }
        }
        if (info.media.isEmpty()) {
            throw new ExtractException(ExtractException.Kind.NO_MEDIA, "This post has no downloadable media.");
        }
        return info;
    }

    private MediaEntry parseMedia(JSONObject m, int index) throws JSONException {
        String type = m.optString("type", "");
        String url = m.optString("url", null);
        int w = m.optInt("width");
        int h = m.optInt("height");
        MediaEntry e = new MediaEntry();
        e.index = index;
        e.width = w;
        e.height = h;
        if ("photo".equals(type)) {
            if (url == null) return null;
            e.type = MediaEntry.TYPE_IMAGE;
            e.thumbUrl = FormatFactory.imageUrl(url, FormatFactory.extensionOf(url), "small");
            e.formats.add(FormatFactory.image(url, w, h));
            return e;
        }
        if ("video".equals(type) || "gif".equals(type)) {
            boolean gif = "gif".equals(type);
            e.type = gif ? MediaEntry.TYPE_GIF : MediaEntry.TYPE_VIDEO;
            e.thumbUrl = m.optString("thumbnail_url", null);
            double dur = m.optDouble("duration", 0);
            e.durationMs = dur > 0 ? Math.round(dur * 1000) : 0;
            Set<String> seen = new HashSet<>();
            String hlsUrl = null;
            JSONArray variants = m.optJSONArray("variants");
            if (variants != null) {
                for (int i = 0; i < variants.length(); i++) {
                    JSONObject v = variants.getJSONObject(i);
                    String ct = v.optString("content_type", "");
                    String vu = v.optString("url", null);
                    if (vu == null) continue;
                    if ("video/mp4".equals(ct)) {
                        FormatOption f = FormatFactory.mp4(vu, v.optLong("bitrate", 0), e.durationMs, w, h, gif ? 0 : -1);
                        if (seen.add(f.id)) e.formats.add(f);
                    } else if (ct.toLowerCase().contains("mpegurl")) {
                        hlsUrl = vu;
                    }
                }
            }
            if (e.formats.isEmpty() && url != null && url.contains(".mp4")) {
                FormatOption f = FormatFactory.mp4(url, 0, e.durationMs, w, h, gif ? 0 : -1);
                e.formats.add(f);
            }
            if (e.formats.isEmpty() && hlsUrl != null) {
                try {
                    String body = Http.getText(hlsUrl, null, 512 * 1024);
                    e.formats.addAll(HlsSupport.parseMaster(hlsUrl, body, e.durationMs));
                } catch (IOException ignored) {
                    // no HLS formats
                }
            }
            if (e.formats.isEmpty()) return null;
            SyndicationExtractor.sortFormats(e.formats);
            return e;
        }
        return null;
    }

    /** X serves avatars as 48 px "_normal" images by default; the 400 px version stays sharp when scaled up. */
    private static String bigAvatar(String url) {
        return url == null ? null : url.replace("_normal.", "_400x400.");
    }
}
