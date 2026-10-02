#!/usr/bin/env bash
# Usage: tools/capture-pull.sh [--debug] list
#        tools/capture-pull.sh [--debug] pull <session-id>
#        tools/capture-pull.sh [--debug] pull-all
#
# Capture sessions live in the app's private storage (files/captures/<id>/). This script lists
# them, and copies finalized ones into captures/<id>/ in the repository (git-ignored), then
# verifies every checksum and prints a summary. A session that is not finalized (no FINALIZED
# file) is refused: it may still contain secrets that layer 2 never scrubbed.
#
# By default it reads the login-safe `daily` app. --debug reads the debug app instead (tests).
# Works through `run-as`, which both builds allow because they are debuggable. Never deletes
# anything on the device.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"

PKG=io.github.chabiroael.twinbook.daily
if [ "${1:-}" = "--debug" ]; then PKG=io.github.chabiroael.twinbook.debug; shift; fi
cmd="${1:-list}"
OUT="${TWINBOOK_ROOT}/captures"

if ! adb get-state > /dev/null 2>&1; then
  echo "error: device ${ANDROID_SERIAL} not connected. Start it with tools/emulator-start.sh" >&2
  exit 1
fi

on_device() { adb shell run-as "${PKG}" sh -c "'$1'" | tr -d '\r'; }

list() {
  on_device 'cd files/captures 2>/dev/null || exit 0; for d in *; do [ -d "$d" ] || continue; if [ -f "$d/FINALIZED" ]; then s=finalized; else s=NOT-finalized; fi; echo "$d $s $(du -sk "$d" | cut -f1)KiB"; done'
}

pull_one() {
  local id="$1"
  if ! [[ "${id}" =~ ^[A-Za-z0-9_-]{1,64}$ ]]; then echo "error: bad session id '${id}'" >&2; return 2; fi
  local state
  state="$(on_device "if [ -d files/captures/${id} ]; then if [ -f files/captures/${id}/FINALIZED ]; then echo finalized; else echo open; fi; else echo missing; fi")"
  case "${state}" in
    missing) echo "error: no session ${id} in ${PKG}" >&2; return 2 ;;
    open) echo "REFUSED: session ${id} is not finalized; it cannot be pulled (it is deleted at the next app start)" >&2; return 3 ;;
  esac
  if [ -e "${OUT}/${id}" ]; then echo "error: ${OUT}/${id} exists already; not overwriting" >&2; return 2; fi
  local tmp="${OUT}/.partial-${id}"
  rm -rf "${tmp}"
  mkdir -p "${tmp}"
  adb exec-out run-as "${PKG}" tar -C files/captures -cf - "${id}" | tar -x -C "${tmp}"
  local dir="${tmp}/${id}"
  (
    cd "${dir}"
    [ -f FINALIZED ] && [ -f checksums.sha256 ] || { echo "error: FINALIZED or checksums.sha256 missing after copy" >&2; exit 4; }
    expected="$(sed -n 's/^checksums //p' FINALIZED)"
    actual="$(sha256sum checksums.sha256 | cut -d' ' -f1)"
    [ "${expected}" = "${actual}" ] || { echo "error: checksums.sha256 does not match FINALIZED" >&2; exit 4; }
    sha256sum -c --quiet --strict checksums.sha256 || { echo "error: checksum mismatch" >&2; exit 4; }
    listed="$(cut -d' ' -f3- checksums.sha256 | sort)"
    present="$(find . -type f ! -name checksums.sha256 ! -name FINALIZED | sed 's|^\./||' | sort)"
    [ "${listed}" = "${present}" ] || { echo "error: files on disk differ from checksums.sha256" >&2; exit 4; }
    echo "checksums: $(wc -l < checksums.sha256) files verified, FINALIZED matches"
  ) || { rm -rf "${tmp}"; return 4; }
  mv "${dir}" "${OUT}/${id}"
  rm -rf "${tmp}"
  echo "== pulled ${PKG} ${id} -> captures/${id}"
  node tools/capture-summary.mjs --short "${OUT}/${id}"
}

case "${cmd}" in
  list)
    echo "== capture sessions in ${PKG}"
    list
    ;;
  pull)
    pull_one "${2:?usage: $0 [--debug] pull <session-id>}"
    ;;
  pull-all)
    status=0
    while read -r id state _; do
      [ -n "${id}" ] || continue
      if [ "${state}" = finalized ] && [ ! -e "${OUT}/${id}" ]; then pull_one "${id}" || status=$?; fi
      if [ "${state}" != finalized ]; then echo "REFUSED: session ${id} is not finalized" >&2; fi
    done < <(list)
    exit "${status}"
    ;;
  *)
    echo "usage: $0 [--debug] list | pull <session-id> | pull-all" >&2
    exit 2
    ;;
esac
