#!/usr/bin/env bash
# =============================================================================
# 陈律工具箱 · Android 编译环境初始化脚本
# -----------------------------------------------------------------------------
# 作用：固化编译 APK 所需的全部环境变量（JAVA_HOME / ANDROID_HOME / PATH），
#       不依赖 sdkman 的临时注入，使 gradle 在「任何新开的 shell 窗口」中都能编译。
#
# 用法（在当前 shell 中使其生效）：
#     source /workspace/MdToGongwen/setup_env.sh
#   或  . /workspace/MdToGongwen/setup_env.sh
#
# 幂等：重复 source 不会污染 PATH，也不会报错。
# =============================================================================

# ---- 1. Android SDK（同时设置两个标准变量，AGP 都能识别） ----
export ANDROID_HOME=/root/android-sdk
export ANDROID_SDK_ROOT=/root/android-sdk

# ---- 2. Java（sdkman 的 Zulu 20） ----
# 解析真实路径，彻底摆脱对 sdkman 注入 JAVA_HOME 的依赖。
if [ -e /root/.sdkman/candidates/java/current ]; then
    export JAVA_HOME="$(readlink -f /root/.sdkman/candidates/java/current)"
elif [ -z "${JAVA_HOME:-}" ]; then
    # 兜底：若 sdkman 目录不存在且无现成 JAVA_HOME，尝试系统 java
    if command -v java >/dev/null 2>&1; then
        export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    fi
fi

# ---- 3. PATH 追加（幂等，避免重复） ----
_add_path() {
    case ":$PATH:" in
        *":$1:"*) ;;                         # 已存在则跳过
        *) export PATH="$1:$PATH" ;;         # 否则前置插入
    esac
}
[ -n "${JAVA_HOME:-}" ]           && _add_path "$JAVA_HOME/bin"
_add_path "$ANDROID_HOME/platform-tools"
_add_path "$ANDROID_HOME/cmdline-tools/latest/bin"
_add_path "$ANDROID_HOME/build-tools/34.0.0"

# ---- 4. Gradle 中文/UTF-8 友好 ----
export GRADLE_OPTS="-Dfile.encoding=UTF-8 ${GRADLE_OPTS:-}"

# ---- 5. 仅在交互终端打印诊断，便于排查 ----
if [ -t 1 ]; then
    echo "[setup_env] ANDROID_HOME = $ANDROID_HOME"
    echo "[setup_env] JAVA_HOME    = $JAVA_HOME"
    if command -v java >/dev/null 2>&1; then
        echo "[setup_env] java         = $(java -version 2>&1 | head -1)"
    else
        echo "[setup_env] 警告：未找到 java，请检查 JAVA_HOME" >&2
    fi
fi
