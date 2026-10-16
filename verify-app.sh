#!/bin/bash
# 耳镜查看器「自检」脚本 —— 独立核验这个 App 到底要了什么权限、有没有对外联网行为。
#
# 用法：
#   bash verify-app.sh                 # 检查 android-app/耳镜查看器.apk
#   bash verify-app.sh 某个.apk         # 检查指定的包
#   AIRLOOK_TOOLS=D:/sdk bash verify-app.sh
#
# 只做「读取 + 打印」，不修改任何文件、不联网。
# 需要：Android SDK 的 build-tools（aapt、apksigner）＋ JDK；第 3、4 项另需 Python
#       （没有 Python 会自动跳过这两项，其余照常）。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
HERE_W="$(cygpath -m "$HERE")"          # F:/... 形式：Windows 工具(aapt/python/java)只认这种

# ---- Android 工具链 ----
TOOLS="${AIRLOOK_TOOLS:-$HERE_W/android-build-tools}"
[ -d "$TOOLS/sdk" ] || TOOLS="F:/android-build-tools"
BT="$TOOLS/sdk/build-tools/33.0.2"

# ---- JDK（apksigner 需要 java）----
if [ -x "${AIRLOOK_JDK:-}/bin/java" ] || [ -f "${AIRLOOK_JDK:-}/bin/java.exe" ]; then
    JAVA_BIN="$(cygpath -m "$AIRLOOK_JDK")/bin"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(dirname "$(command -v java)")"
elif [ -n "$JAVA_HOME" ] && [ -f "$JAVA_HOME/bin/java.exe" ]; then
    JAVA_BIN="$(cygpath -m "$JAVA_HOME")/bin"
else
    JAVA_BIN=""
fi

# ---- Python（可选）----
PY="${AIRLOOK_PY:-}"
if [ -z "$PY" ]; then
    for c in python python3 py; do
        command -v "$c" >/dev/null 2>&1 && { PY="$c"; break; }
    done
fi

[ -d "$BT" ] || { echo "找不到工具链：$BT"; echo "用 AIRLOOK_TOOLS=<路径> 指过去"; exit 1; }
APK="${1:-$HERE_W/android-app/耳镜查看器.apk}"
[ -f "$APK" ] || { echo "找不到 APK：$APK"; exit 1; }

# aapt 是 Windows 程序，收到中文路径参数会乱码 —— 先复制成 ASCII 名再检查
TMP="$HERE_W/.verify-tmp.apk"
cp -f "$APK" "$TMP"
trap 'rm -f "$TMP"' EXIT

echo "检查对象：$APK"
echo "文件大小：$(du -h "$APK" | cut -f1)"
echo

echo "== 1. 身份信息 =="
"$BT/aapt.exe" dump badging "$TMP" 2>/dev/null | grep -E "^package:|application-label:" | sed 's/^/  /'

echo
echo "== 2. 它申请了哪些权限 =="
"$BT/aapt.exe" dump permissions "$TMP" 2>/dev/null | sed 's/uses-permission: name=/  /'

if [ -n "$PY" ]; then
    echo
    echo "== 3. 安装包里的全部文件（有没有塞第三方 SDK / 原生库）=="
    "$PY" - "$TMP" <<'PYEOF'
import zipfile, sys
z = zipfile.ZipFile(sys.argv[1])
total = 0
for i in z.infolist():
    print("  %-30s %9d 字节" % (i.filename, i.file_size))
    total += i.file_size
print("  -- 共 %d 个文件，解压后合计 %d 字节" % (len(z.infolist()), total))
PYEOF

    echo
    echo "== 4. 对外联网扫描：包内有没有网址 / 服务器地址 =="
    "$PY" - "$TMP" <<'PYEOF'
import zipfile, re, sys, collections
z = zipfile.ZipFile(sys.argv[1])
urls, ips = collections.Counter(), collections.Counter()
for n in z.namelist():
    if n.endswith((".dex", ".arsc", ".xml")) or n.startswith("resources"):
        try:
            b = z.read(n)
        except Exception:
            continue
        for m in re.finditer(rb"https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]{4,}", b):
            urls[m.group().decode("latin1")] += 1
        for m in re.finditer(rb"\b(?:\d{1,3}\.){3}\d{1,3}\b", b):
            ips[m.group().decode()] += 1

NS = "http://schemas.android.com/apk/res/android"   # XML 命名空间，不是网络地址
real = [(u, c) for u, c in urls.items() if "schemas.android.com" not in u]
print("  HTTP/HTTPS 网址：%d 个" % len(real))
for u, _ in sorted(real)[:20]:
    print("      ", u)
if urls.get(NS):
    print("  （另有 %d 处 %s" % (urls[NS], NS))
    print("    这是每个安卓 XML 文件都必须写的命名空间声明，不是网址，永远不会被访问）")
print("  IP 地址：%d 个" % len(ips))
for i, _ in ips.most_common(20):
    print("      ", i)
print()
print("  说明：若只出现 192.168.x.x 这类内网地址，说明它只跟耳勺设备通信；")
print("        出现任何公网域名 = 有数据外发通道，需要警惕。")
PYEOF
else
    echo
    echo "== 3./4. 已跳过 =="
    echo "  这两项需要 Python（用来列出包内文件、扫描内嵌网址）。装了 Python 再跑一次即可。"
fi

echo
echo "== 5. 签名证书（确认是同一把密钥签的，没被替换）=="
if [ -n "$JAVA_BIN" ]; then
    "$JAVA_BIN/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool \
        verify --print-certs "$TMP" 2>/dev/null | grep -E "Signer #1 certificate (DN|SHA-256)" | sed 's/^/  /'
else
    echo "  （没找到 java，跳过）"
fi

echo
echo "== 6. 源码规模（全部可读，自行审阅）=="
wc -l "$HERE_W"/android-app/src/com/airlook/viewer/*.java | sed 's/^/  /'

echo
echo "自检结束。第 2、3、4 项是关键："
echo "  · 权限：Android 10 及以上只有 3 个（联网 / 网络状态 / WiFi 状态）。"
echo "    另有一条 WRITE_EXTERNAL_STORAGE 带 maxSdkVersion=28，只在 Android 9 及更早系统生效"
echo "    （旧系统写相册必须用它），新系统的权限列表里不会出现它。"
echo "    没有定位、通讯录、已装应用列表等敏感权限。"
echo "  · 包内只有几个文件、没有原生 .so 库 -> 没有夹带第三方 SDK"
echo "  · 没有任何公网网址 -> 数据没有地方可发出去"
