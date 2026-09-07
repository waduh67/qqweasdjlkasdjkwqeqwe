#!/usr/bin/env bash
set +x
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/test-environment.sh"
umask 077

[[ $# -ge 1 ]] || refuse 'usage: qa.sh server|web-test|web-check|kmp|browser|stop'
MODE=$1
shift
case "$MODE" in server|web-test|web-check|kmp|browser|stop) ;; *) refuse 'unknown QA mode' ;; esac
reject_overrides
load_environment
OWNED_PIDS=' '

export_database() {
    export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$WH_PG_PORT/$1"
    export SPRING_DATASOURCE_USERNAME="$WH_APP_USER" SPRING_DATASOURCE_PASSWORD="$WH_APP_PASSWORD"
    export SPRING_FLYWAY_URL="$SPRING_DATASOURCE_URL" SPRING_FLYWAY_USER="$WH_OWNER_USER" SPRING_FLYWAY_PASSWORD="$WH_OWNER_PASSWORD"
    export FTTH_S3_ENDPOINT="http://127.0.0.1:$WH_S3_PORT" FTTH_S3_ACCESS_KEY=warehouse_storage FTTH_S3_SECRET_KEY="$WH_MINIO_PASSWORD"
    export FTTH_S3_BUCKET="${1//_/-}" FTTH_SCHEDULING_ENABLED=false FTTH_MONITORING_SERVER_POLL_ENABLED=false
    export FTTH_RADIUS_ENABLED=false FTTH_PROVISIONING_AUTO_APPLY_ENABLED=false FTTH_BILLING_PLATFORM_ENABLED=false
    export WAREHOUSE_QA=true WAREHOUSE_ENVIRONMENT_MARKER="$WH_MARKER"
}

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

