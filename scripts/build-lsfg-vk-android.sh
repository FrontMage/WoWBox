#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_URL="https://github.com/GameNative/lsfg-vk-android.git"
SOURCE_COMMIT="feb7899a6d0f419e27bf47102b6df8ff17e50122"
EXPECTED_LIBRARY_SHA256="8c31f59a8be33b2da87013ca3076ddb2bd2e2819fa25b131d67b9ae6d385d25e"
EXPECTED_ARCHIVE_SHA256="bda4ecddfd5af21f786d51a642ee0196c56879088725feff9407a88e33e865f8"
NDK_VERSION="${ANDROID_NDK_VERSION:-27.0.12077973}"
NDK_ROOT="${ANDROID_NDK_ROOT:-${HOME}/Library/Android/sdk/ndk/${NDK_VERSION}}"
HOST_VULKAN_INCLUDE="${HOST_VULKAN_INCLUDE:-/opt/homebrew/include}"
OUTPUT="${1:-${ROOT_DIR}/app/src/main/assets/frame_generation/lsfg-vk-feb7899-wowbox-route-v3-8c31f59a.tzst}"
PATCH_FILE="${ROOT_DIR}/tools/lsfg-vk-android/wowbox-allowlist-fail-open.patch"
ROUTE_PATCH_FILE="${ROOT_DIR}/tools/lsfg-vk-android/wowbox-route-isolation.patch"
PROMOTION_PATCH_FILE="${ROOT_DIR}/tools/lsfg-vk-android/wowbox-present-route-promotion.patch"
MANIFEST_FILE="${ROOT_DIR}/tools/lsfg-vk-android/VkLayer_LS_frame_generation.json"

for command_name in git cmake ninja zstd tar c++ shasum awk; do
    command -v "${command_name}" >/dev/null 2>&1 || {
        echo "Missing required command: ${command_name}" >&2
        exit 1
    }
done

TOOLCHAIN="${NDK_ROOT}/build/cmake/android.toolchain.cmake"
LLVM_BIN="${NDK_ROOT}/toolchains/llvm/prebuilt/darwin-x86_64/bin"
if [[ ! -f "${TOOLCHAIN}" ||
        ! -x "${LLVM_BIN}/llvm-strip" ||
        ! -x "${LLVM_BIN}/llvm-objcopy" ]]; then
    echo "Android NDK ${NDK_VERSION} is unavailable at ${NDK_ROOT}" >&2
    exit 1
fi
if [[ ! -f "${HOST_VULKAN_INCLUDE}/vulkan/vulkan_core.h" ]]; then
    echo "Host Vulkan headers are unavailable at ${HOST_VULKAN_INCLUDE}" >&2
    exit 1
fi

TEMP_ROOT="${TMPDIR:-/tmp}"
TEMP_ROOT="${TEMP_ROOT%/}"
WORK_DIR="$(mktemp -d "${TEMP_ROOT}/wowbox-lsfg.XXXXXX")"
cleanup() {
    rm -rf -- "${WORK_DIR}"
}
trap cleanup EXIT

SOURCE_DIR="${WORK_DIR}/source"
BUILD_DIR="${WORK_DIR}/build"
STAGE_DIR="${WORK_DIR}/stage"
SOURCE_FLAGS="-DVK_USE_PLATFORM_ANDROID_KHR -ffile-prefix-map=${SOURCE_DIR}=. -fmacro-prefix-map=${SOURCE_DIR}=."

git clone --quiet --recurse-submodules "${SOURCE_URL}" "${SOURCE_DIR}"
git -C "${SOURCE_DIR}" checkout --quiet "${SOURCE_COMMIT}"
git -C "${SOURCE_DIR}" submodule update --init --recursive
git -C "${SOURCE_DIR}" apply --check "${PATCH_FILE}"
git -C "${SOURCE_DIR}" apply "${PATCH_FILE}"
git -C "${SOURCE_DIR}" apply --check "${ROUTE_PATCH_FILE}"
git -C "${SOURCE_DIR}" apply "${ROUTE_PATCH_FILE}"
git -C "${SOURCE_DIR}" apply --check "${PROMOTION_PATCH_FILE}"
git -C "${SOURCE_DIR}" apply "${PROMOTION_PATCH_FILE}"

