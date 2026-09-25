## Current follow-up: report owner boundary fix prepared; original R7 is still running

Original is FROZEN at40cbd34f; unfiltered R7/session39048 has passed projection1 and
all7historical tests and is now in modern regression. Edit only validation. CI2c1
36131581036 remains active; feature40 CI36135632377 is also active; source3624 CI
36134311367 remains queued. Preserve their complete results, but do not attribute
those old product results to the new report change below.

Validation now adds InventoryWorkOrderReadPort, implemented inside workorder, and
removes all3 direct inventory report SQL reads of the work_order table. Scope comes
from the existing current-authority fence; the owner uses tenant-scoped ordered FOR
SHARE reads so area changes wait until the report transaction ends. Inventory only
joins the returned IDs; empty scope admits no WO, while receipt prints without a WO
remain valid. Existing ledger, quantities, cost math and snapshots are unchanged.

New WarehouseReportWorkOrderScopeIT has4 expected cases: real LOAN/SALE HTTP journeys
with work-area changes, denied/restored costs/assignments/print while warehouse access
remains; owner scope empty/unrestricted/foreign tenant and area; and a real concurrent
area update blocked by the owner's read lock. Compilation/runtime are PENDING.
CI focused selection now also includes all report tests and ModularityTests (30classes,
expected258cases; derive actual count from XML). Workflow5 and actionlint PASS.

Commit and push this validation checkpoint to NEW refs/heads/work/warehouse-report-scope
(confirmed absent), so CI can verify the new product without cancelling existing runs.
The local branch stays work/warehouse-regression-fixtures. Do not push this report fix
into executing original or assume old241proof remains a final proof for changed source.
After current R7 finishes, archive it, integrate verified fixes, run focused report tests
and a complete current regression. Final F1–F4 reviewers are explicitly authorized,
but must review the fixed and tested current source. No approval or deploy performed.

Downloaded actual2c1 tested Docker archives and verified GitHub ZIP SHA, both tar SHA,
config SHA/image ID/commit labels and equality with revalidated smoke evidence. Safe
proof task48/ci-2c1d8e08-tested-images-verification.json. No docker load/push/deploy.

# Warehouse continuation — current checkpoint, 2026-09-25

This page supersedes runtime instructions in older notes. Historical receipts remain
in the task notes and Git history. Continue working after checkpoints; the user asked
for a long run to completion with committed and remote recovery points.

## Objective and confirmed decisions

Complete `.omo/plans/warehouse-workorder-asset-provenance.md`. Tasks1–45 are done;
46 is in progress,47 has current runbook/browser/preflight proof,48 awaits aggregate
CI. Final F1–F4 must still independently review the completed implementation.
The user explicitly authorized separate reviewer agents after fixes/primary tests
are ready. Use that authorization then; no reviewer verdict has been issued yet.
The user explicitly chose to reject deletion of tenants with protected history and
use Suspend; empty tenant deletion remains supported. All3 HTTP cases and the178.9
control-cascade upgrade pass. No permanent-history purge is requested.
No main merge, registry publication or production deployment is authorized/performed.

## Worktrees and next execution

Original: `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe`, branch `work/warehouse-completion`.
Validation/edit checkout: original + `/.omo/runtime/warehouse-regression-validation`,
branch `work/warehouse-regression-fixtures`. Both contain product2c1d8e08 and historical
runner correction3624efb8. This checkpoint adds docs/evidence/notes only.
Commit validation, fast-forward the CLEAN original, then push the feature checkpoint
to `refs/heads/feat/warehouse-workorder`. Preserve the already pending source CI on
validation; do not cancel it just because this evidence checkpoint exists.

NEXT: launch the prepared original `.omo/runtime/full-server-r7.sh` with umask077 and
a private outer log. It requires the archived241modern and7historical PASS, checks
source hashes/equivalence, archives any replaced XML, then runs unfiltered `qa.sh server`.
That mandatory command runs projection upgrade, all7historical upgrades, and the full
current modern suite. Modern deadline14400s. Require complete XML, >3700tests, zero
failures/errors/skips, and ModularityTests plus WarehouseCompatibilityIT. Focused
success alone does not complete46. Do not edit executing original source/wrappers;
use validation for any necessary fix, then integrate only after the active run ends.
Old full-server-r4/r5/r6 wrappers and pending patches are superseded; never reapply.

After launch, read `.omo/runtime/ACTIVE-WAREHOUSE-RUN.json` if present for session/log
pointers; inspect process state and logs before assuming an old session still runs.
If a runner fails, preserve log/XML/binary before any focused rerun. Do not mistake
Traceback/preflight exit for an active test just because no FAILED test line exists.

