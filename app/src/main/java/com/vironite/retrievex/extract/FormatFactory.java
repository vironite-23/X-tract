package com.vironite.retrievex.extract;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds {@link FormatOption} objects from raw source data, only filling fields the source provides. */
final class FormatFactory {
    private static final Pattern VID_PATH = Pattern.compile("/vid/(?:([A-Za-z0-9]+)/)?(\\d{2,5})x(\\d{2,5})/");

    private FormatFactory() {
    }

    static FormatOption mp4(String url, long bitrate, long durationMs, int fallbackW, int fallbackH, int hasAudio) {
        FormatOption f = new FormatOption();
        f.kind = FormatOption.KIND_PROGRESSIVE;
        f.url = url;
        f.container = "mp4";
        f.mime = "video/mp4";
        f.bitrate = bitrate;
        f.hasAudio = hasAudio;
        Matcher m = VID_PATH.matcher(url);
        if (m.find()) {
            String codec = m.group(1);
            f.width = Integer.parseInt(m.group(2));
            f.height = Integer.parseInt(m.group(3));
            if (codec != null) {
                String c = codec.toLowerCase(Locale.ROOT);
                if (c.startsWith("avc")) f.videoCodec = "H.264";
                else if (c.startsWith("hev") || c.startsWith("hvc")) f.videoCodec = "H.265";
                else f.videoCodec = codec;
            }
        } else {
            f.width = fallbackW;
            f.height = fallbackH;
        }
        if (bitrate > 0 && durationMs > 0) f.estSize = (long) (bitrate / 8.0 * (durationMs / 1000.0));
        f.id = "mp4-" + (f.height > 0 ? f.height : 0) + "-" + bitrate;
        return f;
    }

    /** Original-quality image option. {@code baseUrl} is the plain media URL, e.g. https://pbs.twimg.com/media/ID.jpg */
    static FormatOption image(String baseUrl, int w, int h) {
        FormatOption f = new FormatOption();
        f.kind = FormatOption.KIND_IMAGE;
        f.id = "orig";
        f.width = w;
        f.height = h;
        String ext = extensionOf(baseUrl);
        f.container = ext;
        f.mime = mimeForExt(ext);
        f.url = imageUrl(baseUrl, ext, "orig");
        return f;
    }

    static String extensionOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            // Old style "ID.jpg:orig" suffixes
            int colon = ext.indexOf(':');
            if (colon > 0) ext = ext.substring(0, colon);
            if (ext.equals("jpeg")) ext = "jpg";
            return ext;
        }
        // format=jpg query style
        Matcher m = Pattern.compile("[?&]format=([a-zA-Z0-9]+)").matcher(url);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        return "jpg";
    }

    static String mimeForExt(String ext) {
        if ("png".equals(ext)) return "image/png";
        if ("webp".equals(ext)) return "image/webp";
        if ("gif".equals(ext)) return "image/gif";
        return "image/jpeg";
    }

    /** Builds a pbs.twimg.com style URL with explicit format and size; leaves other hosts untouched. */
    static String imageUrl(String baseUrl, String ext, String name) {
        int q = baseUrl.indexOf('?');
        String clean = q >= 0 ? baseUrl.substring(0, q) : baseUrl;
        if (!clean.contains("pbs.twimg.com/media/")) return baseUrl;
        int slash = clean.lastIndexOf('/');
        int dot = clean.lastIndexOf('.');
        String noExt = dot > slash ? clean.substring(0, dot) : clean;
        return noExt + "?format=" + ext + "&name=" + name;
    }

    static long parseIsoDate(String s) {
        if (s == null) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "EEE MMM dd HH:mm:ss Z yyyy"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
                // try next pattern
            }
        }
        return 0;
    }

    static String unescapeHtml(String s) {
        if (s == null) return null;
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }
}
