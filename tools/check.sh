#!/usr/bin/env bash
# Usage: tools/check.sh [extra Gradle arguments]
#
# Runs every check that needs no device, and fails on the first failure:
#   1. extension/: npm ci (only when node_modules is missing or older than the lockfile),
#      then `npm run check` (typecheck, Vitest, esbuild bundle, web-ext lint)
#   2. Gradle: `check` (unit tests and Android lint in :app, :engine, :data) and
#      `assembleDebug` (debug APK with the extension packaged in its assets)
# Works from a shell with no profile: env -i HOME=$HOME bash tools/check.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
cd "${TWINBOOK_ROOT}"

echo "== check.sh: extension"
(
  cd extension
  if [ ! -f node_modules/.package-lock.json ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
    npm ci --no-audit --no-fund
  fi
  npm run check
)

echo "== check.sh: gradle check assembleDebug"
./gradlew check assembleDebug "$@"

echo "== check.sh: all checks passed"
