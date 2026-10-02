#!/usr/bin/env bash
# Usage: tools/extension-update-test.sh
#
# G16: a changed extension in a reinstalled APK takes effect with app data kept.
# 1. Build the :engine test APK with extension marker A, install it, run phase "write"
#    (cookies and storage.local), record the extension version.
# 2. Build again with marker B (a changed extension bundle, so a new version stamp) and
#    reinstall with `adb install -r`, which keeps app data.
# 3. Phase "verify" checks that the running extension is build B (marker and version) and that
#    the cookies and storage.local written by build A are still there.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"
PKG=io.github.chabiroael.twinbook.engine.test
APK=engine/build/outputs/apk/androidTest/debug/engine-debug-androidTest.apk
stamp="$(date +%s)"
marker_a="update-a-${stamp}"
marker_b="update-b-${stamp}"
token="update${stamp}"

manifest_version() { unzip -p "${APK}" assets/extensions/twin-bridge/manifest.json | grep '"version"' | sed 's/.*: "\(.*\)".*/\1/'; }

echo "== build A (marker ${marker_a})"
./gradlew -q :engine:assembleDebugAndroidTest -Ptwinbook.extensionMarker="${marker_a}"
version_a="$(manifest_version)"
echo "packaged extension version A: ${version_a}"
adb install -r -t "${APK}" > /dev/null
adb shell dumpsys package "${PKG}" | grep -E 'firstInstallTime|lastUpdateTime' | sed 's/^ */A: /'
TWINBOOK_GRADLE_ARGS="-Ptwinbook.extensionMarker=${marker_a}" \
  tools/engine-instrument.sh --no-build PersistenceProbe -e persistPhase write -e token "${token}"

echo "== build B (marker ${marker_b})"
./gradlew -q :engine:assembleDebugAndroidTest -Ptwinbook.extensionMarker="${marker_b}"
version_b="$(manifest_version)"
echo "packaged extension version B: ${version_b}"
[ "${version_a}" != "${version_b}" ] || { echo "error: build B has the same version as A" >&2; exit 1; }
echo "== adb install -r (keeps app data)"
adb install -r -t "${APK}" > /dev/null
adb shell dumpsys package "${PKG}" | grep -E 'firstInstallTime|lastUpdateTime' | sed 's/^ */B: /'
tools/engine-instrument.sh --no-build PersistenceProbe -e persistPhase verify -e token "${token}" \
  -e expectMarker "${marker_b}" -e previousVersion "${version_a}"
echo "== rebuild with the default marker so later builds are not left with marker B"
./gradlew -q :engine:assembleDebugAndroidTest
echo "== extension-update-test: PASS"
