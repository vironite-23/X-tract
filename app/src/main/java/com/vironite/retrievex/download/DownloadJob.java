package com.vironite.retrievex.download;

import android.content.Context;
import android.util.Log;

import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.extract.ExtractException;
import com.vironite.retrievex.extract.ExtractorChain;
import com.vironite.retrievex.extract.FormatOption;
import com.vironite.retrievex.extract.FormatPicker;
import com.vironite.retrievex.extract.Http;
import com.vironite.retrievex.extract.HlsSupport;
import com.vironite.retrievex.extract.MediaEntry;
import com.vironite.retrievex.extract.PostInfo;
import com.vironite.retrievex.storage.FileStore;
import com.vironite.retrievex.util.NetUtil;
import com.vironite.retrievex.util.Prefs;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executes one download task: fetch, verify, merge when needed, publish to storage and record in the library. */
final class DownloadJob implements Runnable {
    static final int NONE = 0;
    static final int PAUSE = 1;
    static final int CANCEL = 2;

    private static final String TAG = "RetrieveX";
    private static final long MIN_FREE_MARGIN = 16L * 1024 * 1024;

    /** Failure with a stable code used by the UI and by retry logic. */
    private static final class JobError extends IOException {
        final String code;

        JobError(String code, String message) {
            super(message);
            this.code = code;
        }