if [[ "$MODE" == stop ]]; then [[ $# == 0 ]] || refuse 'stop takes no arguments'; stop_owned; exit; fi
exec 9>"$RUNTIME/warehouse.lock"
flock -w 30 9 || refuse 'warehouse QA is busy'
docker_local
check_environment
export_database warehouse_test

gradle() { (cd "$ROOT" && timeout --kill-after=30s 1800s ./gradlew "$@"); }
web() { (cd "$ROOT/web" && timeout --kill-after=15s 900s "$@"); }

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
    for attempt in {1..120}; do
        if curl --noproxy '*' -fsS --max-time 2 -H 'Accept: application/json' "$url" >"$RUNTIME/warehouse-readiness.json" 2>/dev/null &&
            jq -e --arg marker "$WH_MARKER" 'type == "object" and .status == "UP" and .components.warehouse.details.database == "warehouse_e2e" and .components.warehouse.details.user == "warehouse_app" and .components.warehouse.details.marker == $marker' "$RUNTIME/warehouse-readiness.json" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    refuse 'backend JSON readiness timed out (HTML or empty responses are not readiness)'
}

case "$MODE" in
    server)
        args=("$@")
        while (( $# )); do
            case "$1" in
                --tests) [[ $# -ge 2 && "$2" != -* ]] || refuse '--tests needs a pattern'; shift 2 ;;
                --rerun-tasks|--no-parallel) shift ;;
                *) refuse 'server accepts only --tests PATTERN, --rerun-tasks, --no-parallel (no overrides or task exclusions)' ;;
            esac
        done
        [[ " ${args[*]} " == *' --no-parallel '* ]] || args+=(--no-parallel)
        gradle :server:test "${args[@]}"
        ;;
    web-test)
        [[ $# == 1 && "$1" != -* ]] || refuse 'web-test requires one test filter'
        web npm test -- --run "$1"
        ;;
    web-check)
        [[ $# == 0 ]] || refuse 'web-check takes no arguments'
        web npm ci
        web npm run lint
        web npm test -- --run
        web npm run build
        ;;
    kmp)
        [[ $# == 0 ]] || refuse 'kmp takes no arguments'
        [[ -f "$ROOT/mobile/feature/materials/build.gradle.kts" ]] || refuse 'materials KMP module missing; implement plan task42 before running kmp'
        gradle :mobile:core:mvi:jvmTest :mobile:core:storage:jvmTest :mobile:feature:workorders:jvmTest :mobile:feature:materials:jvmTest :mobile:app:jvmTest verifyMobileModuleGraph --no-parallel
        ;;
    browser)
        [[ $# == 1 && "$1" =~ ^[a-zA-Z0-9_-]+\.spec\.ts$ ]] || refuse 'browser requires one warehouse spec filename'
        [[ -f "$ROOT/web/playwright.warehouse.config.ts" && -f "$ROOT/web/e2e/warehouse/$1" ]] || refuse 'real warehouse browser config/spec missing; implement plan task31 before running browser'
        [[ -f "$ROOT/server/src/main/resources/application-warehouse-e2e.yml" ]] || refuse 'warehouse-e2e external-adapter isolation profile missing; provide task31 profile before launching a backend'
        [[ ! -e "$RUNTIME/warehouse-backend.pid" && ! -e "$RUNTIME/warehouse-web.pid" ]] || refuse 'owned or stale E2E processes exist; run qa.sh stop first'
        node -e 'const net=require("node:net"); for(const port of [17880,14188]) { const server=net.createServer(); server.once("error",()=>{ console.error("REFUSED: E2E port occupied"); process.exit(64); }); server.listen(port,"127.0.0.1",()=>server.close()); }'
        export_database warehouse_e2e
        export WAREHOUSE_E2E_BACKEND_URL=http://127.0.0.1:17880 WAREHOUSE_E2E_WEB_URL=http://127.0.0.1:14188
        export SPRING_PROFILES_ACTIVE=warehouse-e2e SERVER_ADDRESS=127.0.0.1 SERVER_PORT=17880
        export MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=always
        export FTTH_CORS_ORIGINS=http://127.0.0.1:14188 FTTH_SEED_DEMO=false
        gradle :server:bootJar --no-parallel
        read -r jar < "$ROOT/server/build/warehouse/boot-jar-path.txt"
        [[ -f "$jar" && "$jar" == "$ROOT/server/build/libs/"*.jar && "$jar" != *-plain.jar ]] || refuse 'invalid bootJar output metadata'
        web npm run build
        trap 'status=$?; trap - EXIT INT TERM; stop_owned || status=64; exit "$status"' EXIT
        trap 'exit 130' INT
        trap 'exit 143' TERM
        start_owned backend java -jar "$jar"
        await_json "$WAREHOUSE_E2E_BACKEND_URL/actuator/health"
        start_owned web node --input-type=module -e 'import {preview} from "vite"; await preview({root:process.argv[1],preview:{host:"127.0.0.1",port:14188,strictPort:true,proxy:{"/api":{target:process.env.WAREHOUSE_E2E_BACKEND_URL},"/actuator":{target:process.env.WAREHOUSE_E2E_BACKEND_URL}}}})' "$ROOT/web"
        await_json "$WAREHOUSE_E2E_WEB_URL/actuator/health"
        export PLAYWRIGHT_JSON_OUTPUT_NAME="$RUNTIME/warehouse-playwright.json"
        rm -f -- "$PLAYWRIGHT_JSON_OUTPUT_NAME"
        web npx playwright test --config playwright.warehouse.config.ts "e2e/warehouse/$1" --project warehouse-desktop --project warehouse-mobile --trace on
        jq -e '.stats.expected > 0 and .stats.unexpected == 0 and .stats.skipped == 0 and .stats.flaky == 0' "$PLAYWRIGHT_JSON_OUTPUT_NAME" >/dev/null || refuse 'zero, skipped, flaky, or failing browser tests'
        for project in warehouse-desktop warehouse-mobile; do
            jq -e --arg project "$project" '[.. | objects | select(.projectName? == $project and .status? == "expected")] | length > 0' "$PLAYWRIGHT_JSON_OUTPUT_NAME" >/dev/null || refuse "zero successful tests in $project"
        done
        ;;
esac
