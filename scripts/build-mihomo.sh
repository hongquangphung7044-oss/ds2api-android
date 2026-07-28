#!/usr/bin/env bash
# 下载 mihomo 官方 arm64 预编译版，重命名为 libmihomo.so 放入 jniLibs。
# 用法: ./scripts/build-mihomo.sh [版本号，默认 v1.19.29]
#
# mihomo 是纯 Go 构建 (CGO_ENABLED=0 GOOS=linux GOARCH=arm64)，
# Android 内核是 Linux，纯 Go 二进制不依赖 glibc，可直接运行。
# 重命名为 libmihomo.so 是为了利用 Android 的 jniLibs 打包机制，
# 安装时随原生库释放到 nativeLibraryDir，与 libds2api.so 同样的执行机制。
set -euo pipefail

MIHOMO_VERSION="${1:-v1.19.29}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo ">> 下载 mihomo $MIHOMO_VERSION (linux-arm64)"
URL="https://github.com/MetaCubeX/mihomo/releases/download/${MIHOMO_VERSION}/mihomo-linux-arm64-${MIHOMO_VERSION}.gz"
curl -fL -o "$TMP/mihomo.gz" "$URL"

echo ">> 解压"
gunzip "$TMP/mihomo.gz"

echo ">> 校验可执行权限"
chmod 0755 "$TMP/mihomo"

echo ">> 拷贝到 jniLibs (重命名为 libmihomo.so)"
mkdir -p "$OUT_DIR"
cp "$TMP/mihomo" "$OUT_DIR/libmihomo.so"
chmod 0755 "$OUT_DIR/libmihomo.so"

# 验证是 ELF arm64
FILE_TYPE="$(file "$OUT_DIR/libmihomo.so" 2>/dev/null || echo "unknown")"
echo ">> 产物: $OUT_DIR/libmihomo.so"
echo ">> 类型: $FILE_TYPE"
echo ">> 完成"