## Verified current results

- Modern focused:241tests/23suites PASS at2c1d8e08; raw private
  `.omo/runtime/compatibility-focused-r4-reports`, safe task46/local-focused-r4-verification.json.
- Historical-only R5/session65866 ENDED0:7tests/7suites/4groups PASS at3624efb8.
  Raw root in `.omo/runtime/historical-upgrades-latest.txt`, safe
  task46/local-historical-r5-verification.json. Actual XML and every runner/test/migration
  input hash rechecked. Hikari pool eviction fixes title cached-plan failure; all
  original upgrade preservation/replay/negative/positive assertions remain.
- Historical application pins are recorded in scripts/warehouse/historical-upgrades.sh
  and each group in the safe R5 proof. Use those exact identities; never guess or
  update a pin to make an old fixture compile.
- CI36131581036 at2c1d8e08: focused stage and all nonserver jobs PASS; full server still
  running at this checkpoint.12 encrypted archives downloaded/hash checked/decrypted
  privately; actual reports revalidated.22browser +6legacybrowser +576web +44shared,
  2actual iOS compilations (not runtime/release), real image smoke/restart. Safe proof
  task46/ci-2c1d8e08-nonserver-verification.json. CI36134311367 at3624efb8 is pending
  and includes the historical runner correction plus3 overlay regression checks.
- Current runbook:178.9 docs, valid relative links, preserved2legacy customers/2ONUs/
  2UI-created SKUs,2positive/6negative read-only app-role preflight probes after V172
  upgrade/restart. Safe task47/current-runbook-verification.json. Available stock is
  not invented for legacy tenants. Numeric reference flow ends917500MM/9stockONUs.
- Release guard28tests plus actionlint PASS; task48/current-release-guard-verification.json.
- Current mobile handover screenshot is visually reviewed and shows the installed
  ISP-owned loan and accepted customer handover without obscuring toasts:
  task45/numeric-1789-accepted-loan-mobile.png +numeric-1789-handover-verification.json.
  This is implementing-agent inspection, not independent F3 approval.

## Migration and isolation constraints

Highest applied/package178.9, next178.10. Never edit applied migration bytes.
178.8 checks return-title tenant scope before an RLS-filtered lookup.178.9 narrowly
allows control-row FK cascades only after the correctly scoped empty tenant parent
is absent. Direct control deletion/live epoch reset still reject; protected history
and bystander tenants remain intact.58 deferred guard entry checks pass.
Historical fixtures use SEPARATE DATABASES with public schemas. They must not use
sibling schemas in the shared QA database. Runner preserves pinned migration bytes,
rejects unknown files and checks the shared function sentinel.

Serialize complete QA lifecycle with outer fd8 lock:
`/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock`.
Per-checkout qa.sh uses fd9 `.omo/runtime/warehouse.lock`. Ports25432/29000/17880/14188.
JAVA_HOME=/usr/lib/jvm/java-21-openjdk; original runtime/gradle-home is GRADLE_USER_HOME.
Task-owned Compose only; stop/down retain volumes. Do not reset/delete volumes,
kill unrelated processes, reset/stash/rebase/amend or force-push.
Host has4GiB temporary Btrfs swap at `/swap/warehouse-development.swap`; not in fstab.
After reboot it can be reenabled with sudo swapon. Do not swapoff under load.

## Evidence and remote recovery

This is a PUBLIC repo. Never print or commit env files, auth traces, raw XML/system-out,
DB dumps or decrypted artifacts. Runtime is0700; launch logs under umask077.
Force-add only reviewed safe evidence/notes paths because `.omo` is ignored.
Private download/revalidation helpers live in original `.omo/runtime`; CI raw artifacts
are age-encrypted for the maintainer's existing SSH Ed25519 identity and expire after
14days (tested Docker archives3days). Preserve the private identity securely; never
upload it. Safe proofs include source/artifact/report hashes and actual counts.

Commit with GIT_MASTER=1 and per-command author fajarxfce <fajaralamsyah000@gmail.com>.
Push only explicit refs using SSH `/home/fajar/.ssh/id_ed25519` with inherited GIT_SSH
and GIT_SSH_COMMAND unset. Feature remote is feat/warehouse-workorder; validation is
work/warehouse-regression-fixtures. No force push or main update.

Old failures are archived, not passing evidence: original CI36116539885 had3787tests/
48fail; localfullR3 timed out124 with incomplete modern XML; CIb360 title cached-plan
failed; CI64e50573 had229focused/1empty-tenant failure. Their fixes pass current focused
and historical gates, but only a complete new full regression can establish46.
