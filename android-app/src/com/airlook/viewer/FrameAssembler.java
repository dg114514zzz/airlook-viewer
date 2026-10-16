package com.airlook.viewer;

/**
 * 帧组装：24 字节头 + 分片负载。
 *
 * 头部布局（与上游开源实现 otoscope/xylla FrameAssembler 完全一致）：
 *   offset  size  field
 *      0     u8   magic 0x66（仅此字节参与校验）
 *      1     u8   type，0x01 / 0x03，不参与校验
 *      2     u8   format（1 = MJPEG，4 = YUYV）
 *      3     u8   flag，忽略
 *      4-7   u32  整帧字节数（little-endian）
 *      8-9   u16  宽
 *     10-11  u16  高
 *     12-13  u16  分片序号（每帧从 0 开始）
 *     14-15  u16  本包负载字节数
 *     16-19  u32  加速度计打包值
 *     20-23  u32  保留
 *     24..       负载
 */
final class FrameAssembler {

    static final int HEADER_SIZE = 24;
    static final int MAX_FRAME_SIZE = 8 * 1024 * 1024;

    private byte[] buffer = null;
    private int expectedSize = 0, accumulated = 0, lastChunkSeq = -1;

    int format, width, height, accelerometer;
    /** 本包是否导致整帧被丢弃（跳号 / 越界）。 */
    boolean lastDrop = false;
    /** 本包是否无法解析（太短 / magic 不符 / 长度字段非法）。 */
    boolean lastInvalid = false;

    void reset() {
        buffer = null;
        expectedSize = 0;
        accumulated = 0;
        lastChunkSeq = -1;
    }

    /** 返回完整帧字节，未完成返回 null。调用后请检查 lastDrop / lastInvalid。 */
    byte[] feed(byte[] p, int len) {
        lastDrop = false;
        lastInvalid = false;

        if (len < HEADER_SIZE) { lastInvalid = true; return null; }
        if ((p[0] & 0xFF) != 0x66) { lastInvalid = true; return null; }

        int totalSize = u32(p, 4);
        int chunkSeq = u16(p, 12);
        int chunkLen = u16(p, 14);

        if (chunkLen <= 0 || chunkLen + HEADER_SIZE > len) { lastInvalid = true; return null; }
        if (totalSize <= 0 || totalSize > MAX_FRAME_SIZE) { lastInvalid = true; return null; }

        // 新帧：缓冲为空、帧长变了、序号回退、或序号为 0
        boolean isNewFrame = buffer == null || totalSize != expectedSize
                || chunkSeq < lastChunkSeq || chunkSeq == 0;
        if (isNewFrame) {
            buffer = new byte[totalSize];
            expectedSize = totalSize;
            accumulated = 0;
            lastChunkSeq = -1;
            format = p[2] & 0xFF;
            width = u16(p, 8);
            height = u16(p, 10);
        }

        if (chunkSeq != lastChunkSeq + 1) { reset(); lastDrop = true; return null; }
        if (accumulated + chunkLen > expectedSize) { reset(); lastDrop = true; return null; }

        System.arraycopy(p, HEADER_SIZE, buffer, accumulated, chunkLen);
        accumulated += chunkLen;
        lastChunkSeq = chunkSeq;
        accelerometer = u32(p, 16);

        if (accumulated == expectedSize) {
            byte[] out = buffer;
            reset();
            return out;
        }
        return null;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
