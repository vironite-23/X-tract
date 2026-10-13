package com.vironite.retrievex.download;

import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.extract.FormatOption;
import com.vironite.retrievex.extract.FormatPicker;
import com.vironite.retrievex.extract.MediaEntry;
import com.vironite.retrievex.extract.PostInfo;
import com.vironite.retrievex.storage.NameTemplate;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.Prefs;

import java.util.UUID;

/** Creates queue tasks from extracted post data. */
public final class TaskFactory {
    private TaskFactory() {
    }

    public static DownloadTask create(PostInfo post, MediaEntry e, FormatOption chosen, int ordinal) {
        FormatOption f = chosen;
        boolean image = e.type.equals(MediaEntry.TYPE_IMAGE);
        if (image) f = FormatPicker.imageAs(chosen, Prefs.getString(Prefs.IMAGE_FORMAT, "original"));

        DownloadTask t = new DownloadTask();
        t.id = UUID.randomUUID().toString();
        t.postUrl = post.postUrl;
        t.postId = post.postId;
        t.accountRestId = post.accountRestId;
        t.accountUsername = post.username;
        t.accountName = post.displayName;
        t.accountAvatar = post.avatarUrl;
        t.mediaIndex = e.index;
        t.mediaType = e.type;
        t.thumbUrl = e.thumbUrl;
        t.formatId = f.id;
        t.formatJson = f.toJson();
        String ext = image ? (f.container != null ? f.container : "jpg") : "mp4";
        String res = image ? Fmt.resolution(f.width, f.height) : f.resolutionLabel();
        t.targetName = NameTemplate.build(Prefs.template(), post.username, post.displayName, post.postId,
                post.createdAtMs, e.index + 1, e.type, res, post.text, ext);
        t.mimeType = f.mime != null ? f.mime : (image ? "image/jpeg" : "video/mp4");
        t.createdAt = System.currentTimeMillis() + ordinal;
        t.postText = post.text;
        t.postDate = post.createdAtMs;
        t.duration = e.durationMs;
        t.totalBytes = f.estSize > 0 ? f.estSize : -1;
        return t;
    }
}
