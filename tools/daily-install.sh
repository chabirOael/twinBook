#!/usr/bin/env bash
# Usage: tools/daily-install.sh [--no-build]
#
# Builds the login-safe `daily` app (io.github.chabiroael.twinbook.daily) and installs it, or
# updates it in place, keeping all of its data: `adb install -r` replaces the APK and keeps
# cookies, extension storage and captures (the same mechanism M1 proved with
# tools/extension-update-test.sh). It never uninstalls and never clears data. If Android refuses
# the update (for example because the signing key changed), the script stops and says so; it
# does not fall back to uninstalling. See docs/SETUP.md, "Protecting the owner's session".
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

PKG=io.github.chabiroael.twinbook.daily
APK=app/build/outputs/apk/daily/app-daily.apk

if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi
if [ "${1:-}" != "--no-build" ]; then
  ./gradlew -q :app:assembleDaily
fi

installed_times() { adb shell dumpsys package "${PKG}" | grep -E 'firstInstallTime|lastUpdateTime' | tr -d '\r' | sed 's/^ */  /'; }

if adb shell pm list packages "${PKG}" | tr -d '\r' | grep -qx "package:${PKG}"; then
  echo "== updating ${PKG} in place (data kept)"
  installed_times
else
  echo "== installing ${PKG} for the first time"
fi
if ! out="$(adb install -r "${APK}" 2>&1)"; then
  echo "${out}" >&2
  echo "error: the install was refused. The daily app and its login are untouched." >&2
  echo "       Do NOT uninstall it to get around this; ask the owner (docs/SETUP.md)." >&2
  exit 1
fi
echo "${out}" | tail -n 1
installed_times
