package com.vironite.retrievex.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.vironite.retrievex.R;
import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.storage.FileStore;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.TextUtil;

import java.util.ArrayList;
import java.util.List;

/** Open / share / browse helpers shared by the library and the download queue. */
public final class FileActions {
    private FileActions() {
    }

    public static void openUri(Context c, String uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(uri), TextUtil.isEmpty(mime) ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(Intent.createChooser(i, c.getString(R.string.open_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(c, R.string.cannot_access_file, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens a downloaded item in the built-in viewer / player. */
    public static void open(Context c, MediaItem m) {
        MediaViewerActivity.startSingle(c, m);
    }

    /** Opens the viewer on {@code items.get(index)} so neighbouring items can be browsed by swiping. */
    public static void open(Context c, List<MediaItem> items, int index) {
        MediaViewerActivity.start(c, items, index);
    }

    public static void shareUris(Context c, List<String> uris, List<String> mimes) {
        if (uris.isEmpty()) return;
        try {
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(TextUtil.isEmpty(mimes.get(0)) ? "*/*" : mimes.get(0));
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uris.get(0)));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                ArrayList<Uri> list = new ArrayList<>();
                for (String u : uris) list.add(Uri.parse(u));
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
                ClipData clip = ClipData.newRawUri("media", list.get(0));
                for (int k = 1; k < list.size(); k++) clip.addItem(new ClipData.Item(list.get(k)));
                i.setClipData(clip);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(Intent.createChooser(i, c.getString(R.string.share)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, R.string.no_app_to_share, Toast.LENGTH_SHORT).show();
        }
    }

    public static void share(Context c, List<MediaItem> items) {
        List<String> uris = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        for (MediaItem m : items) {
            uris.add(m.fileUri);
            mimes.add(m.mimeType);
        }
        shareUris(c, uris, mimes);
    }

    /** Opens a post in the browser. Only called on user action and only for HTTPS links. */
    public static void openPost(Context c, String url) {
        if (url == null || !url.startsWith("https://")) return;
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    public static void openFolder(Context c) {
        try {
            c.startActivity(FileStore.folderIntent(c));
        } catch (Exception e) {
            Toast.makeText(c, c.getString(R.string.saved_in, FileStore.destinationLabel(c)), Toast.LENGTH_LONG).show();
        }
    }

    public static String details(Context c, MediaItem m) {
        StringBuilder sb = new StringBuilder();
        line(sb, c.getString(R.string.d_name), m.filename);
        line(sb, c.getString(R.string.d_type), typeLabel(c, m.mediaType) + (TextUtil.isEmpty(m.mimeType) ? "" : " (" + m.mimeType + ")"));
        line(sb, c.getString(R.string.d_size), Fmt.size(m.fileSize));
        if (m.width > 0 && m.height > 0) line(sb, c.getString(R.string.d_resolution), Fmt.resolution(m.width, m.height));
        if (m.duration > 0) line(sb, c.getString(R.string.d_duration), Fmt.duration(m.duration));
        if (!TextUtil.isEmpty(m.videoCodec)) line(sb, c.getString(R.string.d_video_codec), m.videoCodec);
        if (m.isVideoLike()) {
            String audio = m.hasAudio == 1 ? (TextUtil.isEmpty(m.audioCodec) ? c.getString(R.string.yes) : m.audioCodec)
                    : m.hasAudio == 0 ? c.getString(R.string.no) : null;
            if (audio != null) line(sb, c.getString(R.string.d_audio), audio);
        }
        if (m.frameRate > 0) line(sb, c.getString(R.string.d_fps), String.valueOf(Math.round(m.frameRate)));
        String acc = m.accountLabel();
        if (acc != null) {
            String extra = !TextUtil.isEmpty(m.accountDisplayName) ? m.accountDisplayName + " (@" + m.accountUsername + ")" : acc;
            line(sb, c.getString(R.string.d_account), extra);
        }
        line(sb, c.getString(R.string.d_downloaded), Fmt.dateTime(c, m.downloadedAt));
        line(sb, c.getString(R.string.d_post), m.postUrl);
        if (!TextUtil.isEmpty(m.postText)) line(sb, c.getString(R.string.d_post_text), m.postText);
        line(sb, c.getString(R.string.d_status), availabilityLabel(c, m.availability));
        return sb.toString().trim();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (TextUtil.isEmpty(value)) return;
        sb.append(label).append(": ").append(value).append("\n");
    }

    public static String typeLabel(Context c, String type) {
        if (MediaItem.TYPE_VIDEO.equals(type)) return c.getString(R.string.type_video);
        if (MediaItem.TYPE_GIF.equals(type)) return c.getString(R.string.type_gif);
        return c.getString(R.string.type_image);
    }

    public static String availabilityLabel(Context c, int a) {
        if (a == MediaItem.AVAIL_MISSING) return c.getString(R.string.status_missing);
        if (a == MediaItem.AVAIL_INACCESSIBLE) return c.getString(R.string.status_inaccessible);
        return c.getString(R.string.status_available);
    }
}
