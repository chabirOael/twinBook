#!/usr/bin/env bash
# Usage: tools/engine-instrument.sh [--no-build] <test class or class#method> [am instrument -e pairs...]
#
# Builds and installs the :engine instrumented-test APK WITHOUT clearing app data (unlike
# ./gradlew connectedDebugAndroidTest, which uninstalls afterwards), runs one test class or
# method with `am instrument` in a fresh process, and prints the result and the
# twinbook-evidence log lines the test wrote. Used by the persistence, update and measurement
# scripts. Class names may omit the io.github.chabiroael.twinbook.engine. package.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

PKG=io.github.chabiroael.twinbook.engine.test
RUNNER="${PKG}/androidx.test.runner.AndroidJUnitRunner"
APK=engine/build/outputs/apk/androidTest/debug/engine-debug-androidTest.apk

build=1
if [ "${1:-}" = "--no-build" ]; then build=0; shift; fi
target="${1:?usage: $0 [--no-build] <class[#method]> [-e key value ...]}"
shift
case "${target}" in
  io.*) ;;
  *) target="io.github.chabiroael.twinbook.engine.${target}" ;;
esac

if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi
if [ "${build}" = 1 ]; then
  ./gradlew -q :engine:assembleDebugAndroidTest ${TWINBOOK_GRADLE_ARGS:-}
  adb install -r -t "${APK}" > /dev/null
fi

adb logcat -c
out="$(adb shell am instrument -w -e class "${target}" "$@" "${RUNNER}" 2>&1 | tr -d '\r')"
echo "${out}" | grep -vE '^\s+at (org\.junit|kotlinx|java|androidx|kotlin\.|android\.|dalvik)' | sed '/^$/d'
echo "--- evidence"
adb logcat -d -s twinbook-evidence:I | grep 'twinbook-evidence' | sed 's/^.*twinbook-evidence: //'
echo "${out}" | grep -qE '^OK \(' || { echo "engine-instrument: test failed or was skipped" >&2; exit 1; }
