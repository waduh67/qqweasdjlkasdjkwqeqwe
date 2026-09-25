#!/usr/bin/env bash
# Sourced by qa.sh after the owned environment and serialized QA lock are validated.

historical_upgrade_group() {
    local group=$1 historical_head=$2 report_root=$3
    shift 3
    local classes=("$@") historical_base="$RUNTIME/warehouse-historical-$group-fixture"
    local report_dir="$report_root/$group"
    mkdir -m 700 "$report_dir"
    if [[ ! -e "$historical_base" ]]; then
        GIT_MASTER=1 git -C "$ROOT" worktree add --detach "$historical_base" "$historical_head"
    fi
    [[ ! -L "$historical_base" && $(GIT_MASTER=1 git -C "$historical_base" rev-parse HEAD) == "$historical_head" ]] || refuse 'historical upgrade application identity mismatch'
    python3 - "$historical_base" "$ROOT" "$report_dir" "${classes[@]}" <<'PY'
from pathlib import Path
import hashlib, json, subprocess, sys
old, current, destination = map(Path, sys.argv[1:4])
classes = sys.argv[4:]
tests = {f'server/src/test/kotlin/{name.replace(".", "/")}.kt':
         f'server/src/historicalTest/kotlin/{name.replace(".", "/")}.kt' for name in classes}
helpers = ['server/src/test/kotlin/com/duluin/ftth/inventory/WarehouseSchemaDatabase.kt',
           'server/src/test/kotlin/com/duluin/ftth/inventory/WarehouseMigrationInventory.kt',
           'server/src/test/resources/warehouse/task17-migration-inventory.txt']
# The deployment orphan uses an unused physical revision so later sequence
# uniqueness does not mask the document-lineage corruption being tested.
if 'com.duluin.ftth.customer.CustomerDeploymentUpgradeIT' in classes:
    helpers.append('server/src/test/kotlin/com/duluin/ftth/customer/CustomerDeploymentGraphFixture.kt')
overlays = tests | {name: name for name in helpers}
migrations = 'server/src/main/resources/db/migration'
current_migrations = {str(path.relative_to(current)): path for path in (current / migrations).glob('*.sql')}
allowed = set(overlays) | set(current_migrations)
status = subprocess.check_output(['git', '-C', str(old), 'status', '--porcelain', '--untracked-files=all'], text=True)
assert all(line[3:] in allowed and line[:2] in (' M', '??') for line in status.splitlines()), 'Unexpected historical worktree modifications'
pinned = subprocess.check_output(['git', '-C', str(old), 'ls-tree', '-r', '--name-only', 'HEAD', migrations], text=True).splitlines()
for name in pinned:
    original = subprocess.check_output(['git', '-C', str(old), 'show', 'HEAD:' + name])
    assert original == (old / name).read_bytes() == (current / name).read_bytes(), f'Historical migration changed: {name}'
runner = 'scripts/warehouse/historical-upgrades.sh'
inputs = {runner: hashlib.sha256((current / runner).read_bytes()).hexdigest()}
for name, source in current_migrations.items():
    target = old / name
    if target.exists():
        assert target.read_bytes() == source.read_bytes(), f'Migration overlay changed: {name}'
    else:
        target.write_bytes(source.read_bytes())
    inputs[name] = hashlib.sha256(source.read_bytes()).hexdigest()
for target, source in overlays.items():
    path = old / target
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((current / source).read_bytes())
    inputs[source] = hashlib.sha256((current / source).read_bytes()).hexdigest()
proof = {'currentCommit': subprocess.check_output(['git', '-C', str(current), 'rev-parse', 'HEAD'], text=True).strip(),
         'historicalApplication': subprocess.check_output(['git', '-C', str(old), 'rev-parse', 'HEAD'], text=True).strip(),
         'classes': classes, 'sourceInputs': inputs}
(destination / 'inputs.json').write_text(json.dumps(proof, indent=2) + '\n')
PY
    local arguments=() name result=0
    for name in "${classes[@]}"; do
        arguments+=(--tests "$name")
        rm -f -- "$historical_base/server/build/test-results/test/TEST-$name.xml"
    done
    (cd "$historical_base" && timeout --kill-after=30s 2400s ./gradlew :server:test \
        "${arguments[@]}" --no-daemon --no-parallel -Dorg.gradle.workers.max=2 \
        -Pkotlin.compiler.execution.strategy=in-process) || result=$?
    python3 - "$historical_base" "$ROOT" "$report_dir" <<'PY'
from pathlib import Path
import hashlib, json, shutil, sys
import xml.etree.ElementTree as ET
old, current, destination = map(Path, sys.argv[1:])
proof = json.loads((destination / 'inputs.json').read_text())
reports = []
for name in proof['classes']:
    source = old / f'server/build/test-results/test/TEST-{name}.xml'
    if source.is_file():
        shutil.copyfile(source, destination / source.name)
        reports.append(source)
assert len(reports) == len(proof['classes']), 'Missing historical upgrade report'
for name, digest in proof['sourceInputs'].items():
    assert hashlib.sha256((current / name).read_bytes()).hexdigest() == digest, 'Historical upgrade input changed during execution'
counts = []
for source in reports:
    root = ET.parse(source).getroot()
    result = {key: int(root.attrib.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert result == dict(tests=1, failures=0, errors=0, skipped=0), result
    assert len(root.findall('testcase')) == 1 and all(root.find('testcase/' + kind) is None for kind in ('failure', 'error', 'skipped'))
    counts.append({'suite': root.attrib['name'], **result, 'reportSha256': hashlib.sha256(source.read_bytes()).hexdigest()})
proof.update(status='PASSED', suites=counts, tests=sum(row['tests'] for row in counts),
             applicationRole='warehouse_app', isolatedDatabases=True,
             schemaAfter='complete current migration chain',
             limitation='Historical HTTP application creates and replays the fixture; current application is additionally covered by the full modern suite')
(destination / 'verification.json').write_text(json.dumps(proof, indent=2) + '\n')
print('PASS: historical upgrade group,', proof['tests'], 'tests with complete current migrations')
PY
    (( result == 0 )) || return "$result"
}

historical_upgrades() {
    local report_root="$RUNTIME/historical-upgrades-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 4)"
    mkdir -m 700 "$report_root"
    printf '%s\n' "$report_root" > "$RUNTIME/historical-upgrades-latest.txt"
    historical_upgrade_group fulfillment 3bfe12331428eb43740c8c0399b61a956c8820a7 "$report_root"         com.duluin.ftth.fulfillment.WarehouseFulfillmentITOwnerUpgrade         com.duluin.ftth.fulfillment.WarehouseFulfillmentITBngLineageUpgrade
    historical_upgrade_group customer-title fd2cf7c58f02022525e655a8b0884ac2f5df49b0 "$report_root" \
        com.duluin.ftth.customer.CustomerAssetTitleUpgradeIT \
        com.duluin.ftth.customer.CustomerDeploymentUpgradeIT
    historical_upgrade_group customer-episode dfa25e793d186eb8a4a0c5b96cd33e4c549edbbb "$report_root" \
        com.duluin.ftth.customer.CustomerAssetEpisodeRevisionUpgradeIT \
        com.duluin.ftth.monitoring.WarehouseDiscoveryITReviewHistorical
    historical_upgrade_group discovery-receipt 1580145c27d41a63cb2485f2bfadcba7cc0fb8a3 "$report_root" \
        com.duluin.ftth.monitoring.WarehouseDiscoveryITR2Upgrade
    python3 - "$report_root" <<'PY'
from pathlib import Path
import json, sys
root = Path(sys.argv[1])
groups = [json.loads((root / name / 'verification.json').read_text()) for name in ('fulfillment', 'customer-title', 'customer-episode', 'discovery-receipt')]
assert all(group['status'] == 'PASSED' for group in groups)
assert sum(group['tests'] for group in groups) == 7
(root / 'verification.json').write_text(json.dumps({'status': 'PASSED', 'tests': 7, 'groups': groups}, indent=2) + '\n')
print('PASS: seven historical upgrade tests; no skipped cases')
PY
}
