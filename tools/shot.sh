#!/usr/bin/env bash
# Usage: tools/shot.sh <name>
#
# Saves a PNG screenshot of the device (ANDROID_SERIAL, default: the emulator from
# tools/emulator-start.sh) to build/shots/<name>.png and prints the path.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"

name="${1:-}"
if ! [[ "${name}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "usage: $0 <name>   (letters, digits, '.', '_' and '-' only)" >&2
  exit 2
fi
if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi

out="${TWINBOOK_ROOT}/build/shots/${name}.png"
mkdir -p "$(dirname "${out}")"
adb exec-out screencap -p > "${out}.part"
mv "${out}.part" "${out}"
echo "${out}"
