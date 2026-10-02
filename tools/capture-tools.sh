#!/usr/bin/env bash
# Usage: tools/capture-tools.sh <command> [arguments]
#
# Offline tools for pulled capture sessions (docs/CAPTURE.md). They read sessions under
# captures/ (git-ignored) and print names, counts, lengths and structure only, never a
# recorded value. Run without arguments for the list of commands.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
root="${TWINBOOK_ROOT}"
(
  cd "${root}/extension"
  if [ ! -f node_modules/.package-lock.json ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
    npm ci --no-audit --no-fund > /dev/null
  fi
  node build-tools.mjs > /dev/null
)
exec node "${root}/extension/build/capture-tools.mjs" "$@"
