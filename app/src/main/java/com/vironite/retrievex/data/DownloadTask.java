package com.vironite.retrievex.data;

/** A download task in the queue. Transient state lives in memory; the rest is persisted. */
public class DownloadTask {
    public static final int WAITING = 0;
    public static final int FETCHING = 1;
    public static final int DOWNLOADING = 2;
    public static final int MERGING = 3;
    public static final int COMPLETED = 4;
    public static final int FAILED = 5;
    public static final int PAUSED = 6;
    public static final int CANCELED = 7;

    public String id;
    public String postUrl;
    public String postId;
    public String accountRestId;
    public String accountUsername;
    public String accountName;
    public String accountAvatar;
    public int mediaIndex;
    public String mediaType;
    public String thumbUrl;
    public String formatId;
    public String formatJson;
    public String targetName;
    public String mimeType;
    public String destinationUri;
    public String tempPath;
    public String tempPath2;
    public volatile int status = WAITING;
    public volatile long bytesDownloaded;
    public volatile long totalBytes = -1;
    public long createdAt;
    public long startedAt;
    public long completedAt;
    public String errorMessage;
    public String errorCode;
    public long mediaItemId;
    public String postText;
    public long postDate;
    public long duration;

    // Transient (not persisted)
    public volatile long speed;
    public volatile float fraction = -1f;
    public volatile String note;

    public boolean isActive() {
        return status == FETCHING || status == DOWNLOADING || status == MERGING;
    }

    public boolean isRunnable() {
        return status == WAITING;
    }

    public boolean isTerminal() {
        return status == COMPLETED || status == FAILED || status == CANCELED;
    }

    /** Progress in percent, or -1 when unknown. */
    public int percent() {
        if (status == COMPLETED) return 100;
        if (fraction >= 0f) return Math.min(100, Math.round(fraction * 100f));
        long total = totalBytes;
        if (total > 0) return (int) Math.min(100, (bytesDownloaded * 100L) / total);
        return -1;
    }

    public DownloadTask copy() {
        DownloadTask t = new DownloadTask();
        t.id = id;
        t.postUrl = postUrl;
        t.postId = postId;
        t.accountRestId = accountRestId;
        t.accountUsername = accountUsername;
        t.accountName = accountName;
        t.accountAvatar = accountAvatar;
        t.mediaIndex = mediaIndex;
        t.mediaType = mediaType;
        t.thumbUrl = thumbUrl;
        t.formatId = formatId;
        t.formatJson = formatJson;
        t.targetName = targetName;
        t.mimeType = mimeType;
        t.destinationUri = destinationUri;
        t.tempPath = tempPath;
        t.tempPath2 = tempPath2;
        t.status = status;
        t.bytesDownloaded = bytesDownloaded;
        t.totalBytes = totalBytes;
        t.createdAt = createdAt;
        t.startedAt = startedAt;
        t.completedAt = completedAt;
        t.errorMessage = errorMessage;
        t.errorCode = errorCode;
        t.mediaItemId = mediaItemId;
        t.postText = postText;
        t.postDate = postDate;
        t.duration = duration;
        t.speed = speed;
        t.fraction = fraction;
        t.note = note;
        return t;
    }
}
