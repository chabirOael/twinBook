#!/usr/bin/env bash
# Usage: tools/shell-persistence-test.sh [--no-reboot]
#
# S5: the web shell's last page and its history survive process death and a device restart.
# Debug app only (never the daily app), shell pointed at the mock on the host
# (tools/mock-host.sh, reached through adb reverse), so the mock's request log shows every page
# the app loads:
#  1. fresh state; the shell starts at /shell/home.html; the first link (feed) is activated from
#     the keyboard (Tab, Enter), a user gesture like a tap: history = [home, feed], current = feed.
#     (Gecko's back skips entries a page added without a user gesture, so a scripted navigation
#     would not do.)
#  2. am force-stop, relaunch: feed.html is requested again and home.html is not (restored)
#  3. adb reboot (a clean restart of Android), relaunch: feed.html again, home.html not
#  4. the back key: home.html is requested (the history came back too)
# Cookies surviving both was shown in M1 (tools/persistence-test.sh).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

PKG=io.github.chabiroael.twinbook.debug
ACTIVITY="${PKG}/io.github.chabiroael.twinbook.MainActivity"
PORT=8723
ORIGIN="http://127.0.0.1:${PORT}"
LOG="build/mock-host/requests-${PORT}.log"
reboot=1
[ "${1:-}" = "--no-reboot" ] && reboot=0

adb get-state > /dev/null 2>&1 || { echo "error: device ${ANDROID_SERIAL} not connected" >&2; exit 1; }
packages="$(adb shell pm list packages "${PKG}" | tr -d '\r')"
grep -qx "package:${PKG}" <<< "${packages}" || { echo "error: debug app not installed (tools/app-run.sh)" >&2; exit 1; }
tools/mock-host.sh start > /dev/null

launch() { adb shell am start -W -n "${ACTIVITY}" --es twinbook.mockOrigin "${ORIGIN}" --ez twinbook.openShell true "$@" | tr -d '\r' | grep -E 'TotalTime' || true; }
lines() { wc -l < "${LOG}"; }
# Waits until a request for path $2 shows up after line $1 of the log.
await_request() {
  local from="$1" path="$2" timeout="${3:-60}"
  for _ in $(seq 1 $((timeout * 2))); do
    tail -n +"$((from + 1))" "${LOG}" | grep -q " GET ${path}" && return 0
    sleep 0.5
  done
  echo "FAIL: no request for ${path} within ${timeout}s; log since line ${from}:" >&2
  tail -n +"$((from + 1))" "${LOG}" >&2
  exit 1
}
count_since() { tail -n +"$(($1 + 1))" "${LOG}" | grep -c " GET $2" || true; }
saved_history() { adb shell run-as "${PKG}" cat files/shell/state.json 2> /dev/null | python3 -c 'import json,sys; o=json.load(sys.stdin); h=json.loads(o["state"])["history"]; print("index", h.get("index"), [e["url"].split("/shell/")[-1] for e in h["entries"]])' 2> /dev/null || echo "no saved state"; }
check_restored() {
  local what="$1" from="$2"
  await_request "${from}" "/shell/feed.html"
  sleep 3
  local home feed
  home="$(count_since "${from}" "/shell/home.html")"
  feed="$(count_since "${from}" "/shell/feed.html")"
  [ "${home}" = 0 ] || { echo "FAIL: ${what}: home.html was loaded (${home}); the last page was not restored" >&2; exit 1; }
  echo "${what}: feed.html requested ${feed} time(s), home.html 0: last page restored; saved $(saved_history)"
}

echo "== 1. fresh state, build a history"
adb shell am force-stop "${PKG}"
adb shell run-as "${PKG}" rm -f files/shell/state.json
from="$(lines)"
launch
await_request "${from}" "/shell/home.html"
sleep 6 # until the splash is gone
# Focus the page with a tap on its heading (no link there), then move to the first link.
adb shell input tap 540 270
sleep 0.5
adb shell input keyevent KEYCODE_TAB
sleep 0.5
adb shell input keyevent KEYCODE_ENTER
await_request "${from}" "/shell/feed.html"
sleep 3
echo "history built; saved $(saved_history)"

echo "== 2. process death"
adb shell am force-stop "${PKG}"
sleep 2
if adb shell pidof "${PKG}" > /dev/null 2>&1; then echo "FAIL: ${PKG} still running" >&2; exit 1; fi
echo "no process of ${PKG}"
from="$(lines)"
launch
check_restored "after process death" "${from}"

if [ "${reboot}" = 1 ]; then
  echo "== 3. device restart"
  adb shell am force-stop "${PKG}"
  adb reboot
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
  adb shell svc power stayon true
  adb reverse "tcp:${PORT}" "tcp:${PORT}" > /dev/null
  until adb shell run-as "${PKG}" true 2> /dev/null; do sleep 2; done
  echo "device restarted"
  from="$(lines)"
  launch
  check_restored "after device restart" "${from}"
else
  echo "== 3. device restart: skipped (--no-reboot)"
fi

echo "== 4. back goes to the previous page of the restored history"
from="$(lines)"
sleep 2
adb shell input keyevent KEYCODE_BACK
await_request "${from}" "/shell/home.html" 30
sleep 2
activities="$(adb shell dumpsys activity activities | tr -d '\r')"
grep -q "ResumedActivity.*${PKG}" <<< "${activities}" || { echo "FAIL: the app left the foreground on back" >&2; exit 1; }
echo "back: home.html requested, app still in front; saved $(saved_history)"
adb shell am force-stop "${PKG}"
echo "== shell-persistence-test: PASS"
