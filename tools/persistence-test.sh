#!/usr/bin/env bash
# Usage: tools/persistence-test.sh
#
# G15: cookies and the extension's storage.local survive a full process kill and relaunch.
# Phase "write" (one process) sets persistent and session cookies through a session and
# writes storage.local over the bridge. Then the app process is force-stopped. Phase
# "verify" runs in a new process (different pid) and checks what came back. App data is never
# cleared. Needs a running device (tools/emulator-start.sh).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"
PKG=io.github.chabiroael.twinbook.engine.test

token="persist$(date +%s)"
echo "== persistence-test: token ${token}"
echo "== phase write"
tools/engine-instrument.sh PersistenceProbe -e persistPhase write -e token "${token}"
echo "== kill: am force-stop ${PKG}"
adb shell am force-stop "${PKG}"
sleep 2
if adb shell pidof "${PKG}" > /dev/null 2>&1; then
  echo "error: ${PKG} still running" >&2
  exit 1
fi
echo "no process of ${PKG} is running"
echo "== phase verify (new process)"
tools/engine-instrument.sh --no-build PersistenceProbe -e persistPhase verify -e token "${token}"
echo "== persistence-test: PASS"
