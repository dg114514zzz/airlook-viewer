# _env.sh —— build.sh 与 test.sh 共用的环境探测
#
# 用法：在脚本里写  source "$(dirname "$0")/_env.sh"
# 导出：APP / JAVA / TOOLS / BT / JAR / R8 / PY
#
# 可用环境变量覆盖（三个脚本都认）：
#   AIRLOOK_JDK=/c/Java/jdk-21        JDK 位置（需要完整 JDK，不是只有 java 的 JRE）
#   AIRLOOK_TOOLS=D:/android-sdk      工具链位置
#   AIRLOOK_PY=/usr/bin/python        指定 Python（打包 dex 用；没有会退回 JDK 自带的 jar）

APP="$(cygpath -m "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")"

# ---------------------------------------------------------------- JDK
# 注意：只认「真正的 JDK」。Windows 上还有一类转发目录（如
# C:/Program Files/Common Files/Oracle/Java/javapath）只有 java/javac，没有
# keytool/jar，而构建需要用到它们，所以统一用 keytool 是否存在来判定。
_is_jdk() { [ -f "$1/bin/keytool.exe" ] || [ -x "$1/bin/keytool" ]; }

_find_jdk() {
    # 1) 显式指定
    if [ -n "$AIRLOOK_JDK" ] && _is_jdk "$AIRLOOK_JDK"; then echo "$AIRLOOK_JDK"; return; fi
    # 2) JAVA_HOME（必须验证——环境变量常年可能是坏的/过期的）
    if [ -n "$JAVA_HOME" ] && _is_jdk "$JAVA_HOME"; then echo "$JAVA_HOME"; return; fi
    # 3) Windows 注册表（按版本倒序，取第一个真实存在的）
    if command -v reg >/dev/null 2>&1; then
        for raw in $(reg query 'HKLM\SOFTWARE\JavaSoft\JDK' /s 2>/dev/null \
                     | grep -i 'JavaHome' | sed 's/.*REG_SZ[[:space:]]*//' | tr -d '\r' | sort -r); do
            u="$(cygpath -u "$raw" 2>/dev/null || echo "$raw")"
            if _is_jdk "$u"; then echo "$u"; return; fi
        done
    fi
    # 4) PATH 上的 javac（仅当旁边真有 keytool）
    if command -v javac >/dev/null 2>&1; then
        d="$(dirname "$(command -v javac)")"
        if [ -f "$d/keytool.exe" ] || [ -x "$d/keytool" ]; then
            echo "$(cd "$d/.." && pwd)"; return
        fi
    fi
    return 1
}

JDK="$(_find_jdk || true)"
if [ -z "$JDK" ]; then
    echo "[X] 找不到可用的 JDK（需要 17 或更高，且必须是完整 JDK）"
    echo "  已装 JDK 的话，这样指过来："
    echo "    AIRLOOK_JDK=/c/Java/jdk-21 bash build.sh"
    exit 1
fi
JAVA="$(cygpath -m "$JDK")/bin"

# ---------------------------------------------------------------- Android 工具链
if [ -z "$AIRLOOK_TOOLS" ]; then
    SIB="$(cd "$(dirname "${BASH_SOURCE[0]}")/../android-build-tools" 2>/dev/null && pwd || true)"
    if [ -n "$SIB" ] && [ -d "$SIB/sdk" ]; then
        AIRLOOK_TOOLS="$(cygpath -m "$SIB")"
    else
        AIRLOOK_TOOLS="F:/android-build-tools"
    fi
fi
TOOLS="$AIRLOOK_TOOLS"
BT="$TOOLS/sdk/build-tools/33.0.2"                 # aapt2 / zipalign / apksigner
JAR="$TOOLS/sdk/platforms/android-33/android.jar"  # 编译用的安卓框架类
R8="$TOOLS/r8.jar"                                 # d8 用的编译器

if [ ! -f "$JAR" ]; then
    echo "[X] 找不到 Android SDK：$TOOLS"
    echo "  需要 platform-33 与 build-tools 33.0.2，见 README 的「重新构建」一节。"
    exit 1
fi

# ---------------------------------------------------------------- Python（可选）
# 只用来把 classes.dex 塞进 APK；没有就退回 JDK 自带的 jar（见 build.sh 第 5 步）。
_find_py() {
    if [ -n "$AIRLOOK_PY" ]; then echo "$AIRLOOK_PY"; return; fi
    for c in python python3 py; do
        if command -v "$c" >/dev/null 2>&1; then echo "$c"; return; fi
    done
    return 1
}
PY="$(_find_py || true)"