c++ -std=c++20 -Wall -Wextra -Werror \
    -I "${SOURCE_DIR}/include" \
    "${ROOT_DIR}/tools/lsfg-vk-android/application_filter_test.cpp" \
    -o "${WORK_DIR}/application-filter-test"
"${WORK_DIR}/application-filter-test"

c++ -std=c++20 -Wall -Wextra -Werror \
    -I "${SOURCE_DIR}/include" \
    -I "${HOST_VULKAN_INCLUDE}" \
    "${ROOT_DIR}/tools/lsfg-vk-android/dispatch_registry_test.cpp" \
    "${SOURCE_DIR}/src/dispatch_registry.cpp" \
    -o "${WORK_DIR}/dispatch-registry-test"
"${WORK_DIR}/dispatch-registry-test"

cmake -S "${SOURCE_DIR}" -B "${BUILD_DIR}" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DLSFGVK_ANDROID_WINE=ON \
    -DCMAKE_BUILD_TYPE=Release \
    "-DCMAKE_C_FLAGS=${SOURCE_FLAGS}" \
    "-DCMAKE_CXX_FLAGS=${SOURCE_FLAGS}"
cmake --build "${BUILD_DIR}" --target lsfg-vk -j4

mkdir -p \
    "${STAGE_DIR}/usr/lib" \
    "${STAGE_DIR}/usr/share/vulkan/implicit_layer.d" \
    "${STAGE_DIR}/usr/share/licenses/lsfg-vk-android"
"${LLVM_BIN}/llvm-strip" --strip-unneeded \
    -o "${STAGE_DIR}/usr/lib/liblsfg-vk-layer.so" \
    "${BUILD_DIR}/liblsfg-vk.so"
"${LLVM_BIN}/llvm-objcopy" --remove-section=.note.gnu.build-id \
    "${STAGE_DIR}/usr/lib/liblsfg-vk-layer.so"
cp "${MANIFEST_FILE}" \
    "${STAGE_DIR}/usr/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json"
cp "${SOURCE_DIR}/LICENSE.md" \
    "${STAGE_DIR}/usr/share/licenses/lsfg-vk-android/LICENSE.md"
chmod 0755 "${STAGE_DIR}/usr/lib/liblsfg-vk-layer.so"
find "${STAGE_DIR}" -type f ! -name liblsfg-vk-layer.so -exec chmod 0644 {} +
find "${STAGE_DIR}" -exec touch -t 197001010000 {} +

mkdir -p "$(dirname "${OUTPUT}")"
ARCHIVE_TMP="${OUTPUT}.new"
unlink "${ARCHIVE_TMP}" 2>/dev/null || true
tar --uid 0 --gid 0 --uname root --gname root \
    -C "${STAGE_DIR}" -cf - usr |
    zstd -19 -T0 -q -o "${ARCHIVE_TMP}"
LIBRARY_SHA256="$(shasum -a 256 "${STAGE_DIR}/usr/lib/liblsfg-vk-layer.so" | awk '{print $1}')"
ARCHIVE_SHA256="$(shasum -a 256 "${ARCHIVE_TMP}" | awk '{print $1}')"
if [[ "${LIBRARY_SHA256}" != "${EXPECTED_LIBRARY_SHA256}" ]]; then
    echo "Unexpected LSFG library SHA-256: ${LIBRARY_SHA256}" >&2
    exit 1
fi
if [[ "${ARCHIVE_SHA256}" != "${EXPECTED_ARCHIVE_SHA256}" ]]; then
    echo "Unexpected LSFG archive SHA-256: ${ARCHIVE_SHA256}" >&2
    exit 1
fi
mv "${ARCHIVE_TMP}" "${OUTPUT}"

echo "asset=${OUTPUT}"
echo "${ARCHIVE_SHA256}  ${OUTPUT}"
echo "${LIBRARY_SHA256}  ${STAGE_DIR}/usr/lib/liblsfg-vk-layer.so"
"${LLVM_BIN}/llvm-readelf" -h -d \
    "${STAGE_DIR}/usr/lib/liblsfg-vk-layer.so" |
    grep -E 'Class:|Machine:|NEEDED|SONAME'
