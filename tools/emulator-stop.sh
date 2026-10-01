#!/usr/bin/env bash
# Usage: tools/emulator-stop.sh
#
# Stops the emulator started by tools/emulator-start.sh: asks it to shut down through its
# console, waits up to 30s for the process to exit, then kills it. No-op if not running.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

serial="${TWINBOOK_EMULATOR_SERIAL}"
pid_file="${TWINBOOK_ROOT}/build/emulator/emulator.pid"
pid="$(cat "${pid_file}" 2> /dev/null || true)"

running=0
if adb devices 2> /dev/null | grep -q "^${serial}[[:space:]]"; then running=1; fi
if [ -n "${pid}" ] && kill -0 "${pid}" 2> /dev/null; then running=1; fi
if [ "${running}" -eq 0 ]; then
  echo "emulator ${serial} not running"
  rm -f "${pid_file}"
  exit 0
fi

adb -s "${serial}" emu kill > /dev/null 2>&1 || true
if [ -n "${pid}" ]; then
  for _ in $(seq 1 30); do
    kill -0 "${pid}" 2> /dev/null || break
    sleep 1
  done
  if kill -0 "${pid}" 2> /dev/null; then
    echo "emulator did not exit after 30s, killing pid ${pid}"
    kill "${pid}" 2> /dev/null || true
  fi
fi
rm -f "${pid_file}"
echo "emulator ${serial} stopped"
