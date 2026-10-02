#!/usr/bin/env bash
# Usage: tools/measure-shell-startup.sh <ENSURE_BUILT_IN|INSTALL_EVERY_START> [runs] [restart-every]
#
# S8: cold starts of the web shell (debug app, never the daily app) with the given start-up mode
# of the built-in extensions (docs/SHELL.md section 5). Each run: am force-stop, saved state
# removed, launch with the shell pointed at the mock on the host (tools/mock-host.sh) and the
# start page /shell/ads.html, whose image /__utm.gif is on a default uBlock Origin list. Prints
# per run, from process start: twin-bridge ready, uBlock Origin ready (it cancelled a probe),
# both ready, first page shown; the number of probes and how many went out unfiltered; and
# whether /__utm.gif reached the mock (it must never). Then medians and extremes.
# The emulator's qemu process grows by about 250 MB per cold start of the app and is not
# shrunk by a guest reboot (docs/SETUP.md), so the emulator itself is restarted (cleanly) after
# every [restart-every] runs (default 5); the restart is not part of any measured time. Right
# after a boot Android is still busy, so one unrecorded warm-up start follows every boot and
# precedes the first run.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

mode="${1:?usage: $0 <ENSURE_BUILT_IN|INSTALL_EVERY_START> [runs]}"
runs="${2:-20}"
restart_every="${3:-5}"
case "${mode}" in ENSURE_BUILT_IN | INSTALL_EVERY_START) ;; *) echo "unknown mode ${mode}" >&2; exit 2 ;; esac
PKG=io.github.chabiroael.twinbook.debug
PORT=8723
LOG="build/mock-host/requests-${PORT}.log"
out="build/startup-${mode}.tsv"
tools/mock-host.sh start > /dev/null
echo -e "run\tbridge_ms\tblocker_ms\tready_ms\tfirst_page_ms\tprobes\tpassed\tutm_requests" > "${out}"

warm_up() {
  adb shell am force-stop "${PKG}"
  adb shell am start -n "${PKG}/io.github.chabiroael.twinbook.MainActivity" --es twinbook.mockOrigin "http://127.0.0.1:${PORT}" \
    --ez twinbook.openShell true --es twinbook.mockStart "/shell/home.html?warmup=1" --es twinbook.startupMode "${mode}" > /dev/null
  sleep 25
}

leaks=0
warm_up
for i in $(seq 1 "${runs}"); do
  if [ "${i}" -gt 1 ] && [ $(( (i - 1) % restart_every )) = 0 ]; then
    tools/emulator-stop.sh > /dev/null
    tools/emulator-start.sh > /dev/null
    # The device can show as offline for a moment right after boot.
    adb wait-for-device
    until adb reverse "tcp:${PORT}" "tcp:${PORT}" > /dev/null 2>&1; do sleep 2; done
    until adb shell run-as "${PKG}" true 2> /dev/null; do sleep 2; done
    warm_up
  fi
  adb shell am force-stop "${PKG}"
  adb shell run-as "${PKG}" rm -f files/shell/state.json
  sleep 2
  adb logcat -c
  from="$(wc -l < "${LOG}")"
  adb shell am start -n "${PKG}/io.github.chabiroael.twinbook.MainActivity" --es twinbook.mockOrigin "http://127.0.0.1:${PORT}" \
    --ez twinbook.openShell true --es twinbook.mockStart "/shell/ads.html?cold=${mode}-${i}" --es twinbook.startupMode "${mode}" > /dev/null
  line=""
  for _ in $(seq 1 180); do
    line="$(adb logcat -d -s twinbook-timing:I | grep 'shell first page shown' || true)"
    [ -n "${line}" ] && break
    sleep 0.5
  done
  ready="$(adb logcat -d -s twinbook-timing:I | grep 'shell engine ready' | head -n 1)"
  [ -n "${line}" ] && [ -n "${ready}" ] || { echo "run ${i}: no timing lines (failed start?)" >&2; adb logcat -d -s twinbook-engine:* twinbook-app:* | tail -n 5 >&2; exit 1; }
  sleep 1
  bridge="$(sed -n 's/.*twin-bridge \([0-9]*\) ms.*/\1/p' <<< "${ready}")"
  blocker="$(sed -n 's/.*blocker \([0-9]*\) ms.*/\1/p' <<< "${ready}")"
  probes="$(sed -n 's/.*, \([0-9]*\) probes.*/\1/p' <<< "${ready}")"
  passed="$(sed -n 's/.*probes, \([0-9]*\) passed.*/\1/p' <<< "${ready}")"
  readyms="$(sed -n 's/.*) \([0-9]*\) ms after process start.*/\1/p' <<< "${ready}")"
  first="$(sed -n 's/.*shown \([0-9]*\) ms after process start.*/\1/p' <<< "${line}")"
  utm="$(tail -n +"$((from + 1))" "${LOG}" | grep -c 'GET /__utm.gif' || true)"
  page="$(tail -n +"$((from + 1))" "${LOG}" | grep -c "GET /shell/ads.html?cold=${mode}-${i}" || true)"
  [ "${page}" -ge 1 ] || { echo "run ${i}: the start page was not requested" >&2; exit 1; }
  [ "${utm}" = 0 ] || leaks=$((leaks + 1))
  echo -e "${i}\t${bridge}\t${blocker}\t${readyms}\t${first}\t${probes}\t${passed}\t${utm}" | tee -a "${out}"
done
adb shell am force-stop "${PKG}"

python3 - "${out}" "${mode}" << 'PY'
import csv, statistics, sys
rows = list(csv.DictReader(open(sys.argv[1]), delimiter="\t"))
def col(k): return [int(r[k]) for r in rows]
print(f"== {sys.argv[2]}: {len(rows)} cold starts")
for k in ["bridge_ms", "blocker_ms", "ready_ms", "first_page_ms"]:
    v = col(k)
    print(f"{k}: median {statistics.median(v):.0f}, min {min(v)}, max {max(v)}")
print(f"probes: {sum(col('probes'))} in total, passed unfiltered {sum(col('passed'))}; /__utm.gif requests {sum(col('utm_requests'))}")
PY
[ "${leaks}" = 0 ] || { echo "FAIL: /__utm.gif reached the mock in ${leaks} run(s)" >&2; exit 1; }
echo "== measure-shell-startup ${mode}: PASS (no listed request reached the mock)"
