#!/usr/bin/env bash
# Usage: tools/daily-survival-test.sh [--skip-connected]
#
# C13, reworked in M3a: the owner's data in the daily build survives a full run of
# tools/connected-test.sh, the M1 device scripts (persistence-test.sh, extension-update-test.sh)
# and an in-place update of the daily build, and the daily app is never launched.
#
# Since M3a launching the daily app loads the real site with the owner's account, so this script
# never starts it: the marker is written and read with run-as only, and no step opens an
# activity of it. It checks, before and after:
#  - firstInstallTime (changes only if the app was ever uninstalled and installed again),
#  - lastUpdateTime (must change with the in-place update, and only then),
#  - a listing of the app's private storage: every file with size and modification time, which
#    must be identical (the app never ran, so nothing in it may change),
#  - the marker file's content,
#  - that no process of the daily app is running at any point.
# Needs the daily app installed (tools/daily-install.sh); it is never installed fresh here.
# --skip-connected leaves out the instrumented suite (when it was just run on its own).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"
PKG=io.github.chabiroael.twinbook.daily
skip_connected=0
[ "${1:-}" = "--skip-connected" ] && skip_connected=1

adb get-state > /dev/null 2>&1 || { echo "error: device ${ANDROID_SERIAL} not connected" >&2; exit 1; }
packages="$(adb shell pm list packages "${PKG}" | tr -d '\r')"
grep -qx "package:${PKG}" <<< "${packages}" || { echo "error: ${PKG} is not installed; this script never installs it fresh" >&2; exit 1; }

dir="build/daily-survival"
mkdir -p "${dir}"
times() { adb shell dumpsys package "${PKG}" | tr -d '\r' | grep -E 'firstInstallTime|lastUpdateTime' | sed 's/^ *//' | sort; }
listing() { adb shell run-as "${PKG}" sh -c "'find . -type f -exec stat -c \"%n %s %Y\" {} \\;'" | tr -d '\r' | sort; }
not_running() {
  if adb shell pidof "${PKG}" > /dev/null 2>&1; then echo "FAIL: a process of ${PKG} is running ($1)" >&2; exit 1; fi
  echo "  no process of ${PKG} ($1)"
}

not_running "start"
marker="survives-$(date +%s)"
adb shell run-as "${PKG}" sh -c "'mkdir -p files && echo ${marker} > files/twinbook-marker.txt'"
times > "${dir}/times-before.txt"
listing > "${dir}/listing-before.txt"
echo "== before"
cat "${dir}/times-before.txt"
echo "  storage: $(wc -l < "${dir}/listing-before.txt") files, $(awk '{s+=$2} END {print s}' "${dir}/listing-before.txt") bytes"

if [ "${skip_connected}" = 0 ]; then
  echo "== tools/connected-test.sh"
  tools/connected-test.sh 2>&1 | grep -E 'Tests on|Finished|BUILD|FAILED' | tail -n 6 || true
  not_running "after connected-test.sh"
fi
echo "== tools/persistence-test.sh"
tools/persistence-test.sh 2>&1 | tail -n 1
echo "== tools/extension-update-test.sh"
tools/extension-update-test.sh 2>&1 | tail -n 1
not_running "after the M1 device scripts"
times > "${dir}/times-middle.txt"
listing > "${dir}/listing-middle.txt"
diff "${dir}/times-before.txt" "${dir}/times-middle.txt" > /dev/null || { echo "FAIL: install times changed before the update" >&2; diff "${dir}/times-before.txt" "${dir}/times-middle.txt" >&2; exit 1; }
diff "${dir}/listing-before.txt" "${dir}/listing-middle.txt" > /dev/null || { echo "FAIL: storage changed" >&2; diff "${dir}/listing-before.txt" "${dir}/listing-middle.txt" | head -n 20 >&2; exit 1; }
echo "  install times and storage listing unchanged"

echo "== tools/daily-install.sh (update in place, not launched)"
tools/daily-install.sh
not_running "after the update"
times > "${dir}/times-after.txt"
listing > "${dir}/listing-after.txt"
echo "== after the update"
cat "${dir}/times-after.txt"

now_marker="$(adb shell run-as "${PKG}" cat files/twinbook-marker.txt | tr -d '\r')"
[ "${now_marker}" = "${marker}" ] || { echo "FAIL: marker changed: '${now_marker}'" >&2; exit 1; }
first_before="$(grep firstInstallTime "${dir}/times-before.txt")"
first_after="$(grep firstInstallTime "${dir}/times-after.txt")"
[ "${first_before}" = "${first_after}" ] || { echo "FAIL: ${first_before} -> ${first_after}" >&2; exit 1; }
update_before="$(grep lastUpdateTime "${dir}/times-before.txt")"
update_after="$(grep lastUpdateTime "${dir}/times-after.txt")"
[ "${update_before}" != "${update_after}" ] || { echo "FAIL: lastUpdateTime did not change; was the update installed?" >&2; exit 1; }
diff "${dir}/listing-before.txt" "${dir}/listing-after.txt" > /dev/null || { echo "FAIL: storage changed by the update" >&2; diff "${dir}/listing-before.txt" "${dir}/listing-after.txt" | head -n 20 >&2; exit 1; }
# Make sure the new APK reaches the emulator's disk before anything else happens to it.
adb shell sync
echo "  marker ${marker} intact; ${first_after}; ${update_before} -> ${update_after}; storage listing identical ($(wc -l < "${dir}/listing-after.txt") files)"
echo "== daily-survival-test: PASS"
