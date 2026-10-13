package com.vironite.retrievex.storage;

import com.vironite.retrievex.util.TextUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Builds file names from the user's template, e.g. {username}_{date}_{postid}_{index}. */
public final class NameTemplate {
    public static final String HELP =
            "{username} {displayname} {postid} {date} {time} {index} {type} {res} {text} {timestamp}";

    private NameTemplate() {
    }

    public static String build(String template, String username, String displayName, String postId, long postDateMs,
                               int index, String mediaType, String resolution, String text, String ext) {
        long when = postDateMs > 0 ? postDateMs : System.currentTimeMillis();
        Date d = new Date(when);
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(d);
        String time = new SimpleDateFormat("HHmmss", Locale.US).format(d);
        String t = template == null || template.trim().isEmpty() ? "{username}_{postid}_{index}" : template;
        t = t.replace("{username}", TextUtil.nz(username).isEmpty() ? "unknown" : username)
                .replace("{displayname}", TextUtil.nz(displayName))
                .replace("{postid}", TextUtil.nz(postId))
                .replace("{date}", date)
                .replace("{time}", time)
                .replace("{index}", String.valueOf(index))
                .replace("{type}", TextUtil.nz(mediaType))
                .replace("{res}", TextUtil.nz(resolution))
                .replace("{text}", TextUtil.ellipsize(TextUtil.nz(text).replace('\n', ' '), 40).replace("\u2026", ""))
                .replace("{timestamp}", String.valueOf(System.currentTimeMillis()));
        String base = TextUtil.sanitizeFileName(t);
        base = base.replaceAll("^[_\\-\\s]+|[_\\-\\s]+$", "");
        if (base.isEmpty()) base = "media_" + TextUtil.nz(postId);
        if (base.length() > 120) base = base.substring(0, 120).trim();
        return base + "." + ext;
    }
}
