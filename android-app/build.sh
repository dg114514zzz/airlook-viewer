#!/bin/bash
# 耳镜查看器 —— 一键构建脚本
#
# 用法（在任意目录都行）:
#   bash build.sh
#   VK=19 VN=1.1 bash build.sh            # 指定 versionCode / versionName
#   OUTNAME=耳镜查看器-1.1.apk bash build.sh
#   SKIP_TESTS=1 bash build.sh            # 跳过构建前的离线自测
#
# 依赖：完整 JDK 17+、Android SDK(platform-33 + build-tools 33.0.2)。
# 定位逻辑见 _env.sh；找不到时用 AIRLOOK_JDK / AIRLOOK_TOOLS 指过去。
#
# 产物：本目录下的「耳镜查看器.apk」（已签名，可直接装到手机）
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/_env.sh"

# 版本号：显式写了 VK/VN 就由它们说了算（写进一份临时清单，不改源文件）；
# 没写则采用 AndroidManifest.xml 里已有的值。
#   原因：aapt2 的 --version-code/--version-name 会被清单里已有的值覆盖，所以改清单才生效。
VK_SET="${VK:-}"; VN_SET="${VN:-}"
VK="${VK:-1}"; VN="${VN:-1.0}"
OUT="$APP/build"
OUTNAME="${OUTNAME:-耳镜查看器.apk}"

echo "JDK  : $JDK"
echo "SDK  : $TOOLS"
[ -n "$PY" ] && echo "Python: $PY（用于打包 dex）" || echo "Python: 未找到 → 改用 JDK 自带的 jar 打包"

# ---------------------------------------------------------------- 签名密钥
# 查找顺序：项目内 keys/debug.keystore → 工具链目录 → 都没有就自动生成一把。
# 开源仓库刻意**不含**密钥，所以陌生人 clone 下来也必须能直接构建成功。
KEYS_DIR="$(cd "$HERE/../keys" 2>/dev/null && pwd || true)"
if [ -n "$KEYS_DIR" ] && [ -f "$KEYS_DIR/debug.keystore" ]; then
    KS="$(cygpath -m "$KEYS_DIR")/debug.keystore"
elif [ -f "$TOOLS/debug.keystore" ]; then
    KS="$TOOLS/debug.keystore"
else
    KS="$(cygpath -m "$(cd "$HERE/.." && pwd)")/keys/debug.keystore"
fi

if [ ! -f "$KS" ]; then
    echo "[0] 未找到签名密钥 → 自动生成一把（仅本机使用）"
    mkdir -p "$(dirname "$KS")"
    "$JAVA/keytool" -genkeypair -keystore "$KS" -alias airlook -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass airlook123 -keypass airlook123 \
        -dname "CN=Airlook Viewer, OU=Dev, O=Personal, C=CN" >/dev/null
    echo "    已生成：$KS （别名 airlook / 口令 airlook123）"
    echo "    注意：这是全新密钥，签出的包无法覆盖安装由其它密钥签名的版本。"
fi

# ---------------------------------------------------------------- 构建前自测
if [ -z "$SKIP_TESTS" ]; then
    bash "$HERE/test.sh" >/dev/null 2>&1 \
        && echo "[0] 离线自测通过（跳过请设 SKIP_TESTS=1）" \
        || { echo "[X] 离线自测失败，详情：bash test.sh"; exit 1; }
fi

cd "$HERE"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

# 清单：VK/VN 显式给了就生成一份改好版本号的临时清单
MANIFEST="$APP/AndroidManifest.xml"
if [ -n "$VK_SET" ] || [ -n "$VN_SET" ]; then
    sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VK\"/" \
        -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VN\"/" \
        "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
    MANIFEST="$OUT/AndroidManifest.xml"
    echo "    版本号覆盖为 versionCode=$VK versionName=$VN"
fi

echo "[1/6] aapt2 编译资源"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2.exe" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest "$MANIFEST" \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 33 \
    --version-code "$VK" --version-name "$VN" "$OUT/res.zip"

echo "[3/6] javac"
"$JAVA/javac.exe" --release 8 -cp "$JAR" -d "$OUT/classes" -encoding UTF-8 \
    "$OUT/gen/com/airlook/viewer/R.java" \
    "$APP/src/com/airlook/viewer/MainActivity.java" \
    "$APP/src/com/airlook/viewer/FrameAssembler.java" \
    "$APP/src/com/airlook/viewer/RotationFilter.java" \
    "$APP/src/com/airlook/viewer/Recorder.java" \
    2>"$OUT/javac.log" || { cat "$OUT/javac.log"; exit 1; }

echo "[4/6] d8 → dex"
# 用独立的 r8.jar：build-tools 33 自带那版 R8(3.3.20) 遇到含内部类的 class 会崩。
"$JAVA/java.exe" -cp "$R8" com.android.tools.r8.D8 --release \
    --lib "$JAR" --min-api 26 --output "$OUT" \
    $(find "$OUT/classes" -name '*.class')

echo "[5/6] 打包 + zipalign"
# aapt2 不会把 dex 打进 APK，要自己追加。优先 Python；没有就用 JDK 自带的 jar
# （已验证 jar 只会追加 classes.dex，不会产生 META-INF 之类的多余文件）。
if [ -n "$PY" ]; then
    "$PY" - "$OUT" <<'PYEOF'
import zipfile, shutil, os, sys
out = sys.argv[1]
shutil.copy(os.path.join(out, "unsigned.apk"), os.path.join(out, "packed.apk"))
with zipfile.ZipFile(os.path.join(out, "packed.apk"), "a") as z:
    z.write(os.path.join(out, "classes.dex"), "classes.dex")
PYEOF
else
    cp -f "$OUT/unsigned.apk" "$OUT/packed.apk"
    ( cd "$OUT" && "$JAVA/jar.exe" uf packed.apk classes.dex )
fi
"$BT/zipalign.exe" -f 4 "$OUT/packed.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
# [!] 先签成 ASCII 临时名再改名：Windows 版 apksigner 收到中文路径参数会乱码导致失败。
TMPAPK="_build-out.apk"
"$JAVA/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-key-alias airlook \
    --ks-pass pass:airlook123 --key-pass pass:airlook123 \
    --out "$APP/$TMPAPK" "$OUT/aligned.apk"

"$JAVA/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify \
    "$APP/$TMPAPK" || { echo "[X] 签名校验失败"; exit 1; }

mv -f "$APP/$TMPAPK" "$APP/$OUTNAME"
rm -f "$APP/$TMPAPK.idsig"      # v4 增量安装清单，手动安装用不到
echo "OK: $OUTNAME"
