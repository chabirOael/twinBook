#!/usr/bin/env bash
# Usage: tools/har-import.sh <file.har> [--id <session-id>] [--out <dir>]
#
# Converts a HAR file exported from desktop Firefox into a capture session (docs/CAPTURE.md),
# applying both redaction layers, and writes only the finalized, redacted session, by default
# to captures/<id>/ (git-ignored). The HAR file itself is only read. Keep it outside the
# repository, and delete it once imported: it holds live cookies and tokens.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
cd "${TWINBOOK_ROOT}/extension"
if [ ! -f node_modules/.package-lock.json ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
  npm ci --no-audit --no-fund > /dev/null
fi
node build-tools.mjs > /dev/null
har="$(cd "${OLDPWD}" && realpath "${1:?usage: $0 <file.har> [--id <session-id>] [--out <dir>]}")"
shift
out="${TWINBOOK_ROOT}/captures"
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$(cd "${OLDPWD}" && mkdir -p "$2" && realpath "$2")"; shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
mkdir -p "${out}"
node build/har-import.mjs "${har}" --out "${out}" "${args[@]}"
