#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${LLVM_MINGW_ROOT:-$ROOT_DIR/out/_cache/llvm-mingw-20250920-ucrt-macos-universal}"
CC="$TOOLCHAIN/bin/arm64ec-w64-mingw32-gcc"
SOURCE="$ROOT_DIR/tools/win-probes/dllu_wow_launcher.c"
OUTPUT="$ROOT_DIR/app/src/main/assets/winhandler/frontmage-wow-launcher.exe"

if [[ ! -x "$CC" ]]; then
  echo "Missing ARM64EC compiler: $CC" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"
"$CC" -O2 -s -municode -mwindows -Wall -Wextra -o "$OUTPUT" "$SOURCE"
shasum -a 256 "$OUTPUT"
