package com.vironite.retrievex.util;

import java.util.Locale;

/** Small string helpers shared across the app. */
public final class TextUtil {
    private TextUtil() {
    }

    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** Removes characters that are unsafe in file names and collapses whitespace. */
    public static String sanitizeFileName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 32 || "\\/:*?\"<>|".indexOf(ch) >= 0) {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String r = sb.toString().replaceAll("\\s+", " ").replaceAll("_{2,}", "_").trim();
        while (r.startsWith(".")) r = r.substring(1);
        while (r.endsWith(".") || r.endsWith(" ")) r = r.substring(0, r.length() - 1);
        return r;
    }

    public static String ellipsize(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    /** Escapes % _ and \ for use in SQL LIKE ... ESCAPE '\'. */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
