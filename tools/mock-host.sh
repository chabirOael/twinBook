#!/usr/bin/env bash
# Usage: tools/mock-host.sh start|stop|status|log [port]
#
# Runs the mock server (mockserver/, MockHost.kt) on the host at 127.0.0.1:<port> (default 8723)
# and makes it reachable from the device at the same address with `adb reverse`, so device
# scripts can kill the app or restart the device and find the mock again. The request log (one
# line per request, as it arrives) goes to build/mock-host/requests-<port>.log.
#   start   build (installDist), start in the background, adb reverse; waits until it answers
#   stop    stop it and remove the adb reverse rule
#   status  running or not
#   log     print the request log
# Only loopback: nothing else on the network can reach it.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

cmd="${1:?usage: $0 start|stop|status|log [port]}"
port="${2:-8723}"
dir="build/mock-host"
pidfile="${dir}/mock-${port}.pid"
log="${dir}/requests-${port}.log"
mkdir -p "${dir}"

running() { [ -f "${pidfile}" ] && kill -0 "$(cat "${pidfile}")" 2> /dev/null; }

case "${cmd}" in
  start)
    if running; then
      echo "mock host already running on 127.0.0.1:${port} (pid $(cat "${pidfile}"))"
    else
      ./gradlew -q :mockserver:installDist
      : > "${log}"
      nohup mockserver/build/install/mockserver/bin/mockserver "${port}" >> "${log}" 2>&1 &
      echo $! > "${pidfile}"
      for _ in $(seq 1 50); do
        curl -s -o /dev/null "http://127.0.0.1:${port}/blank.html" && break
        sleep 0.2
      done
      curl -s -o /dev/null "http://127.0.0.1:${port}/blank.html" || { echo "error: mock host did not start; see ${log}" >&2; exit 1; }
      echo "mock host running on 127.0.0.1:${port} (pid $(cat "${pidfile}")), log ${log}"
    fi
    adb reverse "tcp:${port}" "tcp:${port}" > /dev/null
    echo "adb reverse tcp:${port} -> host tcp:${port}"
    ;;
  stop)
    if running; then kill "$(cat "${pidfile}")"; fi
    rm -f "${pidfile}"
    adb reverse --remove "tcp:${port}" > /dev/null 2>&1 || true
    echo "mock host on port ${port} stopped"
    ;;
  status)
    if running; then echo "running (pid $(cat "${pidfile}"))"; else echo "not running"; exit 1; fi
    ;;
  log)
    cat "${log}"
    ;;
  *)
    echo "usage: $0 start|stop|status|log [port]" >&2
    exit 2
    ;;
esac
