## Latest checkpoint: F2 legacy correction prepared with five new real tests

Read legacy-read-scope.md. Current validation includes the scoped legacy HTTP
projections and canonical reservation reader; independent F2 source recheck accepts
the correction, runtime/compile still pending. Workflow now selects34focused classes.
Push this commit to NEW work/warehouse-legacy-read-scope, preserving active report CI.
The previous report remote remains work/warehouse-report-scope at a1d4eb19.

Older CI36131581036 is COMPLETED SUCCESS and actual reports revalidated:3783modern/
609suites,241focused,7historical+1projection,375sourceinputhashes,all15jobsPASS. Safe
proof task46/ci-2c1d8e08-complete-server-verification.json. This old result cannot
close later report/legacy changes. LocalR7/session39048 is still running at40.
Current e178 full CI36137258991 and feature40 CI36135632377 remain active.

Independent F4 preliminary and six safe records are preserved in evidence root;
images were inspected without loading/publishing. F1 draft update/expiry findings
remain unresolved and are the next product task. Final approval remains pending.

# Warehouse continuation — current checkpoint, 2026-09-25

This file supersedes old runtime directions. Continue to completion after every saved
checkpoint; the user requested a long run and remote recovery commits.

## Objective and confirmed decisions

Complete `.omo/plans/warehouse-workorder-asset-provenance.md`. Tasks1–45 have prior
implementation marks, but review now found C7/C8 omissions requiring correction.
46 remains in progress;47/48 and F1–F4 are not finally approved. User explicitly
allows independent reviewers. User explicitly chose rejection of tenant deletion
when protected history exists, with Suspend guidance; empty deletion stays supported.
No main merge, image publication or production deployment is authorized/performed.

## Active checkouts and processes

Original `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe`, work/warehouse-completion, is FROZEN
at40cbd34f while local full-server-R7/session39048 runs. Projection1 and historical7
PASS; modern tests still running. Safe progress helper is original
`.omo/runtime/safe-test-progress.py .omo/runtime/full-server-r7.log`.
Do not edit executing original source or wrappers; preserve complete reports or mark
an incomplete run honestly. Original `.omo/runtime/ACTIVE-WAREHOUSE-RUN.json` holds
process pointers. R4/R5/R6 wrappers and pending patches are superseded.

Edit checkout is original + `/.omo/runtime/warehouse-regression-validation`, local
branch work/warehouse-regression-fixtures. Current product e1782718 was pushed to
origin work/warehouse-report-scope. Do not update original until R7 ends/archives.

CI36137258991 at exact e1782718 has compiled all tests and passed the30-class focused
step. Actual focused XML is not available until the server job ends; expected258 is
NOT an executed count yet. Full server/historical stage remains in progress. All
nonserver jobs completed successfully and their actual reports were downloaded,
hash checked/decrypted privately, then revalidated (see proof below).
Old full CI36131581036 at2c1 and feature CI36135632377 at40 remain active. Preserve
outcomes, but their old product cannot prove the e178 report change or later fixes.
Queued CI36134311367 at3624 was cancelled BEFORE any job after full Git delta proved
active40 has identical product/tests/migrations/runners/workflow (docs/evidence-only
change). No active job was cancelled; task48/ci-pending-3624-duplicate.json is receipt.

## Current independent review findings and next work

F2 preliminary REJECT at e178: task root f2-quality-security-preliminary.md. Legacy
GET /api/inventory/warehouses,items,custody,serialized/{id} bypass warehouse/area
scope; reservations also use obsolete asset.status=RESERVED rather than durable
open reservations. Root will route HTTP through fenced, scoped compatibility
projections, retain DTOs/internal owner APIs, and add real scope/revocation and
reserve/pick/release/dispatch regression cases. No final review approval exists.

F1 preliminary source inspection found C8 draft PUT+revision missing for transfer/
count and C7 idle draft expiration missing. No user-approved narrowing exists.
Reviewer is completing the resource matrix and actual evidence mapping, including
E(N) task-N index closure. Root must implement validated omissions, not weaken plan.

Review worktrees are detached e178: warehouse-f1-review, warehouse-f2-review and
warehouse-f4-review. Reviewers may only read product; no parallel Java/Gradle/Docker/
browser/database QA while R7 holds the host lock. F4 is downloading current tested
Docker archives privately in its own worktree; do not duplicate or publish/load.
F3 real browser/manual review has not started. Final reviewers need complete current
runtime gates; a source-only preliminary verdict is never final approval.

## Verified evidence and recent source correction

- e178 moves3 inventory report SQL work_order reads into InventoryWorkOrderReadPort
  implemented in the workorder owner, with ordered FOR SHARE tenant/area-scoped IDs
  in the same authority-fenced transaction.4 new real tests cover loan/sale reports,
  scope variants and a blocked concurrent area update. Current CI focused step PASS;
  actual XML/count pending the complete server artifact.
- task46/ci-e1782718-nonserver-verification.json: exact current actual22browser +
  6legacybrowser +576web +44shared,2 iOS compile tasks, image smoke/restart and all8
  preflight output hashes. Raw original .omo/runtime/ci-run-36137258991. This is
  PASSED_NONSERVER_ONLY, never a full regression or native release claim.
- Older2c1 focused241/23suites PASS: task46/local-focused-r4-verification.json;
  original .omo/runtime/compatibility-focused-r4-reports. All3 tenant deletion cases
  and178.8/178.9 guards pass. Later source changes require affected revalidation.
- Historical R5 ENDED0:7tests/7suites/4groups at3624; task46/local-historical-r5-
  verification.json binds actual XML and all inputs. Exact old application pins are
  authoritative in scripts/warehouse/historical-upgrades.sh. Current R7 also passed7.
- task47/current-runbook-verification.json binds178.9 docs/preflight to40/2c1 product;
 2legacy customers/2ONUs/2UI-created SKUs preserved, no invented stock. Numeric flow
  ends917500MM/9stockONUs. Current e178 raw nonserver upgrade proof confirms178.9.
- task48/current-release-guard-verification.json:28guards+actionlint PASS before
  e178. e178 changed focused class list; workflow5+actionlint rerunPASS.
- task48/ci-2c1d8e08-tested-images-verification.json checks actual Docker tar/config/
  commit identities against smoke. Current e178 archive verification is F4-owned.
- task45/numeric-1789-accepted-loan-mobile.png and matching proof show visible
  installed ISP loan and accepted handover. Implementer-inspected, not F3 approval.

## Next checkpoint rules

Save reviewed safe proofs, findings and handoff in validation with exact paths.
Push explicit work/warehouse-report-scope ref. Running CI is preserved by workflow;
only an unstarted duplicate may be cancelled after exact-source comparison.
Implement fixes with their meaningful tests in the same coherent product commit.
Run affected checks plus complete regression on final source. Do not duplicate a
complete exact-source CI run locally unless new changes/failures justify it. Never
promote focused/pass-step summaries or old products to final46 evidence.

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
and GIT_SSH_COMMAND unset. Feature remote is feat/warehouse-workorder. Current validation/report checkpoint remote is
work/warehouse-report-scope (local branch remains work/warehouse-regression-fixtures). No force push or main update.

Old failures are archived, not passing evidence: original CI36116539885 had3787tests/
48fail; localfullR3 timed out124 with incomplete modern XML; CIb360 title cached-plan
failed; CI64e50573 had229focused/1empty-tenant failure. Their fixes pass current focused
and historical gates, but only a complete new full regression can establish46.
