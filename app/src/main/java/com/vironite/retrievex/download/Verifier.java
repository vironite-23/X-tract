package com.vironite.retrievex.download;

import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.IOException;

/** Verifies finished downloads and extracts technical metadata from the actual file. */
public final class Verifier {
    public static final class Probe {
        public int width;
        public int height;
        public long durationMs;
        public String videoCodec;
        public String audioCodec;
        public int hasAudio = -1;
        public float fps;
        public String mime;
    }

    private Verifier() {
    }

    public static Probe verifyImage(File f) throws IOException {
        if (!f.exists() || f.length() == 0) throw new IOException("The downloaded file is empty.");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("The downloaded file is not a valid image.");
        Probe p = new Probe();
        p.width = o.outWidth;
        p.height = o.outHeight;
        p.mime = o.outMimeType;
        return p;
    }

    public static Probe verifyVideo(File f) throws IOException {
        if (!f.exists() || f.length() == 0) throw new IOException("The downloaded file is empty.");
        Probe p = new Probe();
        MediaExtractor ex = new MediaExtractor();
        boolean hasVideo = false;
        boolean hasAudio = false;
        try {
            ex.setDataSource(f.getPath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && !hasVideo) {
                    hasVideo = true;
                    p.videoCodec = videoName(mime);
                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            p.fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE);
                        } catch (Exception e) {
                            try {
                                p.fps = fmt.getFloat(MediaFormat.KEY_FRAME_RATE);
                            } catch (Exception ignored) {
                                p.fps = 0;
                            }
                        }
                    }
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true;
                    p.audioCodec = audioName(mime);
                }
            }
        } catch (Exception e) {
            throw new IOException("The downloaded file is not a valid video.", e);
        } finally {
            ex.release();
        }
        if (!hasVideo) throw new IOException("The downloaded file contains no video track.");
        p.hasAudio = hasAudio ? 1 : 0;

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            p.width = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            p.height = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            p.durationMs = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int rot = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) {
                int t = p.width;
                p.width = p.height;
                p.height = t;
            }
        } catch (Exception ignored) {
            // metadata is best effort; the track check above already validated the file
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
        p.mime = "video/mp4";
        return p;
    }

    private static int parse(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String videoName(String mime) {
        switch (mime) {
            case "video/avc":
                return "H.264";
            case "video/hevc":
                return "H.265";
            case "video/av01":
                return "AV1";
            case "video/x-vnd.on2.vp9":
                return "VP9";
            default:
                return mime.substring(mime.indexOf('/') + 1);
        }
    }

    public static String audioName(String mime) {
        switch (mime) {
            case "audio/mp4a-latm":
                return "AAC";
            case "audio/opus":
                return "Opus";
            case "audio/mpeg":
                return "MP3";
            default:
                return mime.substring(mime.indexOf('/') + 1);
        }
    }
}
