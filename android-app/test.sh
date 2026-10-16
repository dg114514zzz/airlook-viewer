#!/bin/bash
# 耳镜查看器 —— 离线单元测试
#
# 特点：**不需要设备、不需要联网**，只需要 JDK 和 Android SDK 里的 android.jar
#（不需要 aapt2 / build-tools，比完整构建的门槛低）。
#
# 覆盖三块纯算法逻辑 —— 也就是最容易改错、又最难靠肉眼发现问题的部分：
#
#   SelfTest  FrameAssembler  24 字节帧头解析 + MJPEG 分片重组
#                              （用真机实测参数：整帧 20632B、分片 1436B、type 在 0x01/0x03 间跳）
#   RotTest   RotationFilter  加速度计(3×10bit) → 旋转角度
#                              （照搬上游 rbeilvert/otoscope 官方单测的向量，确认移植无偏差）
#   YuvTest   Recorder        BT.601 YUV 转换（送进 MediaCodec 编码前的一步）
#
# 不在这里测的：MainActivity（界面 / 网络 / 控制通道）—— 它依赖真实设备，
# 只能装到手机上验证，见 CONTRIBUTING.md。
#
# 用法: bash test.sh
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/_env.sh"

OUT="$APP/build/tests"
rm -rf "$OUT"; mkdir -p "$OUT"

echo "编译被测逻辑 + 测试用例 ..."
"$JAVA/javac.exe" --release 8 -cp "$JAR" -d "$OUT" -encoding UTF-8 \
    "$APP/src/com/airlook/viewer/FrameAssembler.java" \
    "$APP/src/com/airlook/viewer/RotationFilter.java" \
    "$APP/src/com/airlook/viewer/Recorder.java" \
    "$APP/tests/com/airlook/viewer/"*.java 2>"$OUT/javac.log" \
    || { grep -v '^注:' "$OUT/javac.log"; exit 1; }

failed=""
for t in SelfTest RotTest YuvTest; do
    echo
    echo "---- $t ----"
    "$JAVA/java.exe" -cp "$OUT;$JAR" "com.airlook.viewer.$t" || failed="$failed $t"
done

echo
if [ -n "$failed" ]; then
    echo "[X] 失败:$failed"
    exit 1
fi
echo "[OK] 三项全过"
