package com.airlook.viewer;

/**
 * 极端验证：直接照搬上游 rbeilvert/otoscope 官方 RotationFilterTest 的向量，
 * 确认本 Java 移植与 Kotlin 原版行为一致。
 */
public class RotTest {

    static int packAxes(int x, int y, int z) {
        return ((x & 0x3FF) << 20) | ((y & 0x3FF) << 10) | (z & 0x3FF);
    }

    public static void main(String[] a) {
        // 1) 窗口未填满（<20 采样）前一律保持 0
        RotationFilter f1 = new RotationFilter();
        int steady = packAxes(100, 0, 300);
        for (int i = 0; i < 19; i++) {
            if (f1.update(steady) != 0f) { fail("窗口未满应返回 0，第 " + i + " 次就不对"); }
        }
        ok("窗口未满（19 次）始终返回 0");

        // 2) 完全静止的输入：窗口填满后也不产生新角度
        RotationFilter f2 = new RotationFilter();
        int still = packAxes(200, 100, 400);
        for (int i = 0; i < 30; i++) f2.update(still);
        near("静止时保持 0", f2.update(still), 0f, 0.001f);

        // 3) 镜头水平朝上（Y≈0，Z>0，X=200）→ 180°（含 180° 安装偏移）
        RotationFilter f3 = new RotationFilter();
        int[] moving = {50,100,150,200,250,300,350,400,450,500, 50,100,150,200,250,300,350,400,450,500};
        for (int raw : moving) f3.update(packAxes(raw, raw, 400));
        float ang = f3.update(packAxes(200, 0, 400));
        near("Y=0/Z>0 姿态 → 180°", ang, 180f, 0.5f);

        // 4) 只有单轴极小抖动（标准差 < 3）→ 不应解除"静止"锁定
        RotationFilter f4 = new RotationFilter();
        int s4 = packAxes(100, 100, 400);
        for (int i = 0; i < 30; i++) f4.update(s4);
        int[] deltas = {1, -1, 0, 1, -1, 0};
        boolean allZero = true;
        for (int d : deltas) if (f4.update(packAxes(100 + d, 100, 400)) != 0f) allZero = false;
        if (!allZero) fail("微小抖动不应解除静止锁定");
        ok("微小抖动时保持 0");

        // 5) 补充：把 10bit 打包/解包还原性验证一遍（X 在高位、Z 在低位）
        int packed = packAxes(889, 227, 59);
        if (((packed >>> 20) & 0x3FF) != 889 || ((packed >>> 10) & 0x3FF) != 227 || (packed & 0x3FF) != 59)
            fail("位域布局不符");
        ok("位域布局 X=高10位 / Y=中10位 / Z=低10位");

        // 6) 补充：真机实测值应落在合理角度区间（不崩、不发散）
        RotationFilter f6 = new RotationFilter();
        int real = 0x03b38b79;   // 用户截图里的真实读数
        float last = 0;
        for (int i = 0; i < 25; i++) last = f6.update(real + (i % 7) * 13);
        if (!(last >= 0f && last < 360f)) fail("角度越界：" + last);
        if (Float.isNaN(last)) fail("角度为 NaN");
        ok(String.format("真机读数解算角度在 [0,360) 内：%.1f°", last));

        System.out.println("ALL PASS");
    }

    static void ok(String name) { System.out.println("  OK   " + name); }
    static void near(String name, float got, float want, float tol) {
        if (Math.abs(got - want) > tol) fail(name + "：得到 " + got + "，期望 " + want);
        System.out.println("  OK   " + name + "  (" + got + ")");
    }
    static void fail(String msg) { System.out.println("  FAIL " + msg); System.exit(1); }
}
