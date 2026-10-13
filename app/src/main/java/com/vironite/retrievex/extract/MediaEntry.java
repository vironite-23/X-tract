package com.vironite.retrievex.extract;

import java.util.ArrayList;
import java.util.List;

/** One media item (image, video or GIF-style animation) inside a post. */
public class MediaEntry {
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_GIF = "gif";

    public int index;            // zero-based position in the post
    public String type;
    public String thumbUrl;
    public int width;
    public int height;
    public long durationMs;
    public final List<FormatOption> formats = new ArrayList<>();

    public boolean isVideoLike() {
        return TYPE_VIDEO.equals(type) || TYPE_GIF.equals(type);
    }
}
