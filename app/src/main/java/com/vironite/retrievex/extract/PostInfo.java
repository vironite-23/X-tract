package com.vironite.retrievex.extract;

import java.util.ArrayList;
import java.util.List;

/** Everything the extractor learned about a post. Missing data stays null/0; nothing is fabricated. */
public class PostInfo {
    public String postId;
    public String postUrl;
    public String text;
    public long createdAtMs;
    public String accountRestId;
    public String username;
    public String displayName;
    public String avatarUrl;
    public String source;
    public final List<MediaEntry> media = new ArrayList<>();
}
