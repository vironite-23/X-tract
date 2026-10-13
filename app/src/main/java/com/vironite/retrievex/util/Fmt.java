package com.vironite.retrievex.util;

import android.content.Context;
import android.text.format.DateUtils;

import java.util.Locale;

/** Formatting helpers for sizes, durations, dates and speeds. */
public final class Fmt {
    private Fmt() {
    }

    public static String size(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024.0;
            i++;
        } while (v >= 1024.0 && i < units.length - 1);
        return String.format(Locale.getDefault(), v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    public static String duration(long ms) {
        if (ms <= 0) return "";
        long total = Math.round(ms / 1000.0);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    public static String dateTime(Context c, long ms) {
        if (ms <= 0) return "";
        return DateUtils.formatDateTime(c, ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
    }

    public static String date(Context c, long ms) {
        if (ms <= 0) return "";
        return DateUtils.formatDateTime(c, ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** Short date such as "28 Sep"; the year is only shown for dates outside the current year. */
    public static String dateShort(Context c, long ms) {
        if (ms <= 0) return "";
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        b.setTimeInMillis(ms);
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH;
        if (a.get(java.util.Calendar.YEAR) != b.get(java.util.Calendar.YEAR)) flags |= DateUtils.FORMAT_SHOW_YEAR;
        else flags |= DateUtils.FORMAT_NO_YEAR;
        return DateUtils.formatDateTime(c, ms, flags);
    }

    public static String relative(long ms) {
        if (ms <= 0) return "";
        return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    public static String speed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "";
        return size(bytesPerSec) + "/s";
    }

    public static String resolution(int w, int h) {
        if (w > 0 && h > 0) return w + "\u00D7" + h;
        return "";
    }
}
