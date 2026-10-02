#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
python3 scripts/fetch-runtime-assets.py
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
./gradlew :app:assembleDebug "$@"
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
