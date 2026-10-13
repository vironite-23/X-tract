package com.vironite.retrievex.extract;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Looks up the real file size of every format of a post, so the preview shows what will actually be
 * saved instead of a bitrate x duration guess. Progressive files and images are measured with a HEAD
 * request (the same bytes the download receives); HLS streams are measured by summing their segments.
 * Everything is best effort: a format that cannot be measured keeps its estimate.
 */
public final class SizeProbe {
    private static final int TOTAL_BUDGET_MS = 10000;
    private static final int REQUEST_MS = 6000;
    private static final int MAX_HLS_SEGMENTS = 60;

    private SizeProbe() {
    }

    /**
     * Fills in sizes for all formats of the post.
     *
     * @param imagePref the image format preference (original, jpg, png, webp) that images will be saved in
     */
    public static void resolve(PostInfo info, final String imagePref) {
        if (info == null) return;
        List<Callable<Void>> jobs = new ArrayList<>();
        for (MediaEntry e : info.media) {
            for (final FormatOption f : e.formats) {
                final boolean image = e.type != null && e.type.equals(MediaEntry.TYPE_IMAGE);
                jobs.add(new Callable<Void>() {
                    @Override
                    public Void call() {
                        try {
                            if (image) probeImage(f, imagePref);
                            else if (f.needsMerge()) probeHls(f);
                            else probeProgressive(f);
                        } catch (Exception ignored) {
                            // keep the estimate
                        }
                        return null;
                    }
                });
            }
        }
        if (jobs.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, jobs.size()));
        try {
            pool.invokeAll(jobs, TOTAL_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void probeImage(FormatOption f, String pref) {
        String key = pref == null ? "original" : pref;
        FormatOption target = FormatPicker.imageAs(f, key);
        long size = contentLength(target.url);
        if (size > 0) {
            f.estSize = size;
            f.sizeExact = true;
            f.sizeFor = key;
        }
    }

    private static void probeProgressive(FormatOption f) {
        long size = contentLength(f.url);
        if (size > 0) {
            f.estSize = size;
            f.sizeExact = true;
        }
    }

    /** Sum of the real segment sizes. Not marked exact because remuxing to MP4 changes the total slightly. */
    private static void probeHls(FormatOption f) throws IOException {
        long total = trackSize(f.url);
        if (total <= 0) return;
        if (f.audioUrl != null) {
            long audio = trackSize(f.audioUrl);
            if (audio <= 0) return;
            total += audio;
        }
        f.estSize = total;
        f.sizeExact = false;
    }

    private static long trackSize(String playlistUrl) throws IOException {
        String body = Http.getText(playlistUrl, null, 2 * 1024 * 1024);
        HlsSupport.MediaPlaylist pl = HlsSupport.parseMedia(playlistUrl, body);
        if (pl.encrypted) return -1;
        List<String> urls = new ArrayList<>();
        if (pl.initSegment != null) urls.add(pl.initSegment);
        urls.addAll(pl.segments);
        if (urls.isEmpty() || urls.size() > MAX_HLS_SEGMENTS) return -1;
        long sum = 0;
        for (String u : urls) {
            if (Thread.currentThread().isInterrupted()) return -1;
            long n = contentLength(u);
            if (n <= 0) return -1;
            sum += n;
        }
        return sum;
    }

    /** Size in bytes of the resource as it will be downloaded, or -1 when the server does not say. */
    static long contentLength(String url) {
        if (url == null) return -1;
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Encoding", "identity");
        HttpURLConnection c = null;
        try {
            c = Http.open(url, h, REQUEST_MS, REQUEST_MS, "HEAD");
            if (c.getResponseCode() < 400) {
                long n = c.getContentLengthLong();
                if (n > 0) return n;
            }
        } catch (IOException ignored) {
            // fall through to the ranged request
        } finally {
            if (c != null) c.disconnect();
        }
        c = null;
        try {
            Map<String, String> r = new HashMap<>(h);
            r.put("Range", "bytes=0-0");
            c = Http.open(url, r, REQUEST_MS, REQUEST_MS);
            int code = c.getResponseCode();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) {
                        String t = cr.substring(slash + 1).trim();
                        if (!"*".equals(t)) return Long.parseLong(t);
                    }
                }
            } else if (code < 400) {
                return c.getContentLengthLong();
            }
        } catch (IOException | NumberFormatException ignored) {
            // unknown
        } finally {
            if (c != null) c.disconnect();
        }
        return -1;
    }
}
