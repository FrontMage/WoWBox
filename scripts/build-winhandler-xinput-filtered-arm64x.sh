#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WINE_SOURCE_INPUT="${1:?usage: $0 DLLU_WINE_SOURCE WINE_BUILD_DIR [OUTPUT_DIR]}"
WINE_BUILD_INPUT="${2:?usage: $0 DLLU_WINE_SOURCE WINE_BUILD_DIR [OUTPUT_DIR]}"
OUTPUT_DIR_INPUT="${3:-$ROOT_DIR/app/src/main/assets/winhandler}"
TOOLCHAIN_ROOT="${TOOLCHAIN_ROOT:-$ROOT_DIR/out/_cache/llvm-mingw-20250920-ucrt-macos-universal}"
BRIDGE_REPO="${BRIDGE_REPO:-https://github.com/brunodev85/wine-9.2-custom.git}"
BRIDGE_COMMIT="a4ef2bf8963fe4bfab390eebf73336364ce209bc"
BRIDGE_MAIN_SHA256="b9def9ba88a9663aca2659c76d0693aed8c77f3c540129b0baf86c77f6d1601e"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/frontmage-xinput-filtered.XXXXXX")"
HEADER_PATCH="$ROOT_DIR/tools/wine-patches/dllu-wine-arm64ec-clang-header.patch"
FILTER_PATCH="$ROOT_DIR/tools/wine-patches/winhandler-xinput-process-filter.patch"
HEADER_PATCH_APPLIED=0

