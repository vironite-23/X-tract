package com.vironite.retrievex.extract;

import com.vironite.retrievex.util.Fmt;

import org.json.JSONException;
import org.json.JSONObject;

/** One downloadable variant of a media item (a video rendition or an image). */
public class FormatOption {
    public static final String KIND_PROGRESSIVE = "progressive";
    public static final String KIND_HLS = "hls";
    public static final String KIND_IMAGE = "image";

    public String id;
    public String kind = KIND_PROGRESSIVE;
    public String url;
    public String audioUrl;      // separate audio stream, when the source provides one
    public String container;     // mp4, jpg, png, webp...
    public String mime;
    public String videoCodec;    // null when the source does not say
    public String audioCodec;
    public int width;
    public int height;
    public float fps;
    public long bitrate;         // bits per second, 0 if unknown
    public long estSize;         // bytes, 0 if unknown
    public boolean sizeExact;    // true when estSize is the real file size reported by the server
    public String sizeFor;       // image format preference estSize was measured for (images only)
    public int hasAudio = -1;    // -1 unknown, 0 no, 1 yes

    public boolean isHls() {
        return KIND_HLS.equals(kind);
    }

    /** True when downloading needs a merge / remux step on the device. */
    public boolean needsMerge() {
        return isHls() || audioUrl != null;
    }

    public boolean isImage() {
        return KIND_IMAGE.equals(kind);
    }

    /** Short resolution label such as "720p"; empty when unknown. */
    public String resolutionLabel() {
        int side = Math.min(width, height);
        if (width > 0 && height > 0) return side + "p";
        if (height > 0) return height + "p";
        return "";
    }

    /** Human readable one-line description built only from fields the source actually provided. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        String res = resolutionLabel();
        if (!res.isEmpty()) sb.append(res);
        if (width > 0 && height > 0) append(sb, Fmt.resolution(width, height));
        if (container != null) append(sb, container.toUpperCase());
        if (videoCodec != null) append(sb, videoCodec);
        if (audioCodec != null) {
            append(sb, audioCodec);
        } else if (hasAudio == 1) {
            append(sb, "audio");
        } else if (hasAudio == 0) {
            append(sb, "no audio");
        }
        if (fps > 0) append(sb, Math.round(fps) + " fps");
        if (estSize > 0) append(sb, sizeLabel());
        else if (bitrate > 0) append(sb, Math.round(bitrate / 1000f) + " kbps");
        if (needsMerge()) append(sb, "merge required");
        return sb.toString();
    }

    /** Size text: exact sizes are shown plainly, estimates get a leading approximation sign. */
    public String sizeLabel() {
        if (estSize <= 0) return "";
        return sizeExact ? Fmt.size(estSize) : "\u2248 " + Fmt.size(estSize);
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append(" \u00B7 ");
        sb.append(part);
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("kind", kind);
            o.put("url", url);
            if (audioUrl != null) o.put("audioUrl", audioUrl);
            if (container != null) o.put("container", container);
            if (mime != null) o.put("mime", mime);
            if (videoCodec != null) o.put("videoCodec", videoCodec);
            if (audioCodec != null) o.put("audioCodec", audioCodec);
            o.put("width", width);
            o.put("height", height);
            o.put("fps", fps);
            o.put("bitrate", bitrate);
            o.put("estSize", estSize);
            o.put("sizeExact", sizeExact);
            if (sizeFor != null) o.put("sizeFor", sizeFor);
            o.put("hasAudio", hasAudio);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static FormatOption fromJson(String json) {
        FormatOption f = new FormatOption();
        try {
            JSONObject o = new JSONObject(json);
            f.id = o.optString("id", null);
            f.kind = o.optString("kind", KIND_PROGRESSIVE);
            f.url = o.optString("url", null);
            f.audioUrl = o.has("audioUrl") ? o.optString("audioUrl", null) : null;
            f.container = o.has("container") ? o.optString("container", null) : null;
            f.mime = o.has("mime") ? o.optString("mime", null) : null;
            f.videoCodec = o.has("videoCodec") ? o.optString("videoCodec", null) : null;
            f.audioCodec = o.has("audioCodec") ? o.optString("audioCodec", null) : null;
            f.width = o.optInt("width");
            f.height = o.optInt("height");
            f.fps = (float) o.optDouble("fps", 0);
            f.bitrate = o.optLong("bitrate");
            f.estSize = o.optLong("estSize");
            f.sizeExact = o.optBoolean("sizeExact", false);
            f.sizeFor = o.has("sizeFor") ? o.optString("sizeFor", null) : null;
            f.hasAudio = o.optInt("hasAudio", -1);
        } catch (JSONException ignored) {
            // return what we have
        }
        return f;
    }
}
