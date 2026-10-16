package com.airlook.viewer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** 用真机抓包参数构造测试：total=20632, chunk=1436, type 在 0x01/0x03 间跳 */
public class SelfTest {
    static final int TOTAL = 20632, CHUNK = 1436;
    static int seq = 0;
    static byte[] pkt(int seq, int payloadLen, int type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(24 + payloadLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)0x66).put((byte)type).put((byte)1).put((byte)0);
        b.putInt(TOTAL); b.putShort((short)0).putShort((short)0);
        b.putShort((short)seq).putShort((short)payloadLen);
        b.putInt(0x03b38b79).putInt(0);          // 加速度计 + 保留
        b.put(payload, 0, payloadLen);
        return b.array();
    }
    static byte[] genFrame() {
        byte[] f = new byte[TOTAL];
        f[0]=(byte)0xFF; f[1]=(byte)0xD8; f[2]=(byte)0xFF; f[3]=(byte)0xDB;
        f[TOTAL-2]=(byte)0xFF; f[TOTAL-1]=(byte)0xD9;
        new java.util.Random(7).nextBytes(f);
        f[0]=(byte)0xFF; f[1]=(byte)0xD8; f[TOTAL-2]=(byte)0xFF; f[TOTAL-1]=(byte)0xD9;
        return f;
    }
    public static void main(String[] a) {
        byte[] frame = genFrame();
        FrameAssembler asm = new FrameAssembler();
        byte[] got = null;
        int n = 0, off = 0;
        while (off < TOTAL) {
            int len = Math.min(CHUNK, TOTAL - off);
            // 关键：第 0 片 type=0x01，其余 type=0x03（旧版正是在这里全被判非法）
            byte[] p = pkt(n, len, n == 0 ? 0x01 : 0x03, Arrays.copyOfRange(frame, off, off + len));
            byte[] r = asm.feed(p, p.length);
            if (r != null) got = r;
            off += len; n++;
        }
        check("分片数 = 15（20632/1436 向上取整）", n == 15);
        check("整帧还原一致", got != null && Arrays.equals(got, frame));
        check("未出现丢帧", asm.lastDrop == false);

        // 混入 control 小包不应影响后续组帧
        FrameAssembler asm2 = new FrameAssembler();
        asm2.feed(new byte[]{0x11, 0x22, 0x33}, 3);           // 垃圾包 -> Invalid
        check("垃圾包判为非法", asm2.lastInvalid);
        byte[] frame2 = genFrame();
        byte[] got2 = null; off = 0; n = 0;
        while (off < TOTAL) {
            int len = Math.min(CHUNK, TOTAL - off);
            byte[] p = pkt(n, len, n == 0 ? 0x01 : 0x03, Arrays.copyOfRange(frame2, off, off + len));
            byte[] r = asm2.feed(p, p.length);
            if (r != null) got2 = r;
            off += len; n++;
        }
        check("乱入包后仍能组出整帧", got2 != null && Arrays.equals(got2, frame2));

        // 丢一片 -> 整帧丢弃
        FrameAssembler asm3 = new FrameAssembler();
        byte[] fr3 = genFrame(); off = 0; n = 0; byte[] got3 = null;
        while (off < TOTAL) {
            int len = Math.min(CHUNK, TOTAL - off);
            if (n != 5) {
                byte[] p = pkt(n, len, n == 0 ? 0x01 : 0x03, Arrays.copyOfRange(fr3, off, off + len));
                byte[] r = asm3.feed(p, p.length);
                if (r != null) got3 = r;
            }
            off += len; n++;
        }
        check("丢片后该帧被丢弃", got3 == null);

        System.out.println("ALL PASS");
    }
    static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + name);
        if (!ok) System.exit(1);
    }
}
