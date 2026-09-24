#!/usr/bin/env bash
# Shared lifecycle for real local HTTP proofs. Call only from validated qa.sh.
prepare_packaged_http() {
    [[ "${WAREHOUSE_QA:-}" == true ]] || exit 64
    command -v python3 >/dev/null || refuse 'python3 is required for packaged HTTP verification'
    [[ ! -e "$RUNTIME/warehouse-backend.pid" && ! -e "$RUNTIME/warehouse-web.pid" ]] || refuse 'owned or stale E2E processes exist; run qa.sh stop first'
    python3 - <<'PY'
import socket
with socket.socket() as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('127.0.0.1', 17880))
    server.listen(1)
PY
    PROOF="$RUNTIME/$MODE-http"
    mkdir -p "$PROOF"
    PACKAGED_TEMP_FILES+=("$PROOF/state.json")
    trap cleanup_packaged_http EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    rm -f -- "${PACKAGED_TEMP_FILES[@]}"
    gradle :server:clean :server:bootJar "$@" --no-parallel
    read -r PACKAGED_JAR < "$ROOT/server/build/warehouse/boot-jar-path.txt"
    [[ -f "$PACKAGED_JAR" && "$PACKAGED_JAR" == "$ROOT/server/build/libs/"*.jar && "$PACKAGED_JAR" != *-plain.jar ]] || refuse 'invalid bootJar output metadata'
    sha256sum "$PACKAGED_JAR" > "$PROOF/boot-jar.sha256"
    export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=17880 FTTH_SEED_DEMO=false
    export FTTH_JWT_SECRET="$WH_APP_PASSWORD" FTTH_ENCRYPTION_SECRET="$WH_OWNER_PASSWORD"
    # Optional unconfigured SMTP is outside this isolated warehouse workflow.
    export MANAGEMENT_ENDPOINT_HEALTH_GROUP_WAREHOUSE_INCLUDE=db,diskSpace,ping
    export MANAGEMENT_ENDPOINT_HEALTH_GROUP_WAREHOUSE_SHOW_DETAILS=always
    export MANAGEMENT_ENDPOINT_HEALTH_GROUP_WAREHOUSE_SHOW_COMPONENTS=always
    export PYTHONDONTWRITEBYTECODE=1
}

run_packaged_phase() {
    local phase=$1 script=$2
    shift 2
    start_owned backend java -Xmx768m -jar "$PACKAGED_JAR"
    python3 "$ROOT/scripts/warehouse/$script" "$phase" "$@" | tee "$PROOF/$phase.txt"
    stop_owned
    cp "$RUNTIME/warehouse-backend.log" "$PROOF/$phase-server.log"
}

cleanup_packaged_http() {
    local exit_status=$?
    trap - EXIT INT TERM
    stop_owned || exit_status=64
    rm -f -- "${PACKAGED_TEMP_FILES[@]}"
    exit "$exit_status"
}
