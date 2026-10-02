#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/tools/winhandler-lite/src/winhandler-lite.c"
OUT="$ROOT/tools/winhandler-lite/out/winhandler-lite.exe"
ASSET="$ROOT/app/src/main/assets/winhandler/winhandler-lite.exe"
DEFAULT_LLVM="$HOME/Downloads/TurtleWoW/third_party/llvm-mingw/llvm-mingw-20260324-ucrt-macos-universal"
LLVM_MINGW_ROOT="${LLVM_MINGW_ROOT:-$DEFAULT_LLVM}"
CC="$LLVM_MINGW_ROOT/bin/aarch64-w64-mingw32-clang"

mkdir -p "$(dirname "$OUT")"
"$CC" -O2 -municode -mwindows -Wall -Wextra -o "$OUT" "$SRC" -lws2_32 -lshell32 -lpsapi
file "$OUT"
shasum -a 256 "$OUT"

if [[ "${INSTALL_ASSET:-0}" == "1" ]]; then
  mkdir -p "$(dirname "$ASSET")"
  cp "$OUT" "$ASSET"
  cmp "$OUT" "$ASSET"
  shasum -a 256 "$ASSET"
fi
