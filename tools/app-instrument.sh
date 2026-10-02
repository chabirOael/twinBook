#!/usr/bin/env bash
# Usage: tools/app-instrument.sh [--no-build] <test class or class#method> [am instrument -e pairs...]
#
# Like tools/engine-instrument.sh, for the app's instrumented tests: builds and installs the
# debug app and its test APK WITHOUT clearing app data, runs one test with `am instrument`, and
# prints the result and the twinbook-evidence lines. Targets the debug build only, never the
# daily build. Class names may omit the io.github.chabiroael.twinbook. package.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

TEST_PKG=io.github.chabiroael.twinbook.debug.test
RUNNER="${TEST_PKG}/androidx.test.runner.AndroidJUnitRunner"

build=1
if [ "${1:-}" = "--no-build" ]; then build=0; shift; fi
target="${1:?usage: $0 [--no-build] <class[#method]> [-e key value ...]}"
shift
case "${target}" in
  io.*) ;;
  *) target="io.github.chabiroael.twinbook.${target}" ;;
esac

if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi
if [ "${build}" = 1 ]; then
  ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk > /dev/null
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > /dev/null
fi

adb logcat -c
out="$(adb shell am instrument -w -e class "${target}" "$@" "${RUNNER}" 2>&1 | tr -d '\r')"
echo "${out}" | grep -vE '^\s+at (org\.junit|kotlinx|java|androidx|kotlin\.|android\.|dalvik)' | sed '/^$/d'
echo "--- evidence"
adb logcat -d -s twinbook-evidence:I | grep 'twinbook-evidence' | sed 's/^.*twinbook-evidence: //'
echo "${out}" | grep -qE '^OK \(' || { echo "app-instrument: test failed or was skipped" >&2; exit 1; }
