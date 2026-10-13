package com.vironite.retrievex.extract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Primary extractor. Uses the public embed ("syndication") endpoint that X serves to any web page
 * embedding a post. No login, cookies or API keys are involved, and it only returns public posts.
 */
public class SyndicationExtractor implements PostExtractor {
    private static final String ENDPOINT = "https://cdn.syndication.twimg.com/tweet-result";

    @Override
    public String name() {
        return "syndication";
    }

    private static String token() {
        String chars = "123456789abcdefghijklmnopqrstuvwxyz";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    @Override
    public PostInfo extract(UrlParser.Parsed p) throws ExtractException {
        Http.Response r;
        try {
            r = Http.get(ENDPOINT + "?id=" + p.statusId + "&lang=en&token=" + token(), null, 2 * 1024 * 1024);
        } catch (SocketTimeoutException e) {
            throw new ExtractException(ExtractException.Kind.TIMEOUT, "The request timed out. Check your connection and try again.", e);
        } catch (UnknownHostException e) {
            throw new ExtractException(ExtractException.Kind.NETWORK, "No internet connection.", e);
        } catch (IOException e) {
            throw new ExtractException(ExtractException.Kind.NETWORK, "Couldn't reach X. Check your connection and try again.", e);
        }

        if (r.code == 404) {
            throw new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
        }
        if (r.code == 401 || r.code == 403) {
            throw new ExtractException(ExtractException.Kind.PRIVATE, "This post is private or not publicly accessible.");
        }
        if (r.code == 429) {
            throw new ExtractException(ExtractException.Kind.RATE_LIMITED, "X is limiting requests right now. Try again in a few minutes.");
        }
        if (r.code >= 400 || r.body == null || r.body.trim().isEmpty()) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "X returned an unexpected response (" + r.code + ").");
        }

