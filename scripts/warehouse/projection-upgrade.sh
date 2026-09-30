#!/usr/bin/env bash
# Sourced only by qa.sh while its isolated database environment and lock are held.

projection_upgrade() {
    local historical_head=c164c4eb4224fb51ebffd669c87caf4fde2af1df
    local historical_base="$RUNTIME/warehouse-v175-fixture"
    local test_path=server/src/test/kotlin/com/duluin/ftth/fulfillment/WorkOrderMaterialUsageITProjectionUpgrade.kt
    local helper_path=server/src/test/kotlin/com/duluin/ftth/inventory/WarehouseSchemaDatabase.kt
    local source_path="$ROOT/server/src/historicalTest/kotlin/com/duluin/ftth/fulfillment/WorkOrderMaterialUsageITProjectionUpgrade.kt"
    local report_dir
    report_dir="$RUNTIME/projection-upgrade-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 4)"
    if [[ ! -e "$historical_base" ]]; then
        GIT_MASTER=1 git -C "$ROOT" worktree add --detach "$historical_base" "$historical_head"
    fi
    [[ ! -L "$historical_base" && $(GIT_MASTER=1 git -C "$historical_base" rev-parse HEAD) == "$historical_head" ]] || refuse 'historical projection application identity mismatch'
    python3 - "$historical_base" "$ROOT" "$test_path" "$source_path" "$helper_path" <<'PY'
from pathlib import Path
import subprocess, sys
old, current, test, source, helper = sys.argv[1:]
status = subprocess.check_output(['git', '-C', old, 'status', '--porcelain'], text=True)
assert all(line[3:] in (test, helper) and line[:2] == ' M' for line in status.splitlines()), 'Unexpected historical source modifications'
old_migrations = Path(old) / 'server/src/main/resources/db/migration'
current_migrations = Path(current) / 'server/src/main/resources/db/migration'
for path in old_migrations.glob('*.sql'):
    assert path.read_bytes() == (current_migrations / path.name).read_bytes(), f'Historical migration changed: {path.name}'
# Overlay the regression and current database-isolation adapter. Historical business fixtures and product code stay pinned.
(Path(old) / test).write_bytes(Path(source).read_bytes())
(Path(old) / helper).write_bytes((Path(current) / helper).read_bytes())
PY
    mkdir -m 700 "$report_dir"
    printf '%s\n' "$report_dir" > "$RUNTIME/projection-upgrade-latest.txt"
    rm -f -- "$historical_base/server/build/test-results/test/TEST-com.duluin.ftth.fulfillment.WorkOrderMaterialUsageITProjectionUpgrade.xml"
    local result=0
    (cd "$historical_base" && timeout --kill-after=30s 1800s ./gradlew :server:test \
        --tests '*WorkOrderMaterialUsageITProjectionUpgrade' --no-daemon --no-parallel \
        -Dorg.gradle.workers.max=2 -Pkotlin.compiler.execution.strategy=in-process) || result=$?
    python3 - "$historical_base" "$ROOT" "$source_path" "$report_dir" "$historical_head" <<'PY'
from pathlib import Path
import hashlib, json, shutil, subprocess, sys
import xml.etree.ElementTree as ET
old, current, source, destination, head = sys.argv[1:]
reports = Path(old) / 'server/build/test-results/test'
destination = Path(destination)
files = list(reports.glob('TEST-*ProjectionUpgrade.xml'))
assert len(files) == 1, 'Exactly one historical upgrade report is required'
for path in files:
    shutil.copyfile(path, destination / path.name)  # Private: HTTP reports can contain credentials.
root = ET.parse(files[0]).getroot()
counts = {key: int(root.attrib.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
assert counts == dict(tests=1, failures=0, errors=0, skipped=0), counts
migration = Path(current) / 'server/src/main/resources/db/migration/V175_22__warehouse_consumed_projection_lineage.sql'
proof = dict(historicalApplication=head,
    currentCommit=subprocess.check_output(['git', '-C', current, 'rev-parse', 'HEAD'], text=True).strip(),
    regressionSha256=hashlib.sha256(Path(source).read_bytes()).hexdigest(),
    migrationSha256=hashlib.sha256(migration.read_bytes()).hexdigest(), counts=counts,
    schemaBefore='175.21', schemaAfter='175.22', applicationRole='warehouse_app',
    assertions=['real receipt, issue, technician acknowledgement and physical use through historical HTTP',
                'zero and descendant corruption committed before upgrade, both historical replays succeed',
                'exactly one immutable migration applied; both replays and reads reject with 409',
                'no accounting changes from denied replay'])
(destination / 'verification.json').write_text(json.dumps(proof, indent=2) + '\n')
print('PASS: historical V175.21 -> V175.22 projection upgrade, one test, two corruption cases')
PY
    (( result == 0 )) || return "$result"
}