cleanup() {
    if [[ "$HEADER_PATCH_APPLIED" == 1 ]]; then
        git -C "$WINE_SOURCE" apply -R "$HEADER_PATCH"
    fi
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

command -v git >/dev/null
command -v shasum >/dev/null
command -v zstd >/dev/null
test -x "$TOOLCHAIN_ROOT/bin/aarch64-w64-mingw32-clang"
test -x "$TOOLCHAIN_ROOT/bin/arm64ec-w64-mingw32-clang"
test -d "$WINE_SOURCE_INPUT/.git"
WINE_SOURCE="$(cd "$WINE_SOURCE_INPUT" && pwd)"
mkdir -p "$WINE_BUILD_INPUT" "$OUTPUT_DIR_INPUT"
WINE_BUILD="$(cd "$WINE_BUILD_INPUT" && pwd)"
OUTPUT_DIR="$(cd "$OUTPUT_DIR_INPUT" && pwd)"
test -f "$WINE_SOURCE/dlls/xinput1_3/xinput1_3.spec"
test -f "$WINE_SOURCE/dlls/xinput1_4/xinput1_4.spec"

git clone -q "$BRIDGE_REPO" "$WORK_DIR/bridge"
git -C "$WORK_DIR/bridge" checkout -q --detach "$BRIDGE_COMMIT"
test "$(shasum -a 256 "$WORK_DIR/bridge/dlls/xinput1_3/main.c" | awk '{print $1}')" = \
    "$BRIDGE_MAIN_SHA256"
git -C "$WORK_DIR/bridge" apply "$FILTER_PATCH"

if git -C "$WINE_SOURCE" apply --check "$HEADER_PATCH" 2>/dev/null; then
    git -C "$WINE_SOURCE" apply "$HEADER_PATCH"
    HEADER_PATCH_APPLIED=1
elif ! git -C "$WINE_SOURCE" apply -R --check "$HEADER_PATCH" 2>/dev/null; then
    echo "Wine ARM64EC header is neither clean nor at the expected patched state" >&2
    exit 1
fi

export PATH="/opt/homebrew/opt/bison/bin:$TOOLCHAIN_ROOT/bin:$PATH"
export SOURCE_DATE_EPOCH
export ZERO_AR_DATE=1

if [[ ! -f "$WINE_BUILD/Makefile" ]]; then
    if [[ ! -x "$WINE_SOURCE/configure" ]]; then
        (cd "$WINE_SOURCE" && ./autogen.sh)
    fi
    (
        cd "$WINE_BUILD"
        aarch64_CC=aarch64-w64-mingw32-clang \
        arm64ec_CC=arm64ec-w64-mingw32-clang \
        "$WINE_SOURCE/configure" \
            --enable-win64 \
            --enable-archs=aarch64,arm64ec \
            --with-mingw=yes \
            --disable-tests \
            --without-x \
            --without-freetype \
            --without-fontconfig \
            --without-gettext \
            --without-gnutls \
            --without-gstreamer \
            --without-pulse \
            --without-sdl
    )
fi

make -C "$WINE_BUILD" -j"${JOBS:-6}" \
    dlls/xinput1_3/aarch64-windows/xinput1_3.dll \
    dlls/xinput1_4/aarch64-windows/xinput1_4.dll \
    dlls/ws2_32/aarch64-windows/libws2_32.a

common_flags=(
    -I"$WINE_BUILD/include"
    -I"$WINE_SOURCE/include"
    -I"$WINE_SOURCE/include/msvcrt"
    -D_UCRT
    -D__WINESRC__
    -D__WINE_PE_BUILD
    -Wall
    -fuse-ld=lld
    --no-default-config
    -fno-strict-aliasing
    -Wdeclaration-after-statement
    -Wempty-body
    -Wignored-qualifiers
    -Winit-self
    -Wno-microsoft-enum-forward-reference
    -Wstrict-prototypes
    -Wtype-limits
    -Wunused-but-set-parameter
    -Wvla
    -Wwrite-strings
    -Wpointer-arith
    -Wabsolute-value
    -ffunction-sections
    -ffp-exception-behavior=maytrap
    "-ffile-prefix-map=$WORK_DIR/bridge=/usr/src/winhandler-xinput"
    -O2
)
bridge_source="$WORK_DIR/bridge/dlls/xinput1_3/main.c"

compile_bridge() {
    local version="$1"
    local arch="$2"
    local compiler="$3"
    local output="$WINE_BUILD/dlls/xinput1_${version}/${arch}-windows/winhandler-main.o"
    local includes=(
        -I"$WINE_BUILD/dlls/xinput1_${version}"
        -I"$WINE_SOURCE/dlls/xinput1_${version}"
    )
    if [[ "$version" == 4 ]]; then
        includes+=( -I"$WINE_SOURCE/dlls/xinput1_3" )
    fi
    "$TOOLCHAIN_ROOT/bin/$compiler" -c -o "$output" "$bridge_source" \
        "${includes[@]}" "${common_flags[@]}" -DXINPUT_VER="$version"
}

compile_bridge 3 aarch64 aarch64-w64-mingw32-clang
compile_bridge 3 arm64ec arm64ec-w64-mingw32-clang
compile_bridge 4 aarch64 aarch64-w64-mingw32-clang
compile_bridge 4 arm64ec arm64ec-w64-mingw32-clang

common_libraries=(
    dlls/hid/aarch64-windows/libhid.a
    dlls/setupapi/aarch64-windows/libsetupapi.a
    dlls/advapi32/aarch64-windows/libadvapi32.a
    dlls/user32/aarch64-windows/libuser32.a
    dlls/ws2_32/aarch64-windows/libws2_32.a
    dlls/winecrt0/aarch64-windows/libwinecrt0.a
    libs/compiler-rt/aarch64-windows/libcompiler-rt.a
    dlls/ucrtbase/aarch64-windows/libucrtbase.a
    dlls/kernel32/aarch64-windows/libkernel32.a
    dlls/ntdll/aarch64-windows/libntdll.a
)

mkdir -p "$WORK_DIR/payload"
for version in 3 4; do
    (
        cd "$WINE_BUILD"
        tools/winegcc/winegcc \
            -o "$WORK_DIR/payload/xinput1_${version}.dll" \
            --wine-objdir . \
            -b arm64ec-w64-mingw32 \
            -marm64x \
            -Wl,--wine-builtin \
            -shared \
            "$WINE_SOURCE/dlls/xinput1_${version}/xinput1_${version}.spec" \
            "dlls/xinput1_${version}/aarch64-windows/winhandler-main.o" \
            "dlls/xinput1_${version}/arm64ec-windows/winhandler-main.o" \
            "dlls/xinput1_${version}/version.res" \
            "${common_libraries[@]}" \
            --no-default-config \
            -Wl,--build-id
    )
done

for name in xinput1_3.dll xinput1_4.dll; do
    dll="$WORK_DIR/payload/$name"
    test "$(dd if="$dll" bs=1 skip=64 count=16 status=none)" = "Wine builtin DLL"
    "$TOOLCHAIN_ROOT/bin/llvm-readobj" --file-headers "$dll" |
        grep -q "Format: COFF-ARM64X"
    chmod 0644 "$dll"
    touch -t 198001010000 "$dll"
done

archive="$WORK_DIR/winhandler-xinput-wow-filtered-arm64x.tzst"
COPYFILE_DISABLE=1 tar \
    --format ustar \
    --uid 0 \
    --gid 0 \
    --uname root \
    --gname root \
    -C "$WORK_DIR/payload" \
    -cf - \
    xinput1_3.dll xinput1_4.dll |
    zstd -19 -T1 -q -o "$archive"

archive_sha="$(shasum -a 256 "$archive" | awk '{print $1}')"
output="$OUTPUT_DIR/winhandler-xinput-wow-filtered-arm64x-${archive_sha:0:8}.tzst"
cp "$archive" "$output"

shasum -a 256 "$WORK_DIR/payload/xinput1_3.dll" \
    "$WORK_DIR/payload/xinput1_4.dll" "$output"
printf '%s\n' "$output"
