#!/usr/bin/env bash
# =============================================================================
# 陈律工具箱 · 一键编译 release APK（含自动版本号 + 自动提交 Gitee）
# -----------------------------------------------------------------------------
# 依赖：setup_env.sh 已就绪（本脚本会自动 source 它）。
# 用法：
#     bash /workspace/chenlv-toolbox-full-source/build_apk.sh
# 产物：
#     本地：   陈律工具箱.apk（便于直接安装/调试）
#     仓库：   release/陈律工具箱_vX.Y.Z.apk（已加入 .gitignore 白名单，随源码提交）
# 版本号：versionCode / versionName 由 app/build.gradle.kts 按 git 提交数自动计算。
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 自动加载编译环境（新窗口未注入 JAVA_HOME 时也能编译）
source "$SCRIPT_DIR/setup_env.sh"

PROJECT_DIR="$(cd "$SCRIPT_DIR" && pwd)"
cd "$PROJECT_DIR"

# ---------- 版本号（与 app/build.gradle.kts 公式一致，参数统一从 gradle.properties 读取） ----------
# versionCode = git 提交数（单调递增）；versionName 自动派生：
# 三段均为个位数(0–9)。以「提交数 - VERSION_BASE_COMMIT」为发布序号，按 base-10 拆成三位：
# 百位→major、十位→minor、个位→patch；满 1000（即 9.9.9）归零循环，
# 保证三段永远 ≤9、不超过 10。
VERSION_CODE="$(git rev-list --count HEAD 2>/dev/null || echo 1)"
VERSION_BASE_COMMIT="$(grep '^VERSION_BASE_COMMIT=' gradle.properties | cut -d= -f2)"
VERSION_MAJOR_BASE="$(grep '^VERSION_MAJOR_BASE=' gradle.properties | cut -d= -f2)"
if [ "$VERSION_CODE" -gt "$VERSION_BASE_COMMIT" ]; then
    DELTA=$(( VERSION_CODE - VERSION_BASE_COMMIT ))
else
    DELTA=0
fi
# 单一递增整数 v = DELTA + 首位基数×100；三位各自按 base-10 进位（首位亦自动递增）
V=$(( DELTA + VERSION_MAJOR_BASE * 100 ))
VERSION_MAJOR=$(( (V / 100) % 10 ))
VERSION_MINOR=$(( (V / 10) % 10 ))
VERSION_PATCH=$(( V % 10 ))
VERSION_NAME="${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}"
echo ">>> 版本号：v$VERSION_NAME (build $VERSION_CODE)"

echo ">>> 项目目录：$PROJECT_DIR"
echo ">>> 开始编译 release APK ..."

# 显式传入 ANDROID_HOME，确保 gradle 一定能定位 SDK
ANDROID_HOME="$ANDROID_HOME" "$PROJECT_DIR/gradlew" assembleRelease --no-daemon

APK_SRC="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_SRC" ]; then
    echo "✗ 编译成功但未找到产物：$APK_SRC" >&2
    exit 1
fi

# 本地副本（*.apk 被 gitignore，仅本地使用，便于安装/调试）
LOCAL_APK="$PROJECT_DIR/陈律工具箱.apk"
cp -f "$APK_SRC" "$LOCAL_APK"

# 版本化副本，放入 release/（.gitignore 白名单，随仓库提交，可直接下载安装）
RELEASE_DIR="$PROJECT_DIR/release"
mkdir -p "$RELEASE_DIR"
# 先清掉旧版本 APK，保证仓库内只保留最新一个
rm -f "$RELEASE_DIR"/陈律工具箱_v*.apk
RELEASE_APK="$RELEASE_DIR/陈律工具箱_v${VERSION_NAME}.apk"
cp -f "$APK_SRC" "$RELEASE_APK"

echo ">>> 完成："
echo "    本地：  $LOCAL_APK  ($(du -h "$LOCAL_APK" | cut -f1))"
echo "    仓库：  $RELEASE_APK  ($(du -h "$RELEASE_APK" | cut -f1))"

# ---------- 自动提交源码 + APK 到 Gitee ----------
# Gitee 令牌存放于本地 git config（gitee.token），不随仓库提交，避免泄露。
GITEE_TOKEN="$(git config --get gitee.token 2>/dev/null || true)"
REMOTE="https://xlking:${GITEE_TOKEN}@gitee.com/xlking/chenlv-toolbox.git"

if git diff --quiet && git diff --cached --quiet && [ -z "$(git status --porcelain)" ]; then
    echo ">>> 无变更，跳过提交。"
else
    git add -u
    git add "$RELEASE_DIR"/*.apk
    git -c user.name="陈律工具箱" -c user.email="dev@chenlv.local" \
        commit -m "build: 陈律工具箱 v$VERSION_NAME (自动版本号 + 提交 APK)"
    echo ">>> 已提交：陈律工具箱 v$VERSION_NAME"
    if [ -n "$GITEE_TOKEN" ]; then
        git push "$REMOTE" master 2>&1 | tail -5
        echo ">>> 已推送到 Gitee。"
    else
        echo ">>> 未配置 gitee.token，跳过推送（本地已提交）。"
    fi
fi
