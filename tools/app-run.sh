#!/usr/bin/env bash
# Usage: tools/app-run.sh
#
# Builds and installs the debug APK on the device (ANDROID_SERIAL, default: the emulator
# from tools/emulator-start.sh), then launches the main activity and waits for it to draw.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi

./gradlew :app:installDebug
adb shell am start -W -n io.github.chabiroael.twinbook.debug/io.github.chabiroael.twinbook.MainActivity
