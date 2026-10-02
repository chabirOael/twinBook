#!/usr/bin/env bash
# Usage: tools/connected-test.sh [extra Gradle arguments]
#
# Runs the instrumented tests (app/src/androidTest) on the running device (ANDROID_SERIAL,
# default: the emulator from tools/emulator-start.sh). Reports land in
# app/build/reports/androidTests/connected/.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi

./gradlew connectedDebugAndroidTest "$@"
