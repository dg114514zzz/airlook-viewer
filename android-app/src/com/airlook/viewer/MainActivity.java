package com.airlook.viewer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 耳镜直连 —— 实时画面 + 画面增强（亮度/对比度）。
 * 协议：UDP 8032，启动包 99 99 01 00 + 20 零字节，数据帧 24 字节头 + 分片 JPEG。
 */
public class MainActivity extends Activity {

    private static final int VIDEO_PORT = 8032;
    private static final byte[] START_CMD = new byte[24];
    static {
        START_CMD[0] = (byte) 0x99;
        START_CMD[1] = (byte) 0x99;
        START_CMD[2] = 0x01;
        START_CMD[3] = 0x00;
    }

    private ImageView preview;
    private TextView status;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean uiLoopActive = new AtomicBoolean(false);
    private final AtomicReference<Bitmap> latestFrame = new AtomicReference<>(null);

    private volatile long packets = 0, frames = 0, dropped = 0, decErrs = 0, invalid = 0;
    private volatile long lastUiUpdate = 0;
    private volatile int lastW = 0, lastH = 0;

    // 诊断信息（一旦采集到就固定显示，不再被统计行覆盖）
    private volatile String diagFirst = null;   // 首包 hex
    private volatile String diagPeer = null;    // 数据来源
    private final java.util.Map<Integer, Integer> sizeHist = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_DIAG_SAMPLES = 4;
    private final java.util.List<String> diagHeads = new ArrayList<>(); // 前 4 个包的头 24 字节

    // 画面增强
    private SeekBar seekBright, seekContrast;
    private TextView vBright, vContrast;
    private int brightPct = 100, contrastPct = 100;
    private SharedPreferences cfg;

    // 灯光：实测该硬件只有开/关，无调光能力，故做成开关
    private static final int CTRL_PORT = 50000;
    private static final int CMD_SET_PWM = 0x1016;   // 报文第 3-4 字节: 16 10
    /**
     * 灯光开关的取值。**开灯必须用小数值**：真机实测 0=灭、1/2/3=满亮，
     * 而 1000 这样的大值会被固件忽略——症状就是"关掉之后再也打不开"。
     * （数值字段位于报文偏移 8、32 位小端，已用反汇编 sendSetPWM 核对确认。）
     */
    private static final int LED_ON_VALUE = 1;
    private static final int LED_OFF_VALUE = 0;
    /** 圆形取景的过扫描系数：留 2% 余量，避免图像边缘与圆相切时露出发丝黑边。 */
    private static final float CIRCLE_OVERSCAN = 1.02f;
    private TextView ledToggle;
    private TextView hint;
    private TextView console;

    // 录像
    private Recorder recorder;
    private android.widget.Button recBtn, snapBtn;
    private android.view.View recIndicator;
    private TextView recTime, recInfo, disconnectView;
    private volatile long lastFrameAt = 0;
    private volatile boolean disconnectShown = false;
    /** 最近一帧头部里的加速度计打包值（帧头 offset 16），自动旋转用。 */
    private volatile int lastAccel = 0;

    // 镜像：只是把屏幕上的画面左右翻转（GPU 侧变换，零开销）；存进相册的照片/视频保持原始方向
    private TextView mirrorToggle;
    private volatile boolean mirrorOn = false;

    // 自动旋转：把帧头里的加速度计读数换算成角度，让画面始终"正着"。
    // 只在屏幕上旋转，不改动画面数据本身（照片/录像保持原始方向）。
    // 开启旋转时把画面裁成圆形：长方形在旋转时四角会跟着扫动，看着很乱；
    // 圆形取景的外轮廓固定不动，只有内容在里面转，观感稳定。
    private TextView rotateToggle;
    private volatile boolean rotateOn = false;
    private final RotationFilter rotFilter = new RotationFilter();
    private volatile float displayAngle = 0f;
    private android.view.View panel;                  // 底部控制栏，用于算圆形取景的可用高度
    private android.graphics.Rect circleRect;         // 非空 = 当前裁剪为圆形
    private volatile boolean matrixDirty = true;
    private int matrixLastVW = -1, matrixLastVH = -1, matrixLastBW = -1, matrixLastBH = -1;
    private int matrixLastAngleQ = Integer.MIN_VALUE;
    private int matrixLastPanelH = -1;
    private boolean matrixLastCircle = false;

    private volatile boolean ledOn = true;
    private volatile String deviceIp = null;
    private volatile DatagramSocket ctrlSock = null;
    private volatile int pwmSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        panel = findViewById(R.id.panel);
        // 圆形取景靠「轮廓裁剪」实现（API 21+ 原生能力，不需要任何第三方库）；
        // 是否真的裁剪由 setClipToOutline 控制，只在自动旋转开启时打开。
        preview.setOutlineProvider(new CircleOutline());

        cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        brightPct = cfg.getInt("bright", 100);
        contrastPct = cfg.getInt("contrast", 100);

        seekBright = findViewById(R.id.seekBright);
        seekContrast = findViewById(R.id.seekContrast);
        vBright = findViewById(R.id.vBright);
        vContrast = findViewById(R.id.vContrast);

        seekBright.setProgress(brightPct);
        seekContrast.setProgress(contrastPct);
        applyFilter();

        SeekBar.OnSeekBarChangeListener l = new SeekListener();
        seekBright.setOnSeekBarChangeListener(l);
        seekContrast.setOnSeekBarChangeListener(l);

        console = findViewById(R.id.console);
        console.setOnClickListener(new ConsoleListener());
        ledToggle = findViewById(R.id.ledToggle);
        ledToggle.setOnClickListener(new LedListener());
        ledToggle.setOnLongClickListener(new LedLongListener());
        hint = findViewById(R.id.hint);
        snapBtn = findViewById(R.id.snap);
        snapBtn.setOnClickListener(new SnapListener());
        recBtn = findViewById(R.id.recBtn);
        recBtn.setOnClickListener(new RecListener());
        recIndicator = findViewById(R.id.recIndicator);
        recTime = findViewById(R.id.recTime);
        recInfo = findViewById(R.id.recInfo);
        disconnectView = findViewById(R.id.disconnect);

        mirrorToggle = findViewById(R.id.mirrorToggle);
        mirrorOn = cfg.getBoolean("mirror", false);
        mirrorToggle.setOnClickListener(new MirrorListener());
        applyMirror();

        rotateToggle = findViewById(R.id.rotateToggle);
        rotateOn = cfg.getBoolean("rotate", false);
        rotateToggle.setOnClickListener(new RotateListener());
        applyRotate();

        recorder = new Recorder(getCacheDir(), new FrameSourceImpl(), new RecorderListenerImpl());

