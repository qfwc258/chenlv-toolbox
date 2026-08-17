#!/usr/bin/env bash
#
# 一键构建 release APK 并自动提交、推送到 Gitee。
# APK 被 .gitignore 的 build/ 规则排除，故用 git add -f 强制纳入。
#
# 用法：
#   export GITEE_TOKEN=你的推送令牌   # 可选；未设置则运行时提示输入（不回显）
#   ./publish_apk.sh
#
set -euo pipefail
cd "$(dirname "$0")"

# ---- 读取 Gitee 推送令牌 ----
TOKEN="${GITEE_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  echo "未设置环境变量 GITEE_TOKEN，请输入 Gitee 推送令牌："
  read -r -s TOKEN
  echo
fi

# ---- 1. 构建 release APK ----
echo "==> 构建 release APK ..."
./gradlew --offline assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "错误：未找到 APK 文件 $APK" >&2
  exit 1
fi

# ---- 2. 强制纳入 APK（被 .gitignore 排除，必须 -f）----
git add -f "$APK"

# APK 相对上次无变化则跳过提交，仅同步代码即可
if git diff --cached --quiet; then
  echo "==> APK 内容无变化，无需提交。"
  exit 0
fi

# ---- 3. 提交 ----
MSG="chore: 自动更新 release APK ($(date '+%Y-%m-%d %H:%M'))"
git commit -m "$MSG"

# ---- 4. 推送到 Gitee（令牌通过 insteadOf 临时注入，不写入仓库/历史）----
echo "==> 推送到 Gitee ..."
git -c "url.https://xlking:${TOKEN}@gitee.com/.insteadOf=git@gitee.com:" push origin master

echo "==> 完成：APK 已构建并提交推送到 Gitee。"
