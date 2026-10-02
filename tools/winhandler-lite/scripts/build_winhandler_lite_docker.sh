#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SRC="${PROJ_DIR}/src/winhandler-lite.c"
OUT_DIR="${PROJ_DIR}/out"
OUT_EXE="${OUT_DIR}/winhandler-lite.exe"

mkdir -p "${OUT_DIR}"

IMAGE="dockcross/windows-arm64:latest"

docker run --rm \
  -v "${PROJ_DIR}:/src" \
  "${IMAGE}" \
  bash -lc "set -euo pipefail; \
    \${CC:-aarch64-w64-mingw32-gcc} -O2 -s -municode -o /src/out/winhandler-lite.exe /src/src/winhandler-lite.c -lws2_32 -lshell32 -lpsapi; \
    file /src/out/winhandler-lite.exe"

echo "Built: ${OUT_EXE}"
