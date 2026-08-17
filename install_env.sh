#!/usr/bin/env bash
# =============================================================================
# 陈律工具箱 · 编译环境「持久化」安装
# -----------------------------------------------------------------------------
# 把 `source setup_env.sh` 写入常用 shell 的 profile，
# 使得之后「新开的终端窗口」自动具备编译 APK 的环境，不再失效。
#
# 用法：
#     bash /workspace/MdToGongwen/install_env.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="$SCRIPT_DIR/setup_env.sh"

if [ ! -f "$SETUP" ]; then
    echo "✗ 找不到 $SETUP，请先确保 setup_env.sh 存在。" >&2
    exit 1
fi

# 要写入 profile 的文件（按需自动创建）
PROFILES=(
    "$HOME/.zshenv"     # zsh：所有调用都会读取（最可靠）
    "$HOME/.zshrc"      # zsh：交互式
    "$HOME/.bashrc"     # bash：交互式
    "$HOME/.profile"    # 登录 shell
)

MARKER_BEGIN="# >>> 陈律工具箱 编译环境 (auto) >>>"
MARKER_END="# <<< 陈律工具箱 编译环境 (auto) <<<"
BLOCK="$MARKER_BEGIN\n[ -f \"$SETUP\" ] && source \"$SETUP\"\n$MARKER_END"

for pf in "${PROFILES[@]}"; do
    # 文件不存在则创建
    [ -f "$pf" ] || touch "$pf"
    # 已注入过则跳过（幂等）
    if grep -q "陈律工具箱 编译环境 (auto)" "$pf"; then
        echo "· 已存在，跳过：$pf"
        continue
    fi
    # 追加块（用 printf 保留换行）
    printf '\n%s\n[ -f "%s" ] && source "%s"\n%s\n' \
        "$MARKER_BEGIN" "$SETUP" "$SETUP" "$MARKER_END" >> "$pf"
    echo "✓ 已注入：$pf"
done

echo
echo "完成。新开窗口执行以下命令验证："
echo "    java -version && echo ANDROID_HOME=\$ANDROID_HOME"
echo "或直接编译："
echo "    bash $SCRIPT_DIR/build_apk.sh"
