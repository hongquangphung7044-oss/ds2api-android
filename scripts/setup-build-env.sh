#!/usr/bin/env bash
# DS2API Android 构建环境一键部署（Debian/Ubuntu, x86_64 / aarch64）
# 幂等：已安装的组件自动跳过。默认安装到 /opt，可用 PREFIX=/path 覆盖。
set -euo pipefail

PREFIX="${PREFIX:-/opt}"
GO_VERSION="${GO_VERSION:-1.26.5}"
NODE_VERSION="${NODE_VERSION:-22.17.0}"
SDK_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
ARM64_SDK_VER="37.0.0"   # 仅 aarch64: 第三方 arm64 aapt2

log() { echo ">> $*"; }
need() { command -v "$1" >/dev/null 2>&1; }

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)  GO_ARCH=amd64;  NODE_ARCH=x64;   JDK_ARCH=x64 ;;
  aarch64) GO_ARCH=arm64;  NODE_ARCH=arm64; JDK_ARCH=aarch64 ;;
  *) echo "不支持的架构: $ARCH"; exit 1 ;;
esac

# ---------- 1. Go ----------
if need go && go version | grep -q "go${GO_VERSION%%.*}"; then
  log "Go 已安装: $(go version)"
else
  log "安装 Go $GO_VERSION ($GO_ARCH)"
  curl -sLO "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz"
  tar -C /usr/local -xzf "go${GO_VERSION}.linux-${GO_ARCH}.tar.gz"
  rm -f "go${GO_VERSION}.linux-${GO_ARCH}.tar.gz"
fi
export PATH=/usr/local/go/bin:$PATH

# ---------- 2. Node.js ----------
if need node && [ "$(node -v)" = "v${NODE_VERSION}" ]; then
  log "Node 已安装: $(node -v)"
else
  log "安装 Node $NODE_VERSION ($NODE_ARCH)"
  curl -sLO "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.gz"
  tar -C /usr/local -xzf "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.gz"
  ln -sf "/usr/local/node-v${NODE_VERSION}-linux-${NODE_ARCH}/bin/node" /usr/local/bin/node
  ln -sf "/usr/local/node-v${NODE_VERSION}-linux-${NODE_ARCH}/bin/npm"  /usr/local/bin/npm
  ln -sf "/usr/local/node-v${NODE_VERSION}-linux-${NODE_ARCH}/bin/npx"  /usr/local/bin/npx
  rm -f "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.gz"
fi

# ---------- 3. JDK 17 (Temurin) ----------
if [ -x "$PREFIX/jdk17/bin/java" ]; then
  log "JDK 已安装: $PREFIX/jdk17"
else
  log "安装 Temurin JDK 17 ($JDK_ARCH)"
  curl -sL -o temurin17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse"
  tar -C "$PREFIX" -xzf temurin17.tar.gz
  ln -sfn "$PREFIX"/jdk-17* "$PREFIX/jdk17"
  rm -f temurin17.tar.gz
fi
export JAVA_HOME="$PREFIX/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"

# ---------- 4. Android SDK ----------
export ANDROID_HOME="${ANDROID_HOME:-$PREFIX/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKM" ]; then
  log "Android cmdline-tools 已安装"
else
  log "安装 Android cmdline-tools"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -sLO "https://dl.google.com/android/repository/${SDK_TOOLS_ZIP}"
  python3 -c "import zipfile; zipfile.ZipFile('${SDK_TOOLS_ZIP}').extractall('${ANDROID_HOME}/cmdline-tools')"
  rm -f "$SDK_TOOLS_ZIP"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  chmod -R +x "$ANDROID_HOME/cmdline-tools/latest/bin"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

if [ -d "$ANDROID_HOME/platforms/android-35" ]; then
  log "Android platform-35 / build-tools 已安装"
else
  log "安装 platform-35 与 build-tools"
  yes | "$SDKM" --licenses >/dev/null 2>&1 || true
  "$SDKM" "platforms;android-35" "build-tools;35.0.0"
fi

# ---------- 5. aarch64: arm64 aapt2 覆盖 ----------
if [ "$ARCH" = "aarch64" ]; then
  AAPT2="$PREFIX/arm64-sdk/android-sdk/build-tools/${ARM64_SDK_VER}/aapt2"
  if [ -x "$AAPT2" ]; then
    log "arm64 aapt2 已安装"
  else
    log "下载 arm64 构建工具（官方仅 x86_64，aarch64 主机必须替换 aapt2）"
    curl -sLO "https://github.com/HomuHomu833/android-sdk-custom/releases/download/${ARM64_SDK_VER}/android-sdk-aarch64-linux-gnu.tar.xz"
    mkdir -p "$PREFIX/arm64-sdk"
    python3 -c "import tarfile; tarfile.open('android-sdk-aarch64-linux-gnu.tar.xz').extractall('$PREFIX/arm64-sdk', filter='tar')"
    chmod +x "$AAPT2" || true
    rm -f android-sdk-aarch64-linux-gnu.tar.xz
  fi
  mkdir -p ~/.gradle
  if grep -q aapt2FromMavenOverride ~/.gradle/gradle.properties 2>/dev/null; then
    log "aapt2 覆盖已配置"
  else
    echo "android.aapt2FromMavenOverride=$AAPT2" >> ~/.gradle/gradle.properties
    log "已写入 ~/.gradle/gradle.properties: aapt2 覆盖 -> $AAPT2"
  fi
fi

# ---------- 6. 环境变量持久化 ----------
MARK="# ds2api-android build env"
if ! grep -q "$MARK" ~/.bashrc 2>/dev/null; then
  {
    echo "$MARK"
    echo "export JAVA_HOME=$PREFIX/jdk17"
    echo "export ANDROID_HOME=$ANDROID_HOME"
    echo "export ANDROID_SDK_ROOT=$ANDROID_HOME"
    echo "export PATH=/usr/local/go/bin:\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
  } >> ~/.bashrc
  log "环境变量已写入 ~/.bashrc（重开终端或 source ~/.bashrc 生效）"
fi

log "完成。验证: go version / node -v / java -version / sdkmanager --version"
log "构建: ./gradlew assembleRelease"
