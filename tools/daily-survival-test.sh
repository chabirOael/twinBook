#!/usr/bin/env bash
# Usage: tools/daily-survival-test.sh
#
# C13: data placed in the daily build survives a full run of tools/connected-test.sh, the M1
# device scripts (persistence-test.sh, extension-update-test.sh) and an update of the daily
# build. A marker file is written into the daily app's private storage with run-as; the
# package's firstInstallTime would change if it were ever uninstalled and installed again.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"
PKG=io.github.chabiroael.twinbook.daily

state() {
  echo "marker: $(adb shell run-as "${PKG}" cat files/twinbook-marker.txt 2>/dev/null | tr -d '\r')"
  adb shell dumpsys package "${PKG}" | grep -E 'firstInstallTime|lastUpdateTime' | tr -d '\r' | sed 's/^ */  /'
  echo "  data entries: $(adb shell run-as "${PKG}" ls files 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
}

adb shell pm list packages "${PKG}" | tr -d '\r' | grep -qx "package:${PKG}" || tools/daily-install.sh
# Open the app once so its private storage and GeckoView profile exist, as after a login.
tools/daily-launch.sh > /dev/null
sleep 10
adb shell input keyevent KEYCODE_HOME
marker="survives-$(date +%s)"
adb shell run-as "${PKG}" sh -c "'mkdir -p files && echo ${marker} > files/twinbook-marker.txt'"
first="$(adb shell dumpsys package "${PKG}" | grep firstInstallTime | tr -d '\r' | sed 's/^ *//')"
echo "== before"
state

echo "== tools/connected-test.sh"
tools/connected-test.sh 2>&1 | grep -E 'Tests on|Finished|BUILD|FAILED' | tail -n 6
echo "== after connected-test.sh"; state
echo "== tools/persistence-test.sh"
tools/persistence-test.sh 2>&1 | tail -n 1
echo "== tools/extension-update-test.sh"
tools/extension-update-test.sh 2>&1 | tail -n 1
echo "== after the M1 device scripts"; state
echo "== tools/daily-install.sh (update in place)"
tools/daily-install.sh
echo "== after the update"; state

now_marker="$(adb shell run-as "${PKG}" cat files/twinbook-marker.txt | tr -d '\r')"
now_first="$(adb shell dumpsys package "${PKG}" | grep firstInstallTime | tr -d '\r' | sed 's/^ *//')"
[ "${now_marker}" = "${marker}" ] || { echo "FAIL: marker changed: '${now_marker}'" >&2; exit 1; }
[ "${now_first}" = "${first}" ] || { echo "FAIL: firstInstallTime changed (${first} -> ${now_first})" >&2; exit 1; }
echo "== daily-survival-test: PASS (marker ${marker}, ${first})"
