#!/usr/bin/env bash
# Usage: tools/emulator-start.sh [--window]
#
# Starts the twinbook_api36 AVD and returns once Android has finished booting.
# Headless by default; --window opens a visible window through WSLg (see docs/SETUP.md).
# No-op if the emulator is already running. Fails fast if /dev/kvm is not usable: software
# emulation is far too slow and is never used.
# Cold boots every time (no snapshots). Animations are switched off for UI tests.
# Emulator output goes to build/emulator/emulator.log.
# TWINBOOK_EMULATOR_GPU overrides the GPU mode (default swiftshader_indirect; see docs/SETUP.md
# on host memory).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

BOOT_TIMEOUT_S=300
WINDOW_ARGS=(-no-window)
if [ "${1:-}" = "--window" ]; then
  WINDOW_ARGS=()
elif [ -n "${1:-}" ]; then
  echo "usage: $0 [--window]" >&2
  exit 2
fi

if ! { [ -r /dev/kvm ] && [ -w /dev/kvm ]; }; then
  echo "error: /dev/kvm is not readable and writable by $(id -un); the emulator needs KVM." >&2
  echo "       Owner fix: sudo usermod -aG kvm $(id -un), then restart WSL (wsl --shutdown)," >&2
  echo "       or for the current boot only: sudo chmod 666 /dev/kvm" >&2
  exit 1
fi

# The emulator's netsim daemon creates files under XDG_RUNTIME_DIR. If that variable names a
# directory that does not exist or is not writable (seen in IDE and agent shells on WSL:
# /run/user/1000/ missing), netsimd fails with "Permission denied" and qemu deadlocks during
# boot ("detected a hanging thread"). Fall back to the default location instead.
if [ -n "${XDG_RUNTIME_DIR:-}" ] && ! { [ -d "${XDG_RUNTIME_DIR}" ] && [ -w "${XDG_RUNTIME_DIR}" ]; }; then
  echo "note: XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR} is not a writable directory; unsetting it for the emulator"
  unset XDG_RUNTIME_DIR
fi

serial="${TWINBOOK_EMULATOR_SERIAL}"
adb start-server > /dev/null
# Only a device that answers counts: right after tools/emulator-stop.sh the old one can still be
# listed for a moment as "offline".
if adb devices | grep -q "^${serial}[[:space:]]*device$"; then
  echo "emulator ${serial} already running"
  exit 0
fi

log_dir="${TWINBOOK_ROOT}/build/emulator"
mkdir -p "${log_dir}"
start=$(date +%s)
nohup emulator -avd "${TWINBOOK_AVD}" -port "${TWINBOOK_EMULATOR_PORT}" "${WINDOW_ARGS[@]}" \
  -no-snapshot -no-audio -no-boot-anim -accel on -gpu "${TWINBOOK_EMULATOR_GPU:-swiftshader_indirect}" \
  > "${log_dir}/emulator.log" 2>&1 &
pid=$!
echo "${pid}" > "${log_dir}/emulator.pid"
echo "emulator starting (pid ${pid}, log ${log_dir}/emulator.log)"

while true; do
  if ! kill -0 "${pid}" 2> /dev/null; then
    echo "error: emulator exited during boot. Last log lines:" >&2
    tail -n 20 "${log_dir}/emulator.log" >&2
    exit 1
  fi
  if [ $(( $(date +%s) - start )) -gt "${BOOT_TIMEOUT_S}" ]; then
    echo "error: emulator did not finish booting within ${BOOT_TIMEOUT_S}s" >&2
    exit 1
  fi
  booted="$(adb -s "${serial}" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r' || true)"
  [ "${booted}" = "1" ] && break
  sleep 2
done

adb -s "${serial}" shell settings put global window_animation_scale 0
adb -s "${serial}" shell settings put global transition_animation_scale 0
adb -s "${serial}" shell settings put global animator_duration_scale 0
adb -s "${serial}" shell svc power stayon true
adb -s "${serial}" shell wm dismiss-keyguard > /dev/null 2>&1 || true

echo "emulator ${serial} booted in $(( $(date +%s) - start ))s"
