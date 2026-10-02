#!/usr/bin/env bash
# Usage: tools/measure-startup.sh [runs]
#
# Cold-starts the installed debug app [runs] times (default 3, after one warm-up start) and
# prints the time from process start to extension ready and to the lab page loaded, as the
# app logs them (tag twinbook-timing). Install the app first: tools/app-run.sh.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
APP=io.github.chabiroael.twinbook.debug
runs="${1:-3}"
for i in $(seq 0 "${runs}"); do
  adb shell am force-stop "${APP}"
  sleep 2
  adb logcat -c
  total="$(adb shell am start -W -n "${APP}/io.github.chabiroael.twinbook.MainActivity" | tr -d '\r' | grep TotalTime | awk '{print $2}')"
  for _ in $(seq 1 60); do
    lines="$(adb logcat -d -s twinbook-timing:I | grep -c 'after process start' || true)"
    [ "${lines}" -ge 2 ] && break
    sleep 0.5
  done
  label="run ${i}"; [ "${i}" = 0 ] && label="warm-up"
  echo "${label}: am start TotalTime ${total} ms; $(adb logcat -d -s twinbook-timing:I | grep 'after process start' | sed 's/^.*twinbook-timing: //' | tr '\n' ';')"
done
