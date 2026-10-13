package com.vironite.retrievex.extract;

import java.util.ArrayList;
import java.util.List;

/** Selection logic for choosing a format of a media item according to a quality mode. */
public final class FormatPicker {
    public static final String BEST = "best";
    public static final String ORIGINAL = "original";
    public static final String SMALLEST = "smallest";

    private FormatPicker() {
    }

    public static int quality(FormatOption f) {
        if (f.width > 0 && f.height > 0) return Math.min(f.width, f.height);
        return f.height;
    }

    /** Formats that may be offered: merge-required ones are hidden when merging is disabled (if alternatives exist). */
    public static List<FormatOption> usable(MediaEntry e, boolean allowMerge) {
        List<FormatOption> out = new ArrayList<>();
        for (FormatOption f : e.formats) {
            if (allowMerge || !f.needsMerge()) out.add(f);
        }
        if (out.isEmpty()) out.addAll(e.formats);
        return out;
    }

    private static long sizeKey(FormatOption f) {
        if (f.estSize > 0) return f.estSize;
        if (f.bitrate > 0) return f.bitrate;
        return Long.MAX_VALUE / 2 + quality(f);
    }

    /** Picks a format. {@code mode} is best, original, smallest or a target height such as "720". */
    public static FormatOption pick(MediaEntry e, String mode, boolean allowMerge) {
        List<FormatOption> list = usable(e, allowMerge);
        if (list.isEmpty()) return null;
        FormatOption best = list.get(0);
        if (mode == null) mode = BEST;
        switch (mode) {
            case SMALLEST:
                for (FormatOption f : list) {
                    if (sizeKey(f) < sizeKey(best)) best = f;
                }
                return best;
            case ORIGINAL:
                for (FormatOption f : list) {
                    if (f.bitrate > best.bitrate) best = f;
                }
                return best;
            case BEST:
                for (FormatOption f : list) {
                    int qf = quality(f);
                    int qb = quality(best);
                    if (qf > qb || (qf == qb && f.bitrate > best.bitrate)) best = f;
                }
                return best;
            default:
                int target;
                try {
                    target = Integer.parseInt(mode);
                } catch (NumberFormatException ex) {
                    return pick(e, BEST, allowMerge);
                }
                FormatOption exact = null;
                FormatOption below = null;
                FormatOption above = null;
                for (FormatOption f : list) {
                    int q = quality(f);
                    if (q == target) {
                        if (exact == null || f.bitrate > exact.bitrate) exact = f;
                    } else if (q < target) {
                        if (below == null || q > quality(below) || (q == quality(below) && f.bitrate > below.bitrate)) below = f;
                    } else {
                        if (above == null || q < quality(above)) above = f;
                    }
                }
                if (exact != null) return exact;
                if (below != null) return below;
                return above != null ? above : best;
        }
    }

    /** Distinct resolutions (as heights) available across the given entries, highest first. */
    public static List<Integer> resolutions(List<MediaEntry> entries, boolean allowMerge) {
        List<Integer> out = new ArrayList<>();
        for (MediaEntry e : entries) {
            if (!e.isVideoLike()) continue;
            for (FormatOption f : usable(e, allowMerge)) {
                int q = quality(f);
                if (q > 0 && !out.contains(q)) out.add(q);
            }
        }
        java.util.Collections.sort(out, java.util.Collections.reverseOrder());
        return out;
    }

    /** Returns a copy of an image format requesting a specific file type (jpg, png or webp) from the source. */
    public static FormatOption imageAs(FormatOption f, String ext) {
        FormatOption c = FormatOption.fromJson(f.toJson());
        // A measured size only applies to the image format it was measured for.
        String key = ext == null ? "original" : ext;
        if (!key.equals(c.sizeFor)) {
            c.estSize = 0;
            c.sizeExact = false;
        }
        if (ext == null || ext.equals("original") || f.url == null || !f.url.contains("format=")) return c;
        c.url = f.url.replaceAll("format=[A-Za-z0-9]+", "format=" + ext);
        c.container = ext;
        c.mime = "png".equals(ext) ? "image/png" : "webp".equals(ext) ? "image/webp" : "image/jpeg";
        return c;
    }
}
