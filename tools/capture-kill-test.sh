#!/usr/bin/env bash
# Usage: tools/capture-kill-test.sh
#
# C7: a capture interrupted by a process kill cannot be pulled and is deleted at the next start.
# Uses the debug app (never the daily app):
# 1. CaptureProbe phase "open" records the mock secrets page and returns without stopping.
# 2. The app process is force-stopped: the session stays on disk without FINALIZED.
# 3. capture-pull.sh --debug must list it as NOT-finalized and refuse to pull it.
# 4. The app is started again; TwinBookApp deletes the session; it is gone from the list.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"
PKG=io.github.chabiroael.twinbook.debug

echo "== 1. record and leave the session open"
out="$(tools/app-instrument.sh CaptureProbe -e capturePhase open)"
echo "${out}" | grep -E 'CAPTURE_ID|^OK'
id="$(echo "${out}" | sed -n 's/^CAPTURE_ID \([^ ]*\) .*/\1/p' | head -n 1)"
[ -n "${id}" ] || { echo "error: no session id" >&2; exit 1; }

echo "== 2. kill the app process"
adb shell am force-stop "${PKG}"
sleep 2
if adb shell pidof "${PKG}" > /dev/null 2>&1; then echo "error: ${PKG} still running" >&2; exit 1; fi
echo "no process of ${PKG} is running"

echo "== 3. the session is on disk, not finalized, and cannot be pulled"
tools/capture-pull.sh --debug list | grep -E "^${id} " || { echo "error: session ${id} not listed" >&2; exit 1; }
tools/capture-pull.sh --debug list | grep -qE "^${id} NOT-finalized" || { echo "error: session ${id} should be NOT-finalized" >&2; exit 1; }
set +e
tools/capture-pull.sh --debug pull "${id}"
rc=$?
set -e
[ "${rc}" = 3 ] || { echo "error: pull should have been refused with exit 3, got ${rc}" >&2; exit 1; }
[ ! -e "captures/${id}" ] || { echo "error: captures/${id} exists" >&2; exit 1; }
echo "pull refused (exit ${rc}); nothing was copied"

echo "== 4. next app start deletes it"
adb shell am start -W -n "${PKG}/io.github.chabiroael.twinbook.MainActivity" > /dev/null
sleep 3
if tools/capture-pull.sh --debug list | grep -qE "^${id} "; then echo "error: session ${id} still on disk after start" >&2; exit 1; fi
adb logcat -d -s twinbook-app:W | grep 'deleted unfinalized' | tail -n 1 || true
echo "session ${id} is gone after the app start"
echo "== capture-kill-test: PASS"