        JobError(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    private static final class StopException extends IOException {
        StopException() {
            super("stopped");
        }
    }

    private final DownloadManager mgr;
    private final Context ctx;
    private final DownloadTask task;
    private volatile int stop = NONE;
    private volatile HttpURLConnection conn;

    private FormatOption fmt;
    private boolean refreshed;
    private long baseBytes;
    private long lastNotify;
    private long lastPersist;
    private long speedTime;
    private long speedBytes;

    DownloadJob(DownloadManager mgr, Context ctx, DownloadTask task) {
        this.mgr = mgr;
        this.ctx = ctx;
        this.task = task;
    }

    void requestStop(int why) {
        stop = why;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void checkStop() throws StopException {
        if (stop != NONE) throw new StopException();
    }

    @Override
    public void run() {
        try {
            execute();
        } catch (StopException e) {
            onStopped();
        } catch (Throwable t) {
            if (stop != NONE) onStopped();
            else fail(t);
        } finally {
            mgr.jobFinished(task.id);
        }
    }

    // ------------------------------------------------------------------ main flow

    private void execute() throws Exception {
        if (task.startedAt == 0) task.startedAt = System.currentTimeMillis();
        task.errorMessage = null;
        task.errorCode = null;
        task.speed = 0;
        setStatus(DownloadTask.FETCHING, true);

        if (!NetUtil.isOnline(ctx)) throw new JobError("NETWORK", "No internet connection.");
        fmt = FormatOption.fromJson(task.formatJson);
        if (fmt.url == null) throw new JobError("FORMAT", "The selected format is missing.");

        File dir = FileStore.tempDir(ctx);
        File result = fmt.needsMerge() ? downloadMerged(dir) : downloadProgressive(dir);
        checkStop();

        boolean image = MediaItem.TYPE_IMAGE.equals(task.mediaType);
        Verifier.Probe probe;
        try {
            probe = image ? Verifier.verifyImage(result) : Verifier.verifyVideo(result);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            result.delete();
            throw new JobError("VERIFY", e.getMessage() != null ? e.getMessage() : "The downloaded file is not valid.", e);
        }

        String name = task.targetName;
        String mime = task.mimeType;
        if (image && probe.mime != null) {
            String ext = extFromMime(probe.mime);
            if (ext != null) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                name = base + "." + ext;
                mime = probe.mime;
            }
        }
        if (!image) mime = "video/mp4";

        boolean skip = "skip".equals(Prefs.getString(Prefs.DUPLICATE_MODE, "rename"));
        FileStore.Published pub;
        try {
            pub = FileStore.publish(ctx, result, name, mime, task.mediaType, skip);
        } catch (FileStore.DuplicateException e) {
            //noinspection ResultOfMethodCallIgnored
            result.delete();
            task.tempPath = null;
            task.tempPath2 = null;
            task.note = "skipped";
            task.status = DownloadTask.COMPLETED;
            task.completedAt = System.currentTimeMillis();
            task.speed = 0;
            task.fraction = 1f;
            mgr.notifyTask(task, true);
            return;
        } catch (SecurityException e) {
            throw new JobError("PERMISSION",
                    "Storage permission was denied or revoked. Check the download folder in Settings.", e);
        } catch (IOException e) {
            throw new JobError("FILE", "Couldn't save the file: " + (e.getMessage() != null ? e.getMessage() : "unknown error"), e);
        }

        MediaItem m = new MediaItem();
        m.postId = task.postId;
        m.postUrl = task.postUrl;
        m.filename = pub.name;
        m.fileUri = pub.uri.toString();
        m.mimeType = mime;
        m.mediaType = task.mediaType;
        m.width = probe.width;
        m.height = probe.height;
        m.duration = probe.durationMs > 0 ? probe.durationMs : task.duration;
        m.fileSize = pub.size;
        m.videoCodec = probe.videoCodec;
        m.audioCodec = probe.audioCodec;
        m.frameRate = probe.fps;
        m.hasAudio = image ? -1 : probe.hasAudio;
        m.postText = task.postText;
        m.sourceMetadata = sourceJson();
        long mediaId;
        try {
            mediaId = Repo.get().recordDownload(task, m);
        } catch (Exception e) {
            try {
                FileStore.delete(ctx, pub.uri);
            } catch (Exception ignored) {
                // leave it; the user can remove it manually
            }
            throw new JobError("DATABASE", "Couldn't record the download in the library.", e);
        }

        //noinspection ResultOfMethodCallIgnored
        result.delete();
        task.tempPath = null;
        task.tempPath2 = null;
        task.mediaItemId = mediaId;
        task.destinationUri = pub.uri.toString();
        task.targetName = pub.name;
        task.bytesDownloaded = pub.size;
        task.totalBytes = pub.size;
        task.fraction = 1f;
        task.speed = 0;
        task.completedAt = System.currentTimeMillis();
        task.status = DownloadTask.COMPLETED;
        mgr.notifyTask(task, true);
        Notifications.completed(ctx, task);

        long accountId = Repo.get().accountIdFor(task.accountUsername);
        if (accountId > 0) AvatarCache.cache(ctx, accountId, task.accountAvatar);
    }

    private String sourceJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("formatId", task.formatId);
            o.put("mediaIndex", task.mediaIndex);
            if (task.thumbUrl != null) o.put("thumbUrl", task.thumbUrl);
            if (task.postDate > 0) o.put("postDate", task.postDate);
            if (fmt != null) {
                o.put("bitrate", fmt.bitrate);
                o.put("kind", fmt.kind);
            }
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extFromMime(String mime) {
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/jpeg":
                return "jpg";
            case "image/png":
                return "png";
            case "image/webp":
                return "webp";
            case "image/gif":
                return "gif";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ progressive downloads

    private File downloadProgressive(File dir) throws IOException {
        File part = new File(dir, task.id + ".part");
        task.tempPath = part.getPath();
        task.tempPath2 = null;
        baseBytes = 0;
        try {
            downloadFile(fmt.url, part, true, -1f, 0f);
        } catch (Http.StatusException e) {
            if (isExpired(e.code) && !refreshed) {
                refreshFormat();
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                task.bytesDownloaded = 0;
                downloadFile(fmt.url, part, false, -1f, 0f);
            } else {
                throw e;
            }
        }
        return part;
    }

    private static boolean isExpired(int code) {
        return code == 403 || code == 404 || code == 410;
    }

    /** Re-reads the post to obtain fresh media URLs when the stored ones have expired. */
    private void refreshFormat() throws IOException {
        refreshed = true;
        setStatus(DownloadTask.FETCHING, false);
        try {
            PostInfo info = ExtractorChain.extract(task.postUrl);
            MediaEntry entry = null;
            for (MediaEntry e : info.media) {
                if (e.index == task.mediaIndex) entry = e;
            }
            if (entry == null && task.mediaIndex < info.media.size()) entry = info.media.get(task.mediaIndex);
            if (entry == null || entry.formats.isEmpty()) {
                throw new JobError("EXPIRED", "The media link expired and the post no longer provides this item.");
            }
            FormatOption chosen = null;
            for (FormatOption f : entry.formats) {
                if (f.id != null && f.id.equals(task.formatId)) chosen = f;
            }
            if (chosen == null) {
                int wanted = FormatPicker.quality(fmt);
                chosen = FormatPicker.pick(entry, String.valueOf(wanted), true);
            }
            if (chosen == null) throw new JobError("EXPIRED", "The media link expired and no matching format was found.");
            if (fmt.isImage() && chosen.isImage()) {
                // keep the image format the user asked for
                chosen.url = fmt.url.contains("format=") && chosen.url.contains("format=")
                        ? chosen.url.replaceAll("format=[a-z0-9]+", "format=" + queryParam(fmt.url, "format"))
                        : chosen.url;
            }
            fmt = chosen;
            task.formatJson = chosen.toJson();
        } catch (ExtractException e) {
            throw new JobError("EXTRACT", e.getMessage(), e);
        }
    }

    private static String queryParam(String url, String key) {
        int i = url.indexOf(key + "=");
        if (i < 0) return "";
        int s = i + key.length() + 1;
        int e = url.indexOf('&', s);
        return e < 0 ? url.substring(s) : url.substring(s, e);
    }

    private static long[] parseContentRange(String h) {
        // "bytes 100-999/1000"
        if (h == null) return null;
        try {
            String s = h.trim();
            if (!s.toLowerCase(Locale.ROOT).startsWith("bytes")) return null;
            s = s.substring(5).trim();
            int dash = s.indexOf('-');
            int slash = s.indexOf('/');
            if (dash < 0 || slash < 0) return null;
            long start = Long.parseLong(s.substring(0, dash).trim());
            String t = s.substring(slash + 1).trim();
            long total = "*".equals(t) ? -1 : Long.parseLong(t);
            return new long[]{start, total};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Streams a URL into {@code part}. Resumes with a Range request only when the server confirms it with a
     * matching 206 response; otherwise restarts safely from zero.
     *
     * @param base fraction offset for merged tasks (-1 when progress should come from bytes)
     * @param span fraction span covered by this file
     */
    private void downloadFile(String url, File part, boolean allowResume, float base, float span) throws IOException {
        long existing = (allowResume && part.exists()) ? part.length() : 0;
        if (!allowResume && part.exists()) //noinspection ResultOfMethodCallIgnored
            part.delete();
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Encoding", "identity");
        if (existing > 0) headers.put("Range", "bytes=" + existing + "-");

        checkStop();
        HttpURLConnection c = Http.open(url, headers, Http.CONNECT_TIMEOUT, Http.READ_TIMEOUT);
        conn = c;
        try {
            int code = c.getResponseCode();
            if (code == 416 && existing > 0) {
                c.disconnect();
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                downloadFile(url, part, false, base, span);
                return;
            }
            if (code >= 400) throw new Http.StatusException(code);
            String ctype = c.getContentType();
            if (ctype != null && ctype.toLowerCase(Locale.ROOT).startsWith("text/")) {
                throw new Http.StatusException(410);
            }

            boolean append = false;
            long total;
            if (code == 206) {
                long[] cr = parseContentRange(c.getHeaderField("Content-Range"));
                if (existing > 0 && cr != null && cr[0] == existing) {
                    append = true;
                    total = cr[1] > 0 ? cr[1] : existing + c.getContentLengthLong();
                } else {
                    c.disconnect();
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    downloadFile(url, part, false, base, span);
                    return;
                }
            } else {
                existing = 0; // server ignored the range request (or none was sent): start from the beginning
                total = c.getContentLengthLong();
            }

            long need = total > 0 ? total - existing : 0;
            if (need > 0 && FileStore.freeSpace(part.getParentFile()) < need + MIN_FREE_MARGIN) {
                throw new JobError("STORAGE", "Not enough free storage to download this file.");
            }

            setStatus(DownloadTask.DOWNLOADING, false);
            if (base < 0) task.totalBytes = total;
            long done = existing;
            speedTime = System.currentTimeMillis();
            speedBytes = done;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (stop != NONE) throw new StopException();
                    out.write(buf, 0, n);
                    done += n;
                    progress(done, total, base, span);
                }
            } catch (StopException e) {
                throw e;
            } catch (IOException e) {
                if (stop != NONE) throw new StopException();
                throw e;
            }
            checkStop();
            if (total > 0 && done < total) throw new JobError("INTERRUPTED", "The connection was interrupted before the file finished.");
            progress(done, total, base, span);
        } finally {
            conn = null;
            c.disconnect();
        }
    }

    private void progress(long done, long total, float base, float span) {
        long now = System.currentTimeMillis();
        if (base < 0) {
            task.bytesDownloaded = done;
            task.fraction = -1f;
        } else {
            task.bytesDownloaded = baseBytes + done;
            float part = total > 0 ? Math.min(1f, (float) done / total) : 0f;
            task.fraction = base + span * part;
        }
        if (now - speedTime >= 500) {
            long dt = now - speedTime;
            task.speed = Math.max(0, (done - speedBytes) * 1000L / Math.max(1, dt));
            speedTime = now;
            speedBytes = done;
        }
        if (now - lastNotify >= 300) {
            lastNotify = now;
            boolean save = now - lastPersist >= 2000;
            if (save) lastPersist = now;
            mgr.notifyTask(task, save);
        }
    }

    // ------------------------------------------------------------------ HLS and merging

    private File downloadMerged(File dir) throws IOException {
        File v = new File(dir, task.id + ".v");
        File a = fmt.audioUrl != null ? new File(dir, task.id + ".a") : null;
        File out = new File(dir, task.id + ".mp4");
        task.tempPath = v.getPath();
        task.tempPath2 = a != null ? a.getPath() : null;
        task.bytesDownloaded = 0;
        baseBytes = 0;
        task.totalBytes = fmt.estSize > 0 ? fmt.estSize : -1;
        float videoSpan = a != null ? 0.45f : 0.9f;

        if (fmt.isHls()) {
            downloadHlsTrack(fmt.url, v, 0f, videoSpan);
            if (a != null) downloadHlsTrack(fmt.audioUrl, a, videoSpan, 0.45f);
        } else {
            downloadFile(fmt.url, v, false, 0f, videoSpan);
            if (a != null) {
                baseBytes = v.length();
                downloadFile(fmt.audioUrl, a, false, videoSpan, 0.45f);
            }
        }
        checkStop();

        setStatus(DownloadTask.MERGING, false);
        task.fraction = 0.92f;
        task.speed = 0;
        mgr.notifyTask(task, true);
        try {
            Merger.merge(v, a, out, new Merger.Cancel() {
                @Override
                public boolean isCancelled() {
                    return stop != NONE;
                }
            });
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (stop != NONE) throw new StopException();
            throw new JobError("MERGE", e.getMessage() != null ? e.getMessage() : "Merging failed.", e);
        }
        //noinspection ResultOfMethodCallIgnored
        v.delete();
        if (a != null) //noinspection ResultOfMethodCallIgnored
            a.delete();
        task.tempPath = out.getPath();
        task.tempPath2 = null;
        return out;
    }

    private void downloadHlsTrack(String playlistUrl, File dest, float base, float span) throws IOException {
        String body = Http.getText(playlistUrl, null, 2 * 1024 * 1024);
        HlsSupport.MediaPlaylist pl = HlsSupport.parseMedia(playlistUrl, body);
        if (pl.encrypted) throw new JobError("UNSUPPORTED", "This stream is encrypted and can't be downloaded.");
        if (pl.segments.isEmpty()) throw new JobError("EXTRACT", "The stream playlist contains no segments.");
        List<String> urls = new ArrayList<>();
        if (pl.initSegment != null) urls.add(pl.initSegment);
        urls.addAll(pl.segments);

        if (dest.exists()) //noinspection ResultOfMethodCallIgnored
            dest.delete();
        setStatus(DownloadTask.DOWNLOADING, false);
        speedTime = System.currentTimeMillis();
        speedBytes = 0;
        long written = 0;
        try (FileOutputStream out = new FileOutputStream(dest)) {
            for (int i = 0; i < urls.size(); i++) {
                checkStop();
                byte[] data = fetchSegment(urls.get(i));
                out.write(data);
                written += data.length;
                task.fraction = base + span * ((i + 1f) / urls.size());
                task.bytesDownloaded = baseBytes + written;
                long now = System.currentTimeMillis();
                if (now - speedTime >= 500) {
                    task.speed = Math.max(0, (written - speedBytes) * 1000L / Math.max(1, now - speedTime));
                    speedTime = now;
                    speedBytes = written;
                }
                if (now - lastNotify >= 300) {
                    lastNotify = now;
                    mgr.notifyTask(task, false);
                }
            }
        }
        baseBytes += written;
    }

    private byte[] fetchSegment(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            checkStop();
            HttpURLConnection c = null;
            try {
                Map<String, String> h = new HashMap<>();
                h.put("Accept-Encoding", "identity");
                c = Http.open(url, h, Http.CONNECT_TIMEOUT, Http.READ_TIMEOUT);
                conn = c;
                int code = c.getResponseCode();
                if (code >= 400) throw new Http.StatusException(code);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (stop != NONE) throw new StopException();
                        bos.write(buf, 0, n);
                    }
                }
                return bos.toByteArray();
            } catch (StopException e) {
                throw e;
            } catch (Http.StatusException e) {
                throw e;
            } catch (IOException e) {
                if (stop != NONE) throw new StopException();
                last = e;
            } finally {
                conn = null;
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new IOException("Segment download failed");
    }

    // ------------------------------------------------------------------ state changes

    private void setStatus(int status, boolean save) {
        task.status = status;
        mgr.notifyTask(task, save);
    }

    private void onStopped() {
        task.speed = 0;
        if (stop == CANCEL) {
            mgr.deleteTemps(task);
            task.bytesDownloaded = 0;
            task.fraction = -1f;
            task.status = DownloadTask.CANCELED;
        } else {
            task.status = DownloadTask.PAUSED;
            task.errorMessage = null;
        }
        mgr.notifyTask(task, true);
    }

    private void fail(Throwable t) {
        String code;
        String msg;
        if (t instanceof JobError) {
            code = ((JobError) t).code;
            msg = t.getMessage();
        } else if (t instanceof Http.StatusException) {
            int http = ((Http.StatusException) t).code;
            if (isExpired(http)) {
                code = "EXPIRED";
                msg = "The media link expired or is no longer available.";
            } else {
                code = "HTTP";
                msg = "The server responded with an error (" + http + ").";
            }
        } else if (t instanceof SocketTimeoutException) {
            code = "TIMEOUT";
            msg = "The connection timed out.";
        } else if (t instanceof UnknownHostException || t instanceof ConnectException) {
            code = "NETWORK";
            msg = "Connection lost. Check your internet connection.";
        } else if (t instanceof SecurityException) {
            code = "PERMISSION";
            msg = "Storage permission was denied or revoked.";
        } else if (t instanceof IOException && t.getMessage() != null
                && (t.getMessage().contains("ENOSPC") || t.getMessage().toLowerCase(Locale.ROOT).contains("no space"))) {
            code = "STORAGE";
            msg = "Not enough free storage.";
        } else if (t instanceof IOException) {
            code = "NETWORK";
            msg = "The download was interrupted: " + (t.getMessage() != null ? t.getMessage() : "network error");
        } else {
            code = "ERROR";
            msg = "Unexpected error: " + t.getClass().getSimpleName();
        }
        Log.w(TAG, "Download failed (" + code + ")", t);
        task.errorCode = code;
        task.errorMessage = msg;
        task.speed = 0;
        task.status = DownloadTask.FAILED;
        if ("VERIFY".equals(code) || "MERGE".equals(code) || "FILE".equals(code) || "UNSUPPORTED".equals(code)
                || "FORMAT".equals(code)) {
            mgr.deleteTemps(task);
        }
        mgr.notifyTask(task, true);
        Notifications.failed(ctx, task);
    }
}
