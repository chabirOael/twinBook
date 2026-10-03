#!/usr/bin/env bash
# Usage: tools/typing-diagnostic.sh burst <runs>                     the suite's burst diagnostic, <runs> fresh processes
#        tools/typing-diagnostic.sh probe <via> <delayMs> <reps>     TypingDiagnosticProbe in one process
#
# S10: how often a burst of key events loses or swaps keys, and under which conditions. Debug app
# only. "burst" runs CaptureBrowserScreenTest#keyEventBurstDiagnostic <runs> times (each a new
# process) and counts its results. "probe" runs TypingDiagnosticProbe (see its comment) once with
# the given variant and prints its per-repetition lines and summary.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
export ANDROID_SERIAL="${ANDROID_SERIAL:-${TWINBOOK_EMULATOR_SERIAL}}"
cd "${TWINBOOK_ROOT}"
mode="${1:?usage: $0 burst <runs> | probe <via> <delayMs> <reps>}"
case "${mode}" in
  burst)
    runs="${2:-20}"
    build=""
    for i in $(seq 1 "${runs}"); do
      line="$(tools/app-instrument.sh ${build} 'CaptureBrowserScreenTest#keyEventBurstDiagnostic' 2>&1 | grep 'BURST DIAGNOSTIC' | sed 's/^.*BURST DIAGNOSTIC //' || true)"
      build="--no-build"
      echo "run ${i}: ${line:-no result (test failed before typing)}"
    done | tee build/typing-burst.log
    echo "== results: $(grep -oE 'result=[a-z]+' build/typing-burst.log | sort | uniq -c | tr '\n' ' ')"
    ;;
  probe)
    via="${2:-input}"; delay="${3:-0}"; reps="${4:-10}"
    tools/app-instrument.sh --no-build TypingDiagnosticProbe -e via "${via}" -e delayMs "${delay}" -e reps "${reps}" 2>&1 | grep -E 'TYPING|^OK|FAIL' || true
    ;;
  *) echo "unknown mode ${mode}" >&2; exit 2 ;;
esac
