package com.airlook.viewer;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 录像引擎：把实时画面编码为 H.264/MP4。
 *
 * 纯系统 API（MediaCodec + MediaMuxer），无第三方库、不录声音、不需要额外权限。
 * 画面从 1280×720 降到 640×360 再编码，保证帧率稳定。
 */
final class Recorder {

    interface FrameSource { Bitmap latest(); }

    interface Listener {
        /** 编码失败（初始化或运行中）。 */
        void onError(String msg);
        /** 录制结束。keep=true 表示应保存到相册，false 表示丢弃。回调在后台线程。 */
        void onDone(File file, boolean keep);
    }

    static final int W = 640;
    static final int H = 360;
    static final int FPS = 12;
    static final int BITRATE = 2_000_000;

    private final File cacheDir;
    private final FrameSource source;
    private final Listener listener;

    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private volatile boolean discard = false;
    private volatile long framesWritten = 0;
    private volatile long startMs = 0;
    private volatile String error = null;
    private Thread thread;
    private File tmpFile;

    Recorder(File cacheDir, FrameSource source, Listener listener) {
        this.cacheDir = cacheDir;
        this.source = source;
        this.listener = listener;
    }

    boolean isRecording() { return running; }
    long framesWritten() { return framesWritten; }
    String lastError() { return error; }

    long elapsedMs() {
        return running ? System.currentTimeMillis() - startMs : 0;
    }

    /** 开始录制。 */
    void start() {
        if (running) return;
        tmpFile = new File(cacheDir, "rec_" + System.currentTimeMillis() + ".mp4");
        discard = false;
        stopRequested = false;
        framesWritten = 0;
        error = null;
        startMs = System.currentTimeMillis();
        running = true;
        thread = new Thread(this::loop, "recorder");
        thread.start();
    }

    /** 正常结束并保存到相册。 */
    void stopAndSave() {
        discard = false;
        stopRequested = true;
    }

    /** 中断并丢弃（例如用户退出 App）。 */
    void abort() {
        discard = true;
        stopRequested = true;
    }

    // ---------------- 编码主循环 ----------------

    private void loop() {
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        int track = -1;
        boolean muxStarted = false;
        final byte[] yuv = new byte[W * H * 3 / 2];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        try {
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", W, H);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            // 逐个尝试色彩格式：不同厂商的编码器支持度不同（NV12 → Flexible → I420）
            final int[] colorFormats = {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            };
            int chosen = 0;
            for (int cf : colorFormats) {
                MediaCodec c = null;
                try {
                    fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, cf);
                    c = MediaCodec.createEncoderByType("video/avc");
                    c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    codec = c;
                    chosen = cf;
                    break;
                } catch (Throwable t) {
                    try { if (c != null) c.release(); } catch (Throwable ignore) {}
                }
            }
            if (codec == null) throw new RuntimeException("编码器不接受任何 YUV420 色彩格式");
            boolean planar = (chosen == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            error = null;
            codec.start();

            muxer = new MediaMuxer(tmpFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            long nextFrameAt = System.currentTimeMillis();
            long lastPts = -1;

            // 复用的缩放画布，避免每帧新建 Bitmap（12fps 下能省下约 11MB/秒 的垃圾内存）
            final Bitmap scratch = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            final android.graphics.Canvas canvas = new android.graphics.Canvas(scratch);
            final android.graphics.Paint paint = new android.graphics.Paint();
            paint.setFilterBitmap(true);
            final android.graphics.Rect srcRect = new android.graphics.Rect();
            final android.graphics.Rect dstRect = new android.graphics.Rect(0, 0, W, H);

            while (true) {
                boolean stop = stopRequested;
                long now = System.currentTimeMillis();

                if (!stop && now >= nextFrameAt) {
                    nextFrameAt += 1000 / FPS;
                    if (nextFrameAt < now) nextFrameAt = now + 1000 / FPS;

                    Bitmap bm = source.latest();
                    if (bm != null && !bm.isRecycled()) {
                        Bitmap target;
                        if (bm.getWidth() == W && bm.getHeight() == H) {
                            target = bm;
                        } else {
                            srcRect.set(0, 0, bm.getWidth(), bm.getHeight());
                            canvas.drawBitmap(bm, srcRect, dstRect, paint);
                            target = scratch;
                        }
                        if (planar) toI420(target, yuv); else toNv12(target, yuv);

                        int idx = codec.dequeueInputBuffer(5000);
                        if (idx >= 0) {
                            ByteBuffer buf = codec.getInputBuffer(idx);
                            if (buf != null) {
                                buf.clear();
                                buf.put(yuv);
                                long pts = Math.max((now - startMs) * 1000L, lastPts + 1);
                                lastPts = pts;
                                codec.queueInputBuffer(idx, 0, yuv.length, pts, 0);
                                framesWritten++;
                            }
                        }
                    }
                }

                int outIdx;
                while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted && ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        muxer.writeSampleData(track, ob, info);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxStarted) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                }

                if (stop) {
                    // 送出结束标记，等编码器把剩余数据吐完
                    int idx = codec.dequeueInputBuffer(20000);
                    if (idx >= 0) {
                        long pts = Math.max((System.currentTimeMillis() - startMs) * 1000L, lastPts + 1);
                        codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    long deadline = System.currentTimeMillis() + 800;
                    while (System.currentTimeMillis() < deadline) {
                        int oi = codec.dequeueOutputBuffer(info, 100);
                        if (oi >= 0) {
                            ByteBuffer ob = codec.getOutputBuffer(oi);
                            if (info.size > 0 && muxStarted && ob != null) {
                                ob.position(info.offset);
                                ob.limit(info.offset + info.size);
                                muxer.writeSampleData(track, ob, info);
                            }
                            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            codec.releaseOutputBuffer(oi, false);
                            if (eos) break;
                        } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // 继续等
                        }
                    }
                    break;
                }

                if (!stop && System.currentTimeMillis() < nextFrameAt) sleep(4);
            }
        } catch (Throwable t) {
            error = String.valueOf(t);
            discard = true;
            listener.onError("录制失败：" + t);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignore) {}
            try { if (muxer != null) { if (muxStarted) muxer.stop(); muxer.release(); } } catch (Throwable ignore) {}
            running = false;
            File f = tmpFile;
            // 只有真正把帧写进容器才算成功。否则（例如点开始后立刻点停止）MediaMuxer 也会留下
            // 一个只有文件头、无法播放的空 mp4，不能把它当成"录制成功"塞进相册。
            boolean ok = f != null && !discard && muxStarted && framesWritten > 0 && f.length() > 0;
            if (f != null) listener.onDone(f, ok);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    // ---------------- RGB → NV12 ----------------

