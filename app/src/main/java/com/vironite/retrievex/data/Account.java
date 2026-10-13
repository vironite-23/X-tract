package com.vironite.retrievex.data;

/** A locally tracked X account (a record of download activity only). */
public class Account {
    public long id;
    public String restId;
    public String username;
    public String displayName;
    public String profileUrl;
    public String avatarUrl;
    public String cachedAvatarPath;
    public long createdAt;
    public long firstDownloadedAt;
    public long lastDownloadedAt;
    public String customLabel;
    public boolean hidden;
    public long metaUpdatedAt;

    // Statistics calculated from media records
    public int mediaCount;
    public int videoCount;
    public int imageCount;
    public long totalSize;

    public String label() {
        if (customLabel != null && !customLabel.trim().isEmpty()) return customLabel;
        if (displayName != null && !displayName.trim().isEmpty()) return displayName;
        return "@" + username;
    }

    public String handle() {
        return "@" + username;
    }
}
