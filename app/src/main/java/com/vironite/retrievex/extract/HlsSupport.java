package com.vironite.retrievex.extract;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small HLS (m3u8) parser: master playlists for format discovery and media playlists for segment lists. */
public final class HlsSupport {
    private HlsSupport() {
    }

    public static final class MediaPlaylist {
        public final List<String> segments = new ArrayList<>();
        public String initSegment;           // EXT-X-MAP uri, absolute
        public boolean encrypted;
        public double durationSec;
    }

    private static final class Variant {
        String url;
        int bandwidth;
        int width;
        int height;
        float fps;
        String codecs;
        String audioGroup;
    }

    private static String resolve(String base, String ref) throws IOException {
        return new URL(new URL(base), ref).toString();
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> out = new HashMap<>();
        int colon = line.indexOf(':');
        String s = colon >= 0 ? line.substring(colon + 1) : line;
        int i = 0;
        while (i < s.length()) {
            int eq = s.indexOf('=', i);
            if (eq < 0) break;
            String key = s.substring(i, eq).trim();
            String value;
            int next;
            if (eq + 1 < s.length() && s.charAt(eq + 1) == '"') {
                int end = s.indexOf('"', eq + 2);
                if (end < 0) end = s.length();
                value = s.substring(eq + 2, end);
                next = end + 2;
            } else {
                int end = s.indexOf(',', eq + 1);
                if (end < 0) end = s.length();
                value = s.substring(eq + 1, end);
                next = end + 1;
            }
            out.put(key.toUpperCase(Locale.ROOT), value);
            i = next;
        }
        return out;
    }

    /** Parses a master playlist into downloadable format options (video + optional separate audio). */
    public static List<FormatOption> parseMaster(String masterUrl, String body, long durationMs) throws IOException {
        List<Variant> variants = new ArrayList<>();
        Map<String, String> audioByGroup = new HashMap<>();
        String[] lines = body.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attrs(line);
                if ("AUDIO".equalsIgnoreCase(a.get("TYPE")) && a.get("URI") != null && a.get("GROUP-ID") != null) {
                    if (!audioByGroup.containsKey(a.get("GROUP-ID"))) {
                        audioByGroup.put(a.get("GROUP-ID"), resolve(masterUrl, a.get("URI")));
                    }
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> a = attrs(line);
                int j = i + 1;
                while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].startsWith("#"))) j++;
                if (j >= lines.length) break;
                Variant v = new Variant();
                v.url = resolve(masterUrl, lines[j].trim());
                try {
                    v.bandwidth = Integer.parseInt(a.containsKey("AVERAGE-BANDWIDTH") ? a.get("AVERAGE-BANDWIDTH") : a.get("BANDWIDTH"));
                } catch (Exception ignored) {
                    v.bandwidth = 0;
                }
                String res = a.get("RESOLUTION");
                if (res != null && res.contains("x")) {
                    try {
                        v.width = Integer.parseInt(res.substring(0, res.indexOf('x')));
                        v.height = Integer.parseInt(res.substring(res.indexOf('x') + 1));
                    } catch (NumberFormatException ignored) {
                        // leave 0
                    }
                }
                try {
                    if (a.get("FRAME-RATE") != null) v.fps = Float.parseFloat(a.get("FRAME-RATE"));
                } catch (NumberFormatException ignored) {
                    v.fps = 0;
                }
                v.codecs = a.get("CODECS");
                v.audioGroup = a.get("AUDIO");
                variants.add(v);
                i = j;
            }
        }
        List<FormatOption> out = new ArrayList<>();
        for (Variant v : variants) {
            FormatOption f = new FormatOption();
            f.kind = FormatOption.KIND_HLS;
            f.url = v.url;
            f.container = "mp4";
            f.mime = "video/mp4";
            f.width = v.width;
            f.height = v.height;
            f.fps = v.fps;
            f.bitrate = v.bandwidth;
            if (durationMs > 0 && v.bandwidth > 0) f.estSize = (long) (v.bandwidth / 8.0 * (durationMs / 1000.0));
            if (v.audioGroup != null && audioByGroup.containsKey(v.audioGroup)) {
                f.audioUrl = audioByGroup.get(v.audioGroup);
                f.hasAudio = 1;
            }
            if (v.codecs != null) {
                for (String c : v.codecs.split(",")) {
                    c = c.trim();
                    if (c.startsWith("avc1") || c.startsWith("hvc1") || c.startsWith("hev1") || c.startsWith("av01")) {
                        f.videoCodec = c.startsWith("avc1") ? "H.264" : (c.startsWith("av01") ? "AV1" : "H.265");
                    } else if (c.startsWith("mp4a")) {
                        f.audioCodec = "AAC";
                        f.hasAudio = 1;
                    }
                }
            }
            f.id = "hls-" + (f.height > 0 ? f.height : (int) (v.bandwidth / 1000)) + "-" + v.bandwidth;
            out.add(f);
        }
        return out;
    }

    /** Parses a media playlist into an ordered list of absolute segment URLs. */
    public static MediaPlaylist parseMedia(String playlistUrl, String body) throws IOException {
        MediaPlaylist p = new MediaPlaylist();
        String[] lines = body.split("\\r?\\n");
        double pendingDuration = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = attrs(line);
                String method = a.get("METHOD");
                if (method != null && !"NONE".equalsIgnoreCase(method)) p.encrypted = true;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = attrs(line);
                if (a.get("URI") != null) p.initSegment = resolve(playlistUrl, a.get("URI"));
            } else if (line.startsWith("#EXTINF:")) {
                try {
                    String d = line.substring(8);
                    int comma = d.indexOf(',');
                    if (comma >= 0) d = d.substring(0, comma);
                    pendingDuration = Double.parseDouble(d.trim());
                } catch (NumberFormatException ignored) {
                    pendingDuration = 0;
                }
            } else if (!line.startsWith("#")) {
                p.segments.add(resolve(playlistUrl, line));
                p.durationSec += pendingDuration;
                pendingDuration = 0;
            }
        }
        return p;
    }
}