        JSONObject root;
        try {
            root = new JSONObject(r.body);
        } catch (JSONException e) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "X returned data in an unexpected format.", e);
        }
        try {
            return parse(root, p);
        } catch (JSONException e) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "Couldn't read the post data. X may have changed its format.", e);
        }
    }

    private PostInfo parse(JSONObject root, UrlParser.Parsed p) throws JSONException, ExtractException {
        String type = root.optString("__typename", "");
        if ("TweetTombstone".equals(type)) {
            String msg = "";
            JSONObject tomb = root.optJSONObject("tombstone");
            if (tomb != null) {
                JSONObject t = tomb.optJSONObject("text");
                if (t != null) msg = t.optString("text", "");
            }
            String low = msg.toLowerCase(Locale.ROOT);
            if (low.contains("protected") || low.contains("private") || low.contains("follow") || low.contains("suspended")
                    || low.contains("age") || low.contains("sensitive") || low.contains("not available")) {
                throw new ExtractException(ExtractException.Kind.PRIVATE,
                        "This post isn't publicly accessible (private, restricted or from an unavailable account).");
            }
            throw new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
        }
        if (!root.has("id_str") && !root.has("mediaDetails") && !root.has("user")) {
            throw new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
        }

        PostInfo info = new PostInfo();
        info.source = name();
        info.postId = root.optString("id_str", p.statusId);
        info.createdAtMs = FormatFactory.parseIsoDate(root.optString("created_at", null));
        info.text = cleanText(root);

        JSONObject user = root.optJSONObject("user");
        if (user != null) {
            info.accountRestId = user.optString("id_str", null);
            info.username = user.optString("screen_name", null);
            info.displayName = user.optString("name", null);
            String avatar = user.optString("profile_image_url_https", null);
            if (avatar != null) avatar = avatar.replace("_normal.", "_400x400.");
            info.avatarUrl = avatar;
        }
        if (info.username == null || info.username.isEmpty()) info.username = p.username;

        JSONArray details = root.optJSONArray("mediaDetails");
        if (details != null) {
            for (int i = 0; i < details.length(); i++) {
                MediaEntry e = parseDetail(details.getJSONObject(i), info.media.size());
                if (e != null) info.media.add(e);
            }
        }
        if (info.media.isEmpty()) {
            throw new ExtractException(ExtractException.Kind.NO_MEDIA, "This post has no downloadable media.");
        }
        return info;
    }

    private static String cleanText(JSONObject root) {
        String text = root.optString("text", null);
        if (text == null) return null;
        JSONArray range = root.optJSONArray("display_text_range");
        if (range != null && range.length() == 2) {
            try {
                int start = range.getInt(0);
                int end = range.getInt(1);
                int cpCount = text.codePointCount(0, text.length());
                if (start >= 0 && end <= cpCount && start <= end) {
                    int s = text.offsetByCodePoints(0, start);
                    int e = text.offsetByCodePoints(0, end);
                    text = text.substring(s, e);
                }
            } catch (Exception ignored) {
                // keep full text
            }
        }
        text = FormatFactory.unescapeHtml(text);
        return text == null ? null : text.trim();
    }

    /** Parses one media object. Shared with {@link AuthedXExtractor}, which receives the same media shape. */
    static MediaEntry parseDetail(JSONObject d, int index) throws JSONException {
        String type = d.optString("type", "");
        String thumbBase = d.optString("media_url_https", null);
        JSONObject orig = d.optJSONObject("original_info");
        int w = orig != null ? orig.optInt("width") : 0;
        int h = orig != null ? orig.optInt("height") : 0;

        JSONObject avail = d.optJSONObject("ext_media_availability");
        if (avail != null && "Unavailable".equalsIgnoreCase(avail.optString("status"))) return null;

        MediaEntry e = new MediaEntry();
        e.index = index;
        e.width = w;
        e.height = h;

        if ("photo".equals(type)) {
            if (thumbBase == null) return null;
            e.type = MediaEntry.TYPE_IMAGE;
            e.thumbUrl = FormatFactory.imageUrl(thumbBase, FormatFactory.extensionOf(thumbBase), "small");
            e.formats.add(FormatFactory.image(thumbBase, w, h));
            return e;
        }
        if ("video".equals(type) || "animated_gif".equals(type)) {
            boolean gif = "animated_gif".equals(type);
            e.type = gif ? MediaEntry.TYPE_GIF : MediaEntry.TYPE_VIDEO;
            e.thumbUrl = thumbBase;
            JSONObject vi = d.optJSONObject("video_info");
            if (vi == null) return null;
            e.durationMs = vi.optLong("duration_millis", 0);
            JSONArray variants = vi.optJSONArray("variants");
            if (variants == null) return null;
            String hlsUrl = null;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < variants.length(); i++) {
                JSONObject v = variants.getJSONObject(i);
                String ct = v.optString("content_type", "");
                String url = v.optString("url", null);
                if (url == null) continue;
                if ("video/mp4".equals(ct)) {
                    FormatOption f = FormatFactory.mp4(url, v.optLong("bitrate", 0), e.durationMs, w, h, gif ? 0 : -1);
                    if (seen.add(f.id)) e.formats.add(f);
                } else if (ct.toLowerCase(Locale.ROOT).contains("mpegurl")) {
                    hlsUrl = url;
                }
            }
            if (e.formats.isEmpty() && hlsUrl != null) {
                try {
                    String body = Http.getText(hlsUrl, null, 512 * 1024);
                    e.formats.addAll(HlsSupport.parseMaster(hlsUrl, body, e.durationMs));
                } catch (IOException ignored) {
                    // no HLS formats available
                }
            }
            if (e.formats.isEmpty()) return null;
            sortFormats(e.formats);
            return e;
        }
        return null;
    }

    static void sortFormats(List<FormatOption> list) {
        Collections.sort(list, new Comparator<FormatOption>() {
            @Override
            public int compare(FormatOption a, FormatOption b) {
                int ha = Math.min(a.width, a.height);
                int hb = Math.min(b.width, b.height);
                if (ha != hb) return hb - ha;
                return Long.compare(b.bitrate, a.bitrate);
            }
        });
    }
}
