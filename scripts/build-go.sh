#!/usr/bin/env bash
# 从上游 ds2api 仓库重新构建 Android 原生服务端（libds2api.so）与内置 WebUI 静态资源。
# 用法: ./scripts/build-go.sh [上游git引用，默认 main]
set -euo pipefail

REF="${1:-main}"
WORKDIR="$(mktemp -d)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo ">> 克隆 ouqiting/ds2api ($REF)"
git clone --depth 1 --branch "$REF" https://github.com/ouqiting/ds2api "$WORKDIR/ds2api" 2>/dev/null \
  || { git clone --depth 1 https://github.com/ouqiting/ds2api "$WORKDIR/ds2api"; }

cd "$WORKDIR/ds2api"

echo ">> 应用 Android 兼容补丁"
for p in "$ROOT"/patches/*.patch; do
  [ -e "$p" ] || continue
  git apply "$p" && echo "   applied $(basename "$p")"
done

echo ">> 构建 WebUI 静态资源"
npm ci --prefix webui --no-audit --no-fund
npm run build --prefix webui   # 产物输出到 static/admin

echo ">> 交叉编译 GOOS=android GOARCH=arm64"
VERSION="$(cat VERSION | tr -d '[:space:]')"
echo "   注入版本号: $VERSION"
CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -trimpath -ldflags="-s -w -X ds2api/internal/version.BuildVersion=$VERSION" \
  -o "$WORKDIR/libds2api.so" ./cmd/ds2api

echo ">> 拷贝产物到 Android 工程"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a" "$ROOT/app/src/main/assets/webui"
cp "$WORKDIR/libds2api.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/libds2api.so"
rm -rf "$ROOT/app/src/main/assets/webui"
cp -r static/admin "$ROOT/app/src/main/assets/webui"
cp "$WORKDIR/ds2api/LICENSE" "$ROOT/UPSTREAM-LICENSE" 2>/dev/null || true

echo ">> 完成: jniLibs/arm64-v8a/libds2api.so 与 assets/webui 已更新"
rm -rf "$WORKDIR"