    static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    static int luma(int r, int g, int b) {
        return clamp255((((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
    }

    static int chromaU(int r, int g, int b) {
        return clamp255((((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
    }

    static int chromaV(int r, int g, int b) {
        return clamp255(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
    }

    /** 备选：I420（Y 平面 + U 平面 + V 平面），部分编码器只接受这种布局。 */
    static void toI420(Bitmap bm, byte[] out) {
        final int w = bm.getWidth(), h = bm.getHeight();
        final int[] row = new int[w];
        int yIdx = 0;
        int uIdx = w * h;
        int vIdx = w * h + w * h / 4;
        for (int j = 0; j < h; j++) {
            bm.getPixels(row, 0, w, 0, j, w, 1);
            for (int i = 0; i < w; i++) {
                int c = row[i];
                out[yIdx++] = (byte) luma((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            }
            if ((j & 1) == 0) {
                for (int i = 0; i + 1 < w; i += 2) {
                    int c0 = row[i], c1 = row[i + 1];
                    int r = (((c0 >> 16) & 0xFF) + ((c1 >> 16) & 0xFF)) >> 1;
                    int g = (((c0 >> 8) & 0xFF) + ((c1 >> 8) & 0xFF)) >> 1;
                    int b = ((c0 & 0xFF) + (c1 & 0xFF)) >> 1;
                    out[uIdx++] = (byte) chromaU(r, g, b);
                    out[vIdx++] = (byte) chromaV(r, g, b);
                }
            }
        }
    }

    /** 把 ARGB 位图转成 NV12（Y 平面 + 交错 UV 平面）。 */
    static void toNv12(Bitmap bm, byte[] out) {
        final int w = bm.getWidth(), h = bm.getHeight();
        final int[] row = new int[w];
        int yIdx = 0;
        int uvIdx = w * h;
        for (int j = 0; j < h; j++) {
            bm.getPixels(row, 0, w, 0, j, w, 1);
            for (int i = 0; i < w; i++) {
                int c = row[i];
                out[yIdx++] = (byte) luma((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            }
            if ((j & 1) == 0) {
                for (int i = 0; i + 1 < w; i += 2) {
                    int c0 = row[i], c1 = row[i + 1];
                    int r = (((c0 >> 16) & 0xFF) + ((c1 >> 16) & 0xFF)) >> 1;
                    int g = (((c0 >> 8) & 0xFF) + ((c1 >> 8) & 0xFF)) >> 1;
                    int b = ((c0 & 0xFF) + (c1 & 0xFF)) >> 1;
                    out[uvIdx++] = (byte) chromaU(r, g, b);
                    out[uvIdx++] = (byte) chromaV(r, g, b);
                }
            }
        }
    }
}
