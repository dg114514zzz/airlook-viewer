package com.airlook.viewer;

/**
 * 把 Xylla 摄像头帧头里打包的加速度计读数（3×10 bit，位于每个分片头的 offset 16）
 * 换算成"画面需要旋转多少度"。
 *
 * 算法与上游开源实现（rbeilvert/otoscope 的 stream/xylla/RotationFilter.kt）逐行一致，
 * 因此其官方单测的向量可直接拿来验证本实现（见 RotationFilterSelfTest）。
 *
 * 抖动抑制：只有最近 20 个采样里任一轴标准差 > 3 时才更新角度；设备静止时保持上一次角度，
 * 避免手稳的时候画面来回漂移。
 */
final class RotationFilter {

    static final int WINDOW = 20;

    private final int[] xs = new int[WINDOW];
    private final int[] ys = new int[WINDOW];
    private final int[] zs = new int[WINDOW];
    private int count = 0;   // 已写入的采样数，最多 WINDOW
    private int head = 0;    // 下一个写入位置
    private float lastAngle = 0f;

    /** 传入帧头 offset 16 的 u32 打包值，返回角度（度，[0,360)）。 */
    float update(int accelerometer) {
        int regX = (accelerometer >>> 20) & 0x3FF;
        int regY = (accelerometer >>> 10) & 0x3FF;
        int regZ = accelerometer & 0x3FF;

        // 每轴 0..1023，512 为零点。以 512 为轴做镜像，得到 0..512 的幅值。
        int magX = regX >= 512 ? 1024 - regX : regX;
        int magY = regY >= 512 ? 1024 - regY : regY;
        int magZ = regZ >= 512 ? 1024 - regZ : regZ;

        push(xs, magX);
        push(ys, magY);
        push(zs, magZ);
        head = (head + 1) % WINDOW;
        if (count < WINDOW) count++;

        if (count < WINDOW) return lastAngle;

        boolean moving = stddev(xs) > 3f || stddev(ys) > 3f || stddev(zs) > 3f;
        if (!moving) return lastAngle;

        if (magZ == 0) return lastAngle;

        double theta = Math.atan((double) magY / (double) magZ);
        if (regZ > 512) theta = Math.PI - theta;
        if (regY > 512) theta = 2.0 * Math.PI - theta;

        // 镜头相对加速度计的参考系装了 180°，不加这个偏移画面就是倒的。
        theta = (theta + Math.PI) % (2.0 * Math.PI);

        lastAngle = (float) Math.toDegrees(theta);
        return lastAngle;
    }

    void reset() {
        count = 0;
        head = 0;
        lastAngle = 0f;
    }

    private void push(int[] buf, int v) {
        buf[head] = v;
    }

    private static float stddev(int[] buf) {
        double sum = 0;
        for (int i = 0; i < WINDOW; i++) sum += buf[i];
        double mean = sum / WINDOW;
        double sq = 0;
        for (int i = 0; i < WINDOW; i++) {
            double d = buf[i] - mean;
            sq += d * d;
        }
        return (float) Math.sqrt(sq / WINDOW);
    }
}
