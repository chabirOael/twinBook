#!/usr/bin/env bash
# Usage: tools/daily-launch.sh
#
# Launches the login-safe `daily` app on the device and waits until it has drawn.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"

if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi
adb shell am start -W -n io.github.chabiroael.twinbook.daily/io.github.chabiroael.twinbook.MainActivity
