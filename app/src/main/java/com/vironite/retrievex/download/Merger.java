package com.vironite.retrievex.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines a video file and an optional separate audio file into one MP4 by copying the encoded
 * samples (no re-encoding, so there is no quality loss and timestamps keep audio in sync).
 * Uses only the platform MediaExtractor/MediaMuxer, so no extra native libraries are required.
 */
public final class Merger {
    public static class MergeException extends IOException {
        public MergeException(String message, Throwable cause) {
            super(message, cause);
        }

        public MergeException(String message) {
            super(message);
        }
    }

    public interface Cancel {
        boolean isCancelled();
    }

    private Merger() {
    }

    private static final class Source {
        MediaExtractor extractor = new MediaExtractor();
        final Map<Integer, Integer> trackMap = new HashMap<>();
    }

    public static void merge(File video, File audio, File out, Cancel cancel) throws IOException {
        MediaMuxer muxer = null;
        List<Source> sources = new ArrayList<>();
        boolean started = false;
        try {
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            boolean haveVideo = false;
            boolean haveAudio = false;

            Source vs = new Source();
            sources.add(vs);
            vs.extractor.setDataSource(video.getPath());
            for (int i = 0; i < vs.extractor.getTrackCount(); i++) {
                MediaFormat f = vs.extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                boolean isVideo = mime.startsWith("video/");
                boolean isAudio = mime.startsWith("audio/");
                if (isVideo && !haveVideo) {
                    vs.trackMap.put(i, muxer.addTrack(f));
                    haveVideo = true;
                } else if (isAudio && audio == null && !haveAudio) {
                    vs.trackMap.put(i, muxer.addTrack(f));
                    haveAudio = true;
                }
            }
            if (!haveVideo) throw new MergeException("The video stream has no video track.");

            if (audio != null) {
                Source as = new Source();
                sources.add(as);
                as.extractor.setDataSource(audio.getPath());
                for (int i = 0; i < as.extractor.getTrackCount(); i++) {
                    MediaFormat f = as.extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/") && !haveAudio) {
                        as.trackMap.put(i, muxer.addTrack(f));
                        haveAudio = true;
                    }
                }
                if (!haveAudio) throw new MergeException("The audio stream has no audio track.");
            }

            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(video.getPath());
                String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rot != null) muxer.setOrientationHint(Integer.parseInt(rot));
            } catch (Exception ignored) {
                // orientation is optional
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                    // ignore
                }
            }

            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (Source s : sources) {
                for (Integer src : s.trackMap.keySet()) s.extractor.selectTrack(src);
                while (true) {
                    if (cancel != null && cancel.isCancelled()) throw new IOException("cancelled");
                    int idx = s.extractor.getSampleTrackIndex();
                    if (idx < 0) break;
                    Integer dst = s.trackMap.get(idx);
                    buffer.clear();
                    int size = s.extractor.readSampleData(buffer, 0);
                    if (size < 0) break;
                    if (dst != null) {
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = s.extractor.getSampleTime();
                        info.flags = (s.extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        muxer.writeSampleData(dst, buffer, info);
                    }
                    s.extractor.advance();
                }
            }
        } catch (MergeException e) {
            throw e;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new MergeException("This device can't merge the selected streams (unsupported codec or container).", e);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MergeException("Merging failed: " + e.getMessage(), e);
        } finally {
            for (Source s : sources) {
                try {
                    s.extractor.release();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Exception ignored) {
                    // a failed stop means the output is unusable; the caller verifies it
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }
}
