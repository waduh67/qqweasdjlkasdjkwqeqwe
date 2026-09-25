#!/usr/bin/env bash
# Sourced by isolated warehouse harnesses. PID/start-time/marker checks bind cleanup
# to our process groups; unrelated services are never stopped.
stop_owned() {
    local record pid start marker current attempt group status=0
    for record in "$RUNTIME/warehouse-backend.pid" "$RUNTIME/warehouse-web.pid"; do
        [[ -e "$record" ]] || continue
        if [[ ! -f "$record" || -L "$record" || $(stat -c '%u' "$record") != "$(id -u)" ]]; then
            printf 'REFUSED: unsafe PID record\n' >&2; status=64; continue
        fi
        if ! read -r pid start marker < "$record"; then
            printf 'REFUSED: malformed PID record\n' >&2; status=64; continue
        fi
        if [[ ! "$pid" =~ ^[0-9]+$ || ! "$start" =~ ^[0-9]+$ || "$marker" != "$WH_MARKER" ]]; then
            printf 'REFUSED: stale PID marker\n' >&2; status=64; continue
        fi
        if [[ -e "/proc/$pid/stat" ]]; then
            current=$(cut -d ' ' -f22 "/proc/$pid/stat")
            group=$(cut -d ' ' -f5 "/proc/$pid/stat")
            if [[ "$current" != "$start" || "$group" != "$pid" || $(stat -c '%u' "/proc/$pid") != "$(id -u)" ]]; then
                printf 'REFUSED: PID reused by another process\n' >&2; status=64; continue
            fi
            kill -TERM -- "-$pid" 2>/dev/null || true
            for attempt in {1..50}; do [[ -e "/proc/$pid" ]] || break; sleep 0.1; done
            [[ ! -e "/proc/$pid" ]] || kill -KILL -- "-$pid" 2>/dev/null || true
        else
            if [[ "$OWNED_PIDS" != *" $pid "* ]]; then
                printf 'REFUSED: stale PID record; inspect this dead warehouse PID record\n' >&2; status=64; continue
            fi
        fi
        if [[ "$OWNED_PIDS" == *" $pid "* ]]; then wait "$pid" 2>/dev/null || true; fi
        rm -- "$record"
    done
    (( status != 0 )) || printf 'PASS: owned E2E processes stopped; unrelated ports/PIDs untouched\n'
    return "$status"
}


start_owned() {
    local name=$1 pid start pending_interrupt=0
    shift
    trap 'pending_interrupt=130' INT
    trap 'pending_interrupt=143' TERM
    (cd "$ROOT/web" && exec setsid "$@") >"$RUNTIME/warehouse-$name.log" 2>&1 9>&- &
    pid=$!
    OWNED_PIDS+="$pid "
    if ! start=$(cut -d ' ' -f22 "/proc/$pid/stat"); then
        kill -TERM "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        refuse 'owned process exited before registration'
    fi
    printf '%s %s %s\n' "$pid" "$start" "$WH_MARKER" > "$RUNTIME/warehouse-$name.pid"
    trap 'exit 130' INT
    trap 'exit 143' TERM
    (( pending_interrupt == 0 )) || exit "$pending_interrupt"
}

await_json() {
    local url=$1 attempt
    for ((attempt=0; attempt<120; attempt++)); do
        if curl --noproxy '*' -fsS --max-time 2 -H 'Accept: application/json' "$url" >"$RUNTIME/warehouse-readiness.json" 2>/dev/null &&
            jq -e --arg marker "$WH_MARKER" 'type == "object" and .status == "UP" and .components.warehouse.details.database == "warehouse_e2e" and .components.warehouse.details.user == "warehouse_app" and .components.warehouse.details.marker == $marker' "$RUNTIME/warehouse-readiness.json" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    refuse 'backend JSON readiness timed out (HTML or empty responses are not readiness)'
}
