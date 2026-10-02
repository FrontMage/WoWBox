#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/tools/win-probes/ime_contract_probe.c"
OUT="$ROOT/tools/win-probes/out"
DEFAULT_LLVM="$HOME/Downloads/TurtleWoW/third_party/llvm-mingw/llvm-mingw-20260324-ucrt-macos-universal"
LLVM_MINGW_ROOT="${LLVM_MINGW_ROOT:-$DEFAULT_LLVM}"

mkdir -p "$OUT"

build_one() {
  local target="$1"
  local name="$2"
  local cc="$LLVM_MINGW_ROOT/bin/${target}-clang"
  test -x "$cc"
  "$cc" -O2 -municode -mwindows -Wall -Wextra -o "$OUT/$name" "$SRC" -luser32 -lkernel32
  file "$OUT/$name"
  shasum -a 256 "$OUT/$name"
}

build_one aarch64-w64-mingw32 ime-contract-probe-arm64.exe
build_one x86_64-w64-mingw32 ime-contract-probe-amd64.exe

if [[ "${INSTALL_ASSET:-0}" == "1" ]]; then
  ASSET_DIR="$ROOT/app/src/main/assets/winhandler"
  mkdir -p "$ASSET_DIR"
  cp "$OUT/ime-contract-probe-arm64.exe" "$ASSET_DIR/ime-contract-probe-arm64.exe"
  cp "$OUT/ime-contract-probe-amd64.exe" "$ASSET_DIR/ime-contract-probe-amd64.exe"
  cmp "$OUT/ime-contract-probe-arm64.exe" "$ASSET_DIR/ime-contract-probe-arm64.exe"
  cmp "$OUT/ime-contract-probe-amd64.exe" "$ASSET_DIR/ime-contract-probe-amd64.exe"
fi
