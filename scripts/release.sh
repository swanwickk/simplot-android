#!/usr/bin/env bash
# ============================================================
# release.sh — SimPlot Android 发布脚本
#
# 流程（对应硬性规则）：更新 CHANGELOG → bump 版本 → 构建 APK
#   → 提交推送 → 创建 GitHub Release 并上传 APK
#
# 用法:
#   scripts/release.sh <新版本号>          # 如 scripts/release.sh 0.2.0
#
# 前置条件:
#   - 已先更新 CHANGELOG.md（必须包含 [<新版本号>] 条目）
#   - JDK 17+ 与 Android SDK 可用（JAVA_HOME / ANDROID_HOME 或 PATH 中）
#   - gh CLI 已登录；git 已配置 credential helper（gh auth setup-git）
# ============================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

NEW_VER="${1:?用法: scripts/release.sh <新版本号, 如 0.2.0>}"
[[ "$NEW_VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "❌ 版本号格式错误: $NEW_VER (应为 X.Y.Z)"; exit 1; }

# ---- 0. 环境 ----
export LANG=C.utf8 LC_ALL=C.utf8          # 中文 asset 文件名需要 UTF-8 locale
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(readlink -f "$(command -v java)")")/..}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
command -v gh >/dev/null || { echo "❌ 缺少 gh CLI"; exit 1; }
REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo swanwickk/simplot-android)"

# ---- 1. 校验 CHANGELOG 已更新（硬性规则①）----
grep -q "^## \[$NEW_VER\]" CHANGELOG.md || {
  echo "❌ CHANGELOG.md 缺少 [${NEW_VER}] 条目 —— 先按 keep-a-changelog 格式补充再运行"; exit 1;
}

# ---- 2. 读取当前版本并 bump（硬性规则②）----
CUR_VER="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)"
CUR_CODE="$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)"
[[ -n "$CUR_VER" && -n "$CUR_CODE" ]] || { echo "❌ 无法读取 app/build.gradle.kts 版本号"; exit 1; }
echo "▶ 版本 $CUR_VER (code $CUR_CODE) → $NEW_VER (code $((CUR_CODE+1)))"
sed -i "s/versionCode = $CUR_CODE/versionCode = $((CUR_CODE+1))/; s/versionName = \"$CUR_VER\"/versionName = \"$NEW_VER\"/" app/build.gradle.kts

# ---- 3. 构建 APK ----
echo "▶ 构建 APK ..."
./gradlew assembleDebug --no-daemon

# ---- 4. 复制 APK（带版本名；*.apk 已在 .gitignore，不进仓库）----
APK_FILE="$REPO_ROOT/SimPlot-v${NEW_VER}-debug.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$APK_FILE"
echo "▶ APK: $APK_FILE"

# ---- 5. 提交推送（CHANGELOG + 版本号 + 代码一起）----
echo "▶ git commit + push ..."
git add -A
git commit -m "Release v${NEW_VER}" --allow-empty
git push origin main

# ---- 6. 创建 GitHub Release 并上传 APK（硬性规则③）----
echo "▶ 创建 GitHub Release v${NEW_VER} ..."
# 从 CHANGELOG 提取该版本的段落作为 release notes
NOTES="$(awk -v v="[$NEW_VER]" '
  $0 ~ "^## " && index($0, v) { on=1; next }
  on && $0 ~ "^## " { exit }
  on { print }
' CHANGELOG.md)"
gh release create "v${NEW_VER}" "$APK_FILE" \
  --repo "$REPO" \
  --title "SimPlot Android v${NEW_VER}（开发版）" \
  --notes "## ${NEW_VER}

${NOTES}

> ⚠️ 开发版，建议 Android 8.0+（minSdk 26）。完整变更记录见 CHANGELOG.md。"

echo "✅ 发布完成: https://github.com/${REPO}/releases/tag/v${NEW_VER}"