        // Android 9 及更早：往系统相册写入必须先有存储权限，系统层面就不允许，只能申请。
        // Android 10 起是分区存储，写自己的媒体不需要任何权限 —— 所以高版本上不会弹这个框。
        if (android.os.Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_STORAGE);
        }
    }

    private static final int REQ_LEGACY_STORAGE = 1001;

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_LEGACY_STORAGE
                && (results.length == 0
                    || results[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            hint("未授予存储权限：本机系统较旧，拍照 / 录像将无法保存");
        }
    }

    /** 顶部「镜像」按钮：切换画面的左右翻转。 */
    private final class MirrorListener implements android.view.View.OnClickListener {
        @Override public void onClick(android.view.View v) {
            mirrorOn = !mirrorOn;
            cfg.edit().putBoolean("mirror", mirrorOn).apply();
            applyMirror();
        }
    }

    /** 用 View 的 scaleX 翻转，交给 GPU 做，不消耗 CPU、不影响帧率。 */
    private void applyMirror() {
        if (preview != null) preview.setScaleX(mirrorOn ? -1f : 1f);
        if (mirrorToggle != null) mirrorToggle.setText(mirrorOn ? "镜像:开" : "镜像:关");
    }

    /** 顶部「旋转」按钮：开启后画面跟随耳勺姿态自动转正（并把取景框切成圆形）。 */
    private final class RotateListener implements android.view.View.OnClickListener {
        @Override public void onClick(android.view.View v) {
            rotateOn = !rotateOn;
            cfg.edit().putBoolean("rotate", rotateOn).apply();
            rotFilter.reset();          // 重新开始采样，避免用上一次的陈旧角度
            matrixDirty = true;
            applyRotate();
        }
    }

    private void applyRotate() {
        if (rotateToggle != null) rotateToggle.setText(rotateOn ? "旋转:开" : "旋转:关");
        if (!rotateOn) displayAngle = 0f;
        matrixDirty = true;             // 让下一帧按新模式重算矩阵与裁剪
    }

    /** 圆形取景的轮廓：由 matrix 计算时写好的 circleRect 决定；为空则不做任何裁剪。 */
    private final class CircleOutline extends android.view.ViewOutlineProvider {
        @Override public void getOutline(android.view.View v, android.graphics.Outline o) {
            android.graphics.Rect r = circleRect;
            if (r == null || r.width() <= 0 || r.height() <= 0) {
                o.setRect(0, 0, Math.max(0, v.getWidth()), Math.max(0, v.getHeight()));
            } else {
                o.setOval(r.left, r.top, r.right, r.bottom);
            }
        }
    }

    private final class FrameSourceImpl implements Recorder.FrameSource {
        @Override public Bitmap latest() { return latestFrame.get(); }
    }

    private final class RecorderListenerImpl implements Recorder.Listener {
        @Override public void onError(String msg) {
            runOnUiThread(() -> hint(msg));
        }
        @Override public void onDone(java.io.File file, boolean keep) {
            if (keep) {
                String name = saveVideoToGallery(file);
                runOnUiThread(() -> hint(name != null ? "视频已存入相册 Movies/耳镜/" + name : "视频保存失败"));
            } else {
                file.delete();
                runOnUiThread(() -> hint("录制已取消，未保存"));
            }
            runOnUiThread(() -> updateRecUi());
        }
    }

    /** 点「录视频」= 开始；再点一下（此时显示「停止」）= 结束并存相册。 */
    private final class RecListener implements android.view.View.OnClickListener {
        @Override public void onClick(android.view.View v) {
            if (recorder.isRecording()) {
                recorder.stopAndSave();
                hint("正在保存…");
                updateRecUi();
                return;
            }
            if (deviceIp == null || frames == 0) {
                hint("设备未连接，无法录像");
                return;
            }
            recorder.start();
            hint("开始录制…");
            updateRecUi();
        }
    }

    private void updateRecUi() {
        final boolean rec = recorder != null && recorder.isRecording();
        runOnUiThread(() -> {
            if (recIndicator != null)
                recIndicator.setVisibility(rec ? android.view.View.VISIBLE : android.view.View.GONE);
            if (recBtn != null) {
                recBtn.setText(rec ? "停止" : "录视频");
                recBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        rec ? 0xFFE24B4A : 0xFFFFFFFF));
                recBtn.setTextColor(rec ? 0xFFFFFFFF : 0xFF000000);
            }
            if (snapBtn != null) {          // 录制中不允许拍照
                snapBtn.setEnabled(!rec);
                snapBtn.setAlpha(rec ? 0.4f : 1f);
            }
        });
    }

    /** 录像期间每秒刷新计时与文件信息。 */
    private void tickRecUi() {
        if (recorder == null || !recorder.isRecording()) return;
        long ms = recorder.elapsedMs();
        long sec = ms / 1000;
        String t = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60);
        long kb = recorder.framesWritten() * 24L;   // 粗略估计
        runOnUiThread(() -> {
            if (recTime != null) recTime.setText(t);
            if (recInfo != null) recInfo.setText(recorder.framesWritten() + "帧");
        });
    }

    /** 把缓存里的 MP4 复制进系统相册，返回文件名；失败返回 null。 */
    private String saveVideoToGallery(java.io.File src) {
        String name = "airlook_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".mp4";
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (android.os.Build.VERSION.SDK_INT >= 29)
                cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/耳镜");
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            in = new java.io.FileInputStream(src);
            out = getContentResolver().openOutputStream(uri);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return name;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
            src.delete();
        }
    }

    /** 灯光开关（硬件实测无调光能力，只保留开/关）。 */
    private final class LedListener implements android.view.View.OnClickListener {
        @Override public void onClick(android.view.View v) {
            ledOn = !ledOn;
            setLed(ledOn);
        }
    }

    /** 长按灯光开关 = 运行灯光自检（诊断用，界面上不占位置）。 */
    private final class LedLongListener implements android.view.View.OnLongClickListener {
        @Override public boolean onLongClick(android.view.View v) {
            if (selfTestRunning) return true;
            selfTestRunning = true;
            synchronized (selfTestLog) { selfTestLog.clear(); }
            new Thread(MainActivity.this::runLightSelfTest, "selftest").start();
            return true;
        }
    }

    /** 拍照：把当前帧存进系统相册（Pictures/耳镜）。 */
    private final class SnapListener implements android.view.View.OnClickListener {
        @Override public void onClick(android.view.View v) { savePhoto(); }
    }

    private void savePhoto() {
        Bitmap bm = latestFrame.get();
        if (bm == null) { hint("还没有画面，无法拍照"); return; }
        String name = "airlook_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(new java.util.Date()) + ".jpg";
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (android.os.Build.VERSION.SDK_INT >= 29)
                cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/耳镜");
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) { hint("保存失败：无法创建相册条目"); return; }
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            bm.compress(Bitmap.CompressFormat.JPEG, 95, os);
            os.flush();
            os.close();
            hint("已存入相册 Pictures/耳镜/" + name);
        } catch (Throwable t) {
            hint("保存失败：" + t);
        }
    }

    private void hint(String s) {
        runOnUiThread(() -> { if (hint != null) hint.setText(s); });
    }

    private void setLed(boolean on) {
        runOnUiThread(() -> { if (ledToggle != null) ledToggle.setText(on ? "灯:开" : "灯:关"); });
        final String ip = deviceIp;
        final DatagramSocket s = ctrlSock;
        if (ip == null || s == null) return;
        final int v = on ? LED_ON_VALUE : LED_OFF_VALUE;
        new Thread(() -> {
            try {
                InetAddress dst = InetAddress.getByName(ip);
                // 连发两次：控制通道是 UDP，多发一包可避免偶发丢包造成"按了没反应"
                for (int i = 0; i < 2; i++) {
                    s.send(new DatagramPacket(buildCmd(CMD_SET_PWM, v), 24, dst, CTRL_PORT));
                    if (i == 0) Thread.sleep(60);
                }
            } catch (Throwable ignore) {}
        }, "led").start();
    }

    private final class ConsoleListener implements android.view.View.OnClickListener {
        @Override public void onClick(android.view.View v) {
            if (!selfTestRunning) v.setVisibility(android.view.View.GONE);
        }
    }

    private volatile boolean selfTestRunning = false;
    private final java.util.List<String> selfTestLog = new java.util.ArrayList<>();

    private void runLightSelfTest() {
        appendSelfTest("=== 灯光终测开始 ===");
        appendSelfTest("（请把耳勺对准固定近处，别晃动）");

        // 第 1 组：确认「开/关」是否稳定可复现
        appendSelfTest("--- 1. 开关复现性 ---");
        for (int i = 1; i <= 3; i++) {
            if (!running.get()) return;
            ctrlSendRecv(CMD_SET_PWM, 0);
            sleep(1100);
            int off = measureBrightness();
            ctrlSendRecv(CMD_SET_PWM, LED_ON_VALUE);
            sleep(1100);
            int on = measureBrightness();
            appendSelfTest("第" + i + "次 关=" + off + " 开=" + on + " 差=" + (on - off));
        }

        // 第 2 组：SetLevel 档位（唯一还没验证的亮度通道）
        appendSelfTest("--- 2. SetLevel 档位 0-5 ---");
        ctrlSendRecv(CMD_SET_PWM, LED_ON_VALUE);
        sleep(1000);
        for (int lv = 0; lv <= 5; lv++) {
            if (!running.get()) return;
            ctrlSendRecv(0x1054, lv);
            sleep(1200);
            appendSelfTest("Level=" + lv + " → 亮度" + measureBrightness());
        }

        // 第 3 组：第二路灯（有的机型是两颗 LED，各占一半）
        appendSelfTest("--- 3. 第二路灯 PWM1 ---");
        ctrlSendRecv(CMD_SET_PWM, LED_ON_VALUE);
        ctrlSendRecv(0x1057, LED_ON_VALUE);
        sleep(1100);
        appendSelfTest("两路都开 → 亮度" + measureBrightness());
        ctrlSendRecv(0x1057, 0);
        sleep(1100);
        appendSelfTest("仅 PWM1 关 → 亮度" + measureBrightness());
        ctrlSendRecv(0x1057, LED_ON_VALUE);
        sleep(800);

        // 第 4 组：极小值再确认（1 和 2 是否真的等于满亮）
        appendSelfTest("--- 4. 极小值确认 ---");
        for (int v : new int[]{0, 1, 2, 3}) {
            if (!running.get()) return;
            ctrlSendRecv(CMD_SET_PWM, v);
            sleep(1100);
            appendSelfTest("值 " + v + " → 亮度" + measureBrightness());
        }

        // 收尾：全亮
        ctrlSendRecv(CMD_SET_PWM, LED_ON_VALUE);
        sleep(1000);
        appendSelfTest("=== 已恢复全亮，亮度" + measureBrightness() + " ===");
        appendSelfTest("（点一下这块文字可关闭）");
        selfTestRunning = false;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    /** 画面中过曝（接近纯白）像素的百分比——饱和状态下比平均亮度更灵敏。 */
    private int saturatedPct() {
        Bitmap bm = latestFrame.get();
        if (bm == null) return -1;
        try {
            Bitmap small = Bitmap.createScaledBitmap(bm, 32, 18, false);
            int hot = 0, n = 0;
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int c = small.getPixel(x, y);
                    int lum = ((c >> 16 & 0xFF) + (c >> 8 & 0xFF) + (c & 0xFF)) / 3;
                    if (lum >= 245) hot++;
                    n++;
                }
            }
            if (small != bm) small.recycle();
            return (int) (100L * hot / Math.max(1, n));
        } catch (Throwable t) {
            return -2;
        }
    }

    /** 取最近一帧的平均亮度（0-255），用于客观判断灯光是否变化。 */
    private int measureBrightness() {
        Bitmap bm = latestFrame.get();
        if (bm == null) return -1;
        try {
            Bitmap small = Bitmap.createScaledBitmap(bm, 32, 18, false);
            long sum = 0;
            int n = 0;
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int c = small.getPixel(x, y);
                    sum += ((c >> 16 & 0xFF) + (c >> 8 & 0xFF) + (c & 0xFF)) / 3;
                    n++;
                }
            }
            if (small != bm) small.recycle();
            return (int) (sum / Math.max(1, n));
        } catch (Throwable t) {
            return -2;
        }
    }

    private void appendSelfTest(String s) {
        synchronized (selfTestLog) {
            selfTestLog.add(s);
            if (selfTestLog.size() > 24) selfTestLog.remove(0);
        }
        final String all = String.join("\n", new java.util.ArrayList<>(selfTestLog));
        runOnUiThread(() -> {
            if (console != null) {
                console.setVisibility(android.view.View.VISIBLE);
                console.setText(all);
            }
        });
    }

    /** 发一条控制指令并等应答，返回应答前 12 字节的 hex 描述。 */
    private String ctrlSendRecv(int cmd, int value) {
        final String ip = deviceIp;
        final DatagramSocket s = ctrlSock;
        if (ip == null || s == null) return "无控制通道";
        try {
            s.send(new DatagramPacket(buildCmd(cmd, value), 24,
                    InetAddress.getByName(ip), CTRL_PORT));
            byte[] buf = new byte[2048];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            s.setSoTimeout(800);
            s.receive(r);
            byte[] resp = java.util.Arrays.copyOf(buf, r.getLength());
            return resp.length + "B " + hex(resp, Math.min(12, resp.length));
        } catch (Exception e) {
            return "无应答";
        }
    }

    /** 构造 24 字节控制报文：99 99 | 命令字 LE | 序号 LE | 数值 LE | 8 字节 0 */
    private synchronized byte[] buildCmd(int cmd, int value) {
        byte[] p = new byte[24];
        p[0] = (byte) 0x99; p[1] = (byte) 0x99;
        p[2] = (byte) (cmd & 0xFF); p[3] = (byte) ((cmd >> 8) & 0xFF);
        int seq = ++pwmSeq;
        p[4] = (byte) seq; p[5] = (byte) (seq >> 8); p[6] = (byte) (seq >> 16); p[7] = (byte) (seq >> 24);
        p[8] = (byte) value; p[9] = (byte) (value >> 8); p[10] = (byte) (value >> 16); p[11] = (byte) (value >> 24);
        return p;
    }

    /** 具名内部类（保证与旧版 d8 兼容）。 */
    private final class SeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            onSliderMoved();
        }
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }

    private void onSliderMoved() {
        brightPct = seekBright.getProgress();
        contrastPct = seekContrast.getProgress();
        applyFilter();
        cfg.edit().putInt("bright", brightPct).putInt("contrast", contrastPct).apply();
    }

    /** 用 ColorMatrix 在 GPU 上做亮度/对比度，不占 CPU、不影响帧率。 */
    private void applyFilter() {
        float gain = 0.5f + brightPct / 100f;        // 0% → 0.5，100% → 1.0，200% → 1.5
        float c = 0.5f + contrastPct / 100f;         // 0% → 0.5，100% → 1.0，200% → 1.5
        float scale = gain * c;
        float trans = (1f - c) * 127.5f;
        ColorMatrix cm = new ColorMatrix(new float[]{
                scale, 0, 0, 0, trans,
                0, scale, 0, 0, trans,
                0, 0, scale, 0, trans,
                0, 0, 0, 1, 0
        });
        preview.setColorFilter(new ColorMatrixColorFilter(cm));
        vBright.setText(brightPct + "%");
        vContrast.setText(contrastPct + "%");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (running.compareAndSet(false, true)) {
            new Thread(this::worker, "stream").start();
        }
        if (uiLoopActive.compareAndSet(false, true)) preview.post(this::uiLoop);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 用户离开 App（切后台/退出）→ 录制中断且不保存
        if (recorder != null && recorder.isRecording()) {
            recorder.abort();
            updateRecUi();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        running.set(false);
        uiLoopActive.set(false);
    }

    private void uiLoop() {
        if (!running.get() || !uiLoopActive.get()) return;
        Bitmap bm = latestFrame.get();
        if (bm != null) {
            preview.setImageBitmap(bm);
            updateDisplayMatrix(bm);
        }
        long now = System.currentTimeMillis();
        if (now - lastUiUpdate > 500) {
            lastUiUpdate = now;
            updateStatus(null);
            tickRecUi();
            checkDisconnect(now);
        }
        preview.postDelayed(this::uiLoop, 33);
    }

    /**
     * 把「缩放 + 旋转 + 居中」合成一个矩阵交给 ImageView 渲染（scaleType=matrix）。
     * 变形全部由 GPU 完成，CPU 只在角度/尺寸真的变化时才重算一次。
     *
     * 两种模式：
     *   自动旋转关闭 → 长方形，完整显示（与之前一致，可能留黑边）
     *   自动旋转开启 → 圆形取景，画面按「覆盖」缩放填满圆，旋转时不会露黑边
     */
    private void updateDisplayMatrix(Bitmap bm) {
        int vw = preview.getWidth(), vh = preview.getHeight();
        int bw = bm.getWidth(), bh = bm.getHeight();
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return;

        if (rotateOn) displayAngle = rotFilter.update(lastAccel);

        int panelH = (panel != null) ? panel.getHeight() : 0;
        int side = 0, top = 0;
        if (rotateOn) {
            // 圆心放在「底部控制栏以上」那块区域的正中，避免被控制栏压住
            int availH = Math.max(1, vh - panelH);
            side = Math.min(vw, availH);
            top = Math.max(0, (availH - side) / 2);
        }

        int aq = Math.round(displayAngle * 10f);          // 量化到 0.1°，避免无意义的重复计算
        if (!matrixDirty && vw == matrixLastVW && vh == matrixLastVH && bw == matrixLastBW
                && bh == matrixLastBH && aq == matrixLastAngleQ && panelH == matrixLastPanelH
                && rotateOn == matrixLastCircle) return;
        matrixDirty = false;
        matrixLastVW = vw; matrixLastVH = vh;
        matrixLastBW = bw; matrixLastBH = bh; matrixLastAngleQ = aq;
        matrixLastPanelH = panelH; matrixLastCircle = rotateOn;
        float deg = aq / 10f;

        android.graphics.Matrix m = new android.graphics.Matrix();
        if (rotateOn) {
            // 圆形取景：圆外都裁掉。画面按「覆盖」缩放（图高 = 圆的直径），
            // 这样圆在任意旋转角度下都被完全填满，不会露出黑边。
            android.graphics.Rect r = new android.graphics.Rect(
                    (vw - side) / 2, top, (vw - side) / 2 + side, top + side);
            if (!r.equals(circleRect)) {
                circleRect = r;
                preview.setClipToOutline(true);
                preview.invalidateOutline();
            }
            // 覆盖式缩放：让图高 = 圆的直径，这是"圆在任意角度都不露黑边"的理论最小值。
            // 但理论最小值意味着图像边缘与圆**恰好相切**（余量 0），实际渲染时抗锯齿会在
            // 相切处露出发丝状黑边，所以再放大 2% 留出安全余量。
            float s = Math.max(side / (float) bw, side / (float) bh) * CIRCLE_OVERSCAN;
            m.setScale(s, s);
            m.postRotate(deg, bw * s / 2f, bh * s / 2f);
            m.postTranslate(r.exactCenterX() - bw * s / 2f, r.exactCenterY() - bh * s / 2f);
        } else {
            if (circleRect != null) {                     // 从圆形切回长方形
                circleRect = null;
                preview.setClipToOutline(false);
                preview.invalidateOutline();
            }
            // 旋转后画面的外接矩形，用来算等比缩放系数（完整显示，可能留黑边）
            android.graphics.RectF rr = new android.graphics.RectF(0, 0, bw, bh);
            android.graphics.Matrix rot = new android.graphics.Matrix();
            rot.setRotate(deg, bw / 2f, bh / 2f);
            rot.mapRect(rr);
            float s = Math.min(vw / rr.width(), vh / rr.height());
            m.setScale(s, s);
            m.postRotate(deg, bw * s / 2f, bh * s / 2f);
            m.postTranslate((vw - bw * s) / 2f, (vh - bh * s) / 2f);
        }
        preview.setImageMatrix(m);
    }

    /** 连续 3 秒收不到画面 = 设备断开：中央显示提示；若正在录像则自动结束并保存。 */
    private void checkDisconnect(long now) {
        if (frames == 0 || lastFrameAt == 0) return;
        boolean lost = now - lastFrameAt > 3000;
        if (lost && !disconnectShown) {
            disconnectShown = true;
            if (disconnectView != null) disconnectView.setVisibility(android.view.View.VISIBLE);
            if (recorder != null && recorder.isRecording()) {
                recorder.stopAndSave();      // 断连时保存（与主动退出不同）
                hint("连接中断，录像已自动保存");
                updateRecUi();
            } else {
                hint("连接中断");
            }
        } else if (!lost && disconnectShown) {
            disconnectShown = false;
            if (disconnectView != null) disconnectView.setVisibility(android.view.View.GONE);
            hint("");
        }
    }

    private void updateStatus(String line) {
        StringBuilder sb = new StringBuilder();
        if (line != null) sb.append(line).append('\n');
        sb.append("包 ").append(packets).append(" · 帧 ").append(frames)
          .append(" · 丢帧 ").append(dropped)
          .append(" · 解码失败 ").append(decErrs)
          .append(" · 无效包 ").append(invalid);
        if (lastW > 0) sb.append("\n分辨率 ").append(lastW).append('×').append(lastH);
        if (frames == 0) {   // 还没出画面时显示诊断信息；一旦正常出图就收起，保持界面干净
            if (diagPeer != null) sb.append("\n来源 ").append(diagPeer);
            if (diagFirst != null) sb.append("\n首包hex ").append(diagFirst);
            synchronized (diagHeads) {
                for (int i = 0; i < diagHeads.size(); i++)
                    sb.append("\n#").append(i).append(' ').append(diagHeads.get(i));
            }
            if (!sizeHist.isEmpty()) {
                List<String> parts = new ArrayList<>();
                sizeHist.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(4)
                        .forEach(e -> parts.add(e.getKey() + "B×" + e.getValue()));
                sb.append("\n包长分布 ").append(String.join(" | ", parts));
            }
        }
        final String text = sb.toString();
        final boolean working = frames > 0;
        runOnUiThread(() -> {
            if (working) {
                status.setVisibility(View.GONE);   // 正常出画面就不显示任何诊断文字
            } else {
                status.setVisibility(View.VISIBLE);
                status.setText(text);
            }
        });
    }

    private void recordDiag(byte[] buf, int len, DatagramPacket p) {
        if (diagPeer == null)
            diagPeer = p.getAddress().getHostAddress() + ":" + p.getPort();
        if (diagFirst == null)
            diagFirst = hex(buf, Math.min(48, len));
        sizeHist.merge(len, 1, Integer::sum);
        synchronized (diagHeads) {
            if (diagHeads.size() < MAX_DIAG_SAMPLES)
                diagHeads.add(len + "B: " + hex(buf, Math.min(24, len)));
        }
    }

    private void log(String s) {
        runOnUiThread(() -> status.append("\n" + s));
    }

    // ---------------- 后台主循环 ----------------

    private void worker() {
        DatagramSocket sock = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network net = pickWifiNetwork(cm);
            if (net == null) {
                updateStatus("未找到 WiFi 网络：请先在系统设置里连接耳勺热点（名字形如 JesHome-xxxx），再回到本应用。");
                return;
            }
            try { cm.bindProcessToNetwork(net); } catch (Exception ignore) {}

            List<String> candidates = buildCandidates(cm, net);
            log("候选设备地址: " + candidates);

            sock = new DatagramSocket(null);
            net.bindSocket(sock);   // 绑定到 WiFi 网卡，绕过 VPN/蜂窝默认路由
            sock.setSoTimeout(1200);
            sock.setReceiveBufferSize(1 << 20);

            // 对每个候选地址连发启动包，谁回数据用谁
            String found = null;
            byte[] buf = new byte[65536];
            FrameAssembler fa = new FrameAssembler();
            long t0 = System.currentTimeMillis();
            boolean announced = false;

            while (running.get()) {
                long now = System.currentTimeMillis();
                if (found == null) {
                    if (now - t0 > 20000) { updateStatus("20 秒内未收到设备数据。请确认：1) 耳勺已开机（灯亮）；2) 手机 WiFi 已连到耳勺热点；3) 没有其他设备占着热点。"); return; }
                    for (String ip : candidates) sendCmd(sock, ip, 3);
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        recordDiag(buf, p.getLength(), p);
                        if (looksLikeFrame(buf, p)) {
                            found = p.getAddress().getHostAddress();
                            deviceIp = found;
                            log("✓ 设备应答，来源 " + found + ":" + p.getPort());
                            // 建立控制通道并读一次当前灯光值
                            try {
                                ctrlSock = new DatagramSocket(null);
                                net.bindSocket(ctrlSock);
                                ctrlSock.setReceiveBufferSize(1 << 16);
                                hint("设备已连接");
                                // 把硬件同步成界面当前状态（默认开灯），而不是无脑点亮：
                                // 用户手动关灯后切后台再回来，不应该被强行打开。
                                setLed(ledOn);
                            } catch (Exception e) {
                                log("控制通道建立失败：" + e);
                            }
                        }
                    } catch (Exception ignore) {}
                    continue;
                }

                // 已锁定设备：保活 + 收流
                if (now - lastKeepalive > 800) sendCmd(sock, found, 1);
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    packets++;
                    recordDiag(buf, p.getLength(), p);
                    int len = p.getLength();
                    boolean rawMode = rawJpegMode && frames > 0;

                    if (!rawMode && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xD8 && len > 100) {
                        // 兜底：整包即一帧完整 JPEG（无分片头的简单流）
                        Bitmap bm = BitmapFactory.decodeByteArray(buf, 0, len);
                        if (bm != null) {
                            rawJpegMode = true;
                            frames++;
                            latestFrame.set(bm);
                            lastFrameAt = System.currentTimeMillis();
                            continue;
                        }
                    }

                    byte[] frame = fa.feed(buf, len);
                    if (fa.width > 0) { lastW = fa.width; lastH = fa.height; }
                    lastAccel = fa.accelerometer;   // 供自动旋转使用（帧头 offset 16）
                    if (frame != null) {
                        Bitmap bm = (fa.format == 4)
                                ? decodeYuyv(frame, fa.width, fa.height)
                                : BitmapFactory.decodeByteArray(frame, 0, frame.length);
                        if (bm != null) { frames++; latestFrame.set(bm); lastFrameAt = System.currentTimeMillis(); }
                        else decErrs++;
                        if (frames == 1) log("✓ 首帧完成：" + fa.width + "×" + fa.height
                                + "，格式 " + (fa.format == 4 ? "YUYV" : "MJPEG")
                                + "，" + frame.length + " 字节");
                    } else if (fa.lastDrop) {
                        dropped++;
                    } else if (fa.lastInvalid) {
                        invalid++;
                    }
                } catch (Exception ignore) {}
            }
        } catch (Throwable t) {
            updateStatus("异常退出: " + t);
        } finally {
            if (sock != null) sock.close();
            DatagramSocket cs = ctrlSock;
            if (cs != null) { cs.close(); ctrlSock = null; }
            deviceIp = null;
            running.set(false);
        }
    }

    private volatile long lastKeepalive = 0;
    private volatile boolean rawJpegMode = false;

    private void sendCmd(DatagramSocket sock, String ip, int times) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            for (int i = 0; i < times; i++)
                sock.send(new DatagramPacket(START_CMD, START_CMD.length, a, VIDEO_PORT));
            lastKeepalive = System.currentTimeMillis();
        } catch (Exception ignore) {}
    }

    private Network pickWifiNetwork(ConnectivityManager cm) {
        Network[] all = cm.getAllNetworks();
        for (Network n : all) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        // 没有活动 WiFi 网络时，尝试从请求中拿（HarmonyOS 兼容路径）
        return null;
    }

    private List<String> buildCandidates(ConnectivityManager cm, Network net) {
        List<String> list = new ArrayList<>();
        try {
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp != null) {
                for (android.net.RouteInfo r : lp.getRoutes()) {
                    java.net.InetAddress gw = r.getGateway();
                    if (gw != null && !gw.isAnyLocalAddress()) {
                        String g = gw.getHostAddress();
                        if (g != null && !list.contains(g)) list.add(g);
                    }
                }
            }
        } catch (Exception ignore) {}
        for (String ip : new String[]{"192.168.0.10", "192.168.1.1", "192.168.4.1", "192.168.10.123"})
            if (!list.contains(ip)) list.add(ip);
        return list;
    }

    private boolean looksLikeFrame(byte[] buf, DatagramPacket p) {
        int len = p.getLength();
        if (len < 24) return false;
        return (buf[0] & 0xFF) == 0x66 && (buf[1] & 0xFF) == 0x01;
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x ", b[i]));
        return sb.toString().trim();
    }

    /** YUYV422(YUY2) → Bitmap，用于设备以 YUYV 模式出图的情况。 */
    private static Bitmap decodeYuyv(byte[] data, int w, int h) {
        if (w <= 0 || h <= 0 || data.length < w * h * 2) return null;
        int[] px = new int[w * h];
        int p = 0;
        for (int i = 0; i < w * h / 2; i++) {
            int y0 = data[p] & 0xFF, u = (data[p + 1] & 0xFF) - 128;
            int y1 = data[p + 2] & 0xFF, v = (data[p + 3] & 0xFF) - 128;
            p += 4;
            int c0 = y0 - 16, c1 = y1 - 16;
            int r = clamp((298 * c0 + 409 * v + 128) >> 8);
            int g = clamp((298 * c0 - 100 * u - 208 * v + 128) >> 8);
            int b = clamp((298 * c0 + 516 * u + 128) >> 8);
            px[2 * i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            r = clamp((298 * c1 + 409 * v + 128) >> 8);
            g = clamp((298 * c1 - 100 * u - 208 * v + 128) >> 8);
            b = clamp((298 * c1 + 516 * u + 128) >> 8);
            px[2 * i + 1] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
