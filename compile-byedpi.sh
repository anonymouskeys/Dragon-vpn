#!/usr/bin/env bash
set -euo pipefail

: "${NDK_HOME:?NDK_HOME is required}"
VERSION="v0.17.3"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fsSL "https://github.com/hufrea/byedpi/archive/refs/tags/${VERSION}.tar.gz" -o "$WORK/byedpi.tar.gz"
tar -xzf "$WORK/byedpi.tar.gz" -C "$WORK"
SRC="$WORK/byedpi-${VERSION#v}"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="$PWD/V2rayNG/app/libs"

build_one() {
  local abi="$1" triple="$2" api="$3"
  mkdir -p "$OUT/$abi"
  "$TOOLCHAIN/${triple}${api}-clang" \
    -D_DEFAULT_SOURCE -std=c99 -O2 -fPIE -pie \
    -Wall -Wextra -Wno-unused -Wno-unused-parameter \
    "$SRC/packets.c" "$SRC/main.c" "$SRC/conev.c" \
    "$SRC/proxy.c" "$SRC/desync.c" "$SRC/mpool.c" "$SRC/extend.c" \
    -I"$SRC" -Wl,--build-id=none \
    -o "$OUT/$abi/libciadpi.so"
  chmod 755 "$OUT/$abi/libciadpi.so"
  file "$OUT/$abi/libciadpi.so"
}

build_one arm64-v8a aarch64-linux-android 21
build_one armeabi-v7a armv7a-linux-androideabi 21
build_one x86 i686-linux-android 21
build_one x86_64 x86_64-linux-android 21
