#!/usr/bin/env bash
# ============================================================
# setup-toolchain.sh — 重建沙箱构建环境（网关重启/沙箱重置后运行）
#
# 背景：workspace 是持久卷，但 ~/buildtools、~/android-sdk 在容器层，
# 网关重启会被重置。本脚本把 JDK 17 + Android SDK 装进 workspace/toolchain/
# （持久），并写好 local.properties。
#
# 用法: bash scripts/setup-toolchain.sh
# ============================================================
set -euo pipefail

WS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$WS/toolchain"
JDK="$TC/jdk-17.0.2"
SDK="$TC/android-sdk"

mkdir -p "$TC"
cd "$TC"

# ---- 1. JDK 17（华为云镜像） ----
if [ ! -x "$JDK/bin/java" ]; then
  echo "▶ 下载 JDK 17.0.2 ..."
  curl -sL -o jdk17.tar.gz "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_linux-x64_bin.tar.gz"
  tar xzf jdk17.tar.gz && rm jdk17.tar.gz
fi
echo "✅ JDK: $($JDK/bin/java -version 2>&1 | head -1)"

# ---- 2. Android cmdline-tools ----
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "▶ 下载 cmdline-tools ..."
  curl -sL -o tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p "$SDK/cmdline-tools"
  cd "$SDK/cmdline-tools"
  "$JDK/bin/jar" xf ../../tools.zip
  rm -f ../../tools.zip
  mv cmdline-tools latest
  chmod +x latest/bin/*
fi

# ---- 3. platform + build-tools ----
export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export PATH="$PATH:$SDK/cmdline-tools/latest/bin"
if [ ! -d "$SDK/platforms/android-35" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
  yes | sdkmanager --licenses > /dev/null 2>&1 || true
  sdkmanager "platforms;android-35" "build-tools;34.0.0" "platform-tools"
fi
echo "✅ SDK: platforms/android-35 + build-tools/34.0.0"

# ---- 4. local.properties ----
echo "sdk.dir=$SDK" > "$WS/local.properties"
echo "✅ local.properties: sdk.dir=$SDK"
echo "全部就绪。构建命令："
echo "  cd $WS && LANG=C.utf8 LC_ALL=C.utf8 JAVA_HOME=$JDK ANDROID_HOME=$SDK ./gradlew assembleDebug --no-daemon"

# ---- 5. gradle.properties 最省资源配置（沙箱 3.4GB 内存） ----
if ! grep -q "kotlin.compiler.execution.strategy" "$WS/gradle.properties" 2>/dev/null; then
  cat >> "$WS/gradle.properties" << 'PROP'
org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m -Dfile.encoding=UTF-8
org.gradle.workers.max=1
org.gradle.daemon=false
kotlin.compiler.execution.strategy=in-process
android.suppressUnsupportedCompileSdk=35
PROP
  echo "✅ gradle.properties 已追加省内存配置"
fi

# ---- 6. gh CLI（gh-proxy 加速） ----
if [ ! -x "$TC/gh_2.97.0_linux_amd64/bin/gh" ]; then
  echo "▶ 下载 gh CLI（gh-proxy 加速）..."
  curl -sL -o gh.tar.gz "https://gh-proxy.com/https://github.com/cli/cli/releases/download/v2.97.0/gh_2.97.0_linux_amd64.tar.gz"
  tar xzf gh.tar.gz && rm gh.tar.gz
fi
echo "✅ gh: $($TC/gh_2.97.0_linux_amd64/bin/gh --version | head -1)"
echo "提示：GitHub token 需重新认证 → $TC/gh_2.97.0_linux_amd64/bin/gh auth login --with-token"

# ---- 7. GitHub 认证恢复（token 持久化在 workspace/.env，防沙箱重置丢失） ----
if [ -f "$WS/.env" ] && grep -q "^GH_TOKEN=" "$WS/.env"; then
  TOKEN="$(grep "^GH_TOKEN=" "$WS/.env" | cut -d= -f2)"
  if ! "$TC/gh_2.97.0_linux_amd64/bin/gh" auth status > /dev/null 2>&1; then
    echo "$TOKEN" | "$TC/gh_2.97.0_linux_amd64/bin/gh" auth login --with-token
    "$TC/gh_2.97.0_linux_amd64/bin/gh" auth setup-git
    echo "✅ GitHub 认证已恢复（token 来自 workspace/.env）"
  fi
fi
