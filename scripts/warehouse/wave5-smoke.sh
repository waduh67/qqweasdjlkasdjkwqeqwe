#!/usr/bin/env bash
[[ "${MODE:-}" == wave5 && "${WAREHOUSE_QA:-}" == true ]] || exit 64
source "$ROOT/scripts/warehouse/packaged-http.sh"
PACKAGED_TEMP_FILES=()
prepare_packaged_http "$@"
for phase in before-restart after-restart; do
    run_packaged_phase "$phase" wave5-http.py "$PROOF/state.json"
done
printf 'PASS: real signup, transfer, count, independent approval and restart replay\n'
