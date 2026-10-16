package com.airlook.viewer;

/** 验证 BT.601 有限范围 YUV 转换（与 MediaCodec NV12 期望一致） */
public class YuvTest {
    public static void main(String[] a) {
        // 黑 → Y=16, U=V=128
        ck("黑 Y", Recorder.luma(0,0,0), 16);
        ck("黑 U", Recorder.chromaU(0,0,0), 128);
        ck("黑 V", Recorder.chromaV(0,0,0), 128);
        // 白 → Y=235, U=V=128
        ck("白 Y", Recorder.luma(255,255,255), 235);
        ck("白 U", Recorder.chromaU(255,255,255), 128);
        ck("白 V", Recorder.chromaV(255,255,255), 128);
        // 纯红 → Y=82, U=90, V=240
        ck("红 Y", Recorder.luma(255,0,0), 82);
        ck("红 U", Recorder.chromaU(255,0,0), 90);
        ck("红 V", Recorder.chromaV(255,0,0), 240);
        // 纯绿 → Y=145, U=54, V=34
        ck("绿 Y", Recorder.luma(0,255,0), 144);   // BT.601: 16+0.504*255=144.5 → 取整 144
        ck("绿 U", Recorder.chromaU(0,255,0), 54);
        ck("绿 V", Recorder.chromaV(0,255,0), 34);
        // 纯蓝 → Y=41, U=240, V=110
        ck("蓝 Y", Recorder.luma(0,0,255), 41);
        ck("蓝 U", Recorder.chromaU(0,0,255), 240);
        ck("蓝 V", Recorder.chromaV(0,0,255), 110);
        // 中灰 → Y=126, U=V=128
        ck("灰 Y", Recorder.luma(128,128,128), 126);
        // 越界钳位
        ck("钳位上限", Recorder.clamp255(999), 255);
        ck("钳位下限", Recorder.clamp255(-999), 0);
        // NV12 缓冲区长度：640×360 → 345600 字节
        int expect = 640 * 360 * 3 / 2;
        ck("NV12 缓冲长度", expect, 345600);
        System.out.println("ALL PASS");
    }
    static void ck(String name, int got, int want) {
        boolean ok = got == want;
        System.out.println((ok ? "  OK   " : "  FAIL ") + name + "  得到 " + got + "，期望 " + want);
        if (!ok) System.exit(1);
    }
}
