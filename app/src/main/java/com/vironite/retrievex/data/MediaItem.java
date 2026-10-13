package com.vironite.retrievex.data;

/** A downloaded media file recorded in the persistent library. */
public class MediaItem {
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_GIF = "gif";

    public static final int AVAIL_OK = 0;
    public static final int AVAIL_MISSING = 1;
    public static final int AVAIL_INACCESSIBLE = 2;

    public long id;
    public String postId;
    public String postUrl;
    public long accountId;
    public String filename;
    public String fileUri;
    public String mimeType;
    public String mediaType;
    public int width;
    public int height;
    public long duration;      // milliseconds
    public long fileSize;
    public String videoCodec;
    public String audioCodec;
    public float frameRate;
    public int hasAudio = -1;  // -1 unknown, 0 no, 1 yes
    public long downloadedAt;
    public String sourceMetadata;
    public String postText;
    public int availability;

    /** UI only (Detail layout): fingerprint of every file in this item's post, so the list redraws when any of them changes. */
    public String groupSig;
    private long postDateCache = -1;

    // Joined account columns (read-only, filled by queries)
    public String accountUsername;
    public String accountDisplayName;
    public String accountCustomLabel;
    public String accountAvatarPath;
    public String accountAvatarUrl;

    /** When the post was published (read from the stored source data), falling back to the download time. */
    public long postDate() {
        if (postDateCache < 0) {
            long v = 0;
            if (sourceMetadata != null && !sourceMetadata.isEmpty()) {
                try {
                    v = new org.json.JSONObject(sourceMetadata).optLong("postDate", 0);
                } catch (Exception ignored) {
                    // malformed metadata: use the download time below
                }
            }
            postDateCache = v > 0 ? v : Math.max(0, downloadedAt);
        }
        return postDateCache;
    }

    public boolean isVideoLike() {
        return TYPE_VIDEO.equals(mediaType) || TYPE_GIF.equals(mediaType);
    }

    public boolean isImage() {
        return TYPE_IMAGE.equals(mediaType);
    }

    /** Best label for the account that owns this item, or null when unknown. */
    public String accountLabel() {
        if (accountCustomLabel != null && !accountCustomLabel.trim().isEmpty()) return accountCustomLabel;
        if (accountUsername != null && !accountUsername.isEmpty()) return "@" + accountUsername;
        return null;
    }

    public MediaItem copy() {
        MediaItem m = new MediaItem();
        m.id = id;
        m.postId = postId;
        m.postUrl = postUrl;
        m.accountId = accountId;
        m.filename = filename;
        m.fileUri = fileUri;
        m.mimeType = mimeType;
        m.mediaType = mediaType;
        m.width = width;
        m.height = height;
        m.duration = duration;
        m.fileSize = fileSize;
        m.videoCodec = videoCodec;
        m.audioCodec = audioCodec;
        m.frameRate = frameRate;
        m.hasAudio = hasAudio;
        m.downloadedAt = downloadedAt;
        m.sourceMetadata = sourceMetadata;
        m.postText = postText;
        m.availability = availability;
        m.accountUsername = accountUsername;
        m.accountDisplayName = accountDisplayName;
        m.accountCustomLabel = accountCustomLabel;
        m.accountAvatarPath = accountAvatarPath;
        m.accountAvatarUrl = accountAvatarUrl;
        return m;
    }
}
