#!/usr/bin/env bash
# Usage: tools/measure-memory.sh
#
# Total PSS summed over all processes of the :engine test app at four stages: runtime started
# with no session; one visible session on the test page; plus one headless session; after the
# headless session is closed. Runs MeasurementProbe in a fresh process.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
cd "${TWINBOOK_ROOT}"
tools/engine-instrument.sh MeasurementProbe -e measure 1 | grep -E 'MEASURE|^OK|FAIL'
