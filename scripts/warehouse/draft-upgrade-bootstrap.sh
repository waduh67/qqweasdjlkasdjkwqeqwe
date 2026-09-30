#!/usr/bin/env bash
# Sourced only by serialized qa.sh, with the owned test environment loaded.
prepare_draft_upgrade_bootstrap() {
    local pin=1d14f4b9aebcd1c1a9e8c84041259ff56a422005
    local historical="$RUNTIME/warehouse-draft-upgrade-seed" output="$RUNTIME/draft-upgrade-bootstrap"
    local seed=server/src/historicalTest/kotlin/com/duluin/ftth/inventory/WarehouseDraftUpgradeSeed.kt
    local destination=server/src/test/kotlin/com/duluin/ftth/inventory/WarehouseDraftUpgradeSeed.kt
    mkdir -p -m 700 "$output"
    if [[ ! -e "$historical" ]]; then
        GIT_MASTER=1 git -C "$ROOT" worktree add --detach "$historical" "$pin"
    fi
    [[ ! -L "$historical" && $(git -C "$historical" rev-parse HEAD) == "$pin" ]] || refuse 'wrong pinned draft bootstrap application'
    python3 - "$historical" "$ROOT" "$seed" "$destination" <<'PY'
from pathlib import Path
import subprocess,sys
old,current=map(Path,sys.argv[1:3]); source,destination=sys.argv[3:]
status=subprocess.check_output(['git','-C',str(old),'status','--porcelain','--untracked-files=all'],text=True)
assert all(row[3:]==destination and row[:2]=='??' for row in status.splitlines()), 'Unexpected pinned bootstrap modification'
target=old/destination
target.write_bytes((current/source).read_bytes())
PY
    export WAREHOUSE_DRAFT_UPGRADE_CLASSPATH_OUTPUT="$output/classpath.txt"
    (cd "$historical" && timeout --kill-after=30s 1200s ./gradlew :server:warehouseDraftSeedClasspath \
        --init-script "$ROOT/scripts/warehouse/draft-upgrade-classpath.gradle" --no-daemon --no-parallel \
        -Dorg.gradle.workers.max=2 -Pkotlin.compiler.execution.strategy=in-process)
    python3 - "$historical" "$ROOT" "$output" "$seed" "$pin" <<'PY'
from pathlib import Path
import hashlib,json,subprocess,sys
old,current,output=map(Path,sys.argv[1:4]); seed,pin=sys.argv[4:]
names=subprocess.check_output(['git','-C',str(old),'ls-tree','-r','--name-only',pin,'server/src/main','server/src/test','server/build.gradle.kts'],text=True).splitlines()
subprocess.run(['git','-C',str(old),'diff','--exit-code',pin,'--'],check=True,stdout=subprocess.DEVNULL)
inputs={}
for name in names:
    data=(old/name).read_bytes()
    inputs[name]=hashlib.sha256(data).hexdigest()
helpers={name:hashlib.sha256((current/name).read_bytes()).hexdigest() for name in [seed,'scripts/warehouse/draft-upgrade-bootstrap.sh','scripts/warehouse/draft-upgrade-classpath.gradle']}
descriptor=dict(pinnedApplication=pin,pinnedSourceInputs=inputs,currentHarnessInputs=helpers,
                classpathSha256=hashlib.sha256((output/'classpath.txt').read_bytes()).hexdigest())
(output/'descriptor.json').write_text(json.dumps(descriptor,indent=2)+'\n')
PY
    export WAREHOUSE_DRAFT_UPGRADE_DESCRIPTOR="$output/descriptor.json"
    export WAREHOUSE_DRAFT_UPGRADE_REPORT_DIR="$RUNTIME/draft-upgrade-handoff-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 4)"
    mkdir -m 700 "$WAREHOUSE_DRAFT_UPGRADE_REPORT_DIR"
    printf '%s\n' "$WAREHOUSE_DRAFT_UPGRADE_REPORT_DIR" > "$RUNTIME/draft-upgrade-handoff-latest.txt"
}
