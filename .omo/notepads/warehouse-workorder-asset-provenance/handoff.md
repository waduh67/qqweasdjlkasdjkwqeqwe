## Current checkpoint: image smoke PASS; full server running; repair patches saved

Goal ACTIVE. Tasks1–45DONE;46inprogress;47–48/F1–F4open. Continue afterpush.
No subagents. Current source c622a85b + documentation/evidence changes only.
CI36116539885: imagesPASS (14replays/16snapshots/4stockkinds before+restart); all8
browsersPASS; legacy6PASS; web576PASS/shared44PASS/native2COMPILED. Serverrunning.
Image tar/config hashes reverified locally; safe task48/docker-image-smoke-verification.json.
Private imagearchives in.omo/runtime/ci-remote-r2/tested-images; raw reports encrypted.

LIVE LOCAL full-serverR3 session11018, wrapper/log.omo/runtime/full-server-r3.sh/log.
Env7PASS, historicalPASS; modernfull currently27failures mostlyoldserial-onlyfixtures.
Do NOTedit runningbackend/QA/testsource oroverwritefullJUnit. Waitforcompletionand
archive. PreflightR2 session47709 queued withread-onlypositive+3negativechecks.
Prepared,NOTAPPLIED patches saved safely in task46 evidence: pending-network-fixtures.patch.gz
(nineclasses+actualwarehouse-registrationhelper),pending-predictive-history.patch.gz
(explicithistoricallegacydevice),pending-rma-source-control.patch.gz (genuineRMApositive
andfakesource-negative). All apply--checkPASS; no compile/executionclaim. Afterfull
reportarchive, applythenfocusedtest/fix, preserveoldreports, rerunfullsuite.

Warehouse concurrency now cancel-in-progress:false, so checkpoint pushes preserve
runningfullreport and queue thelatestfollowup. Uncommittedrunbookdocs47+preflight.sql
need actualprobeverification. Finishregressions/docs/gates/audits; no prematurefinal.

## Previous checkpoints (superseded)

## Current checkpoint: tasks1–45 done; full regression and Docker image CI active

Goal ACTIVE. Continue until46–48 and final audits are handled; no checkpoint final.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. No subagents.
Task45 completed in38e068e3:22real browser cases/eight specs; legacy6separatePASS.
QA dependency fix9ce591d5: MinIO built from checksum-pinned upstreamrelease source;
Timescale pinned to the existing tested digest. Environment7/7PASS including S3.
RemoteCI36114803368 had web576PASS/native2COMPILED/shared44PASS; overallFAILED
because formerMinIO registrydenial and parser[jvm] (fixed2907bc7f). Preserve raw
encrypted artifacts in .omo/runtime/ci-remote-r1; publicsafeproof task46.

LIVE full-serverR3 session11018 (.omo/runtime/full-server-r3.sh/log), underfd8.
Env7PASS, historicalprojectionPASS, complete modernserver suite RUNNING. Archive
.omo/runtime/full-server-r3-reports whenfinished. NEVER editactivewrapper/QA/server
inputs. PreflightR2 session47709 (.omo/runtime/runbook-preflight-r2.sh/log) queued.
OldR2 cancelledwhilequeued, zeroexecuted; MinIOsourcebuild91077completed successfully.

Current exact-image CI checkpoint adds mandatoryimages prerequisite, actualHTTP
smoke/restart, exportedtestedimages andcheckedpublication+immutabledeployreferences.
24guardtests andactionlintPASS; realDockerimagejob has NOTexecuted yet. Featurepush
startsCI; no imagepublish/mainmerge/deploy authorized orperformed. Draftdocs47 and
preflight.sql await actualpositive+3negativerun. Review CI thenfix failures, archive
fullserverreports, finishrunbooks/audits andkeepcommitting+pushingsafecheckpoints.

## Earlier checkpoints (superseded)

## Current checkpoint: task45 complete; continue final regression and release gates

Tasks1–45 DONE;46 IN PROGRESS;47–48/F1–F4 OPEN. Goal ACTIVE. No subagents authorized.
Browser R5 finished exit0:22 real tests/eight specs/both projects, no skipped/flaky.
Safe proof task45/browser-matrix-r5-verification.json and four inspected history PNGs.
LegacyR6 separately6PASS. Current product/source identity recorded in both proofs.
Remote CI36114803368 FAILED as expected: old pinned MinIO registry denies access.
Web576/115PASS; native2targetsCOMPILED; shared44/7modulesPASS, parser[jvm]bug now
fixed in2907bc7f and tested11PASS plus revalidated identical encrypted CI reports.
Full-serverR2 was safely cancelled WHILE QUEUED, zero tests, to use final dependency.
LIVE session91077: .omo/runtime/minio-source-build-r1.sh/log under hostfd8, building
QA-only MinIO from upstreamrelease source with pinned tar/base digests. No byte
equivalence claim to oldvendorimage. Do not edit executing Dockerfile/wrapper.
Next: wire verified source image + pinned Timescale in QA Compose, run environment
S3 tests and full server, run preflightpositive/3negative, finish exact Docker smoke
and CI. Uncommitted image/workflow/publish drafts have not run real containers yet.
Keep preserving private traces/env, retaining volumes, committing and pushing.

## Previous checkpoints (superseded)

## Current checkpoint: isolated fixtures, portal and legacy cutover verified; final matrix running

Goal ACTIVE; tasks 1–44 DONE, 45 IN PROGRESS, 46–48/F1–F4 OPEN. Keep working after push.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. No subagents authorized.
Latest committed fixes: ddf494b3 separate DATABASE/public for every migration/restart
fixture (isolation 10/10); fcdd987a AFTER_COMMIT portal contact index transaction
(60/60 portal tests); f49250b0 historical V172 UI -> current cutover -> restart (6/6).
QA shared warehouse_e2e functions recovered against a fresh migrated reference:
three deterministic functions restored, activation timestamp preserved, 322 tables /
31,173 business rows and all Flyway checksums unchanged. warehouse_test unchanged.
Never use sibling schemas for historical migrations that explicitly reference public.
V178.6 and V178.7 immutable in BOTH retained environments; next available V178.8.

Current browser matrix R5 uses unchanged product with corrected approval workbench
assertion and new scrolled asset-history screenshots. Five specs already pass:
setup 4, receiving 2, provenance 2, issue 2, returns 4. Exceptions is running;
numeric and customer-assets follow. Private reports/source hashes/artifacts:
.omo/runtime/task45-matrix-r5-<spec>-{report.json,source.json,artifacts/}.
Session 42129 / task45-browser-matrix-r5.sh/log. DO NOT edit ANY E2E files while active.
Full server R2 queued session 51803 / full-server-r2.sh/log under host fd8 lock.
It requires all eight R5 reports green first, then runs historical projection gate
and ALL modern server tests. Full gate deadline 7200s; focused remains 1800s.
Archives complete JUnit in .omo/runtime/full-server-r2-reports before any rerun.
Do not edit executing/queued wrappers or kill unrelated Java process 1045933.

CI foundation being committed: mandatory server/web/KMP/eight browser/legacy/native
jobs before publish/deploy; safe result summaries + encrypted raw reports. 17 Python
guard tests pass (including failing/missing/empty/skipped/flaky/cancelled cases),
actionlint passes. age and actionlint installed via pacman on Arch. Repository
variable WAREHOUSE_EVIDENCE_RECIPIENT is the existing user's SSH Ed25519 PUBLIC key;
private key never uploaded. Real five-report encryption roundtrip byte-identical.
Task48 NOT DONE: actual remote CI, exact Docker image smoke and publication identity
must still be completed. Docker context/bootJar fixes, docs47 and preflight remain
drafts. Preflight runner .omo/runtime/runbook-preflight-r1.sh exists but is NOT queued
or verified. Old active-session lists below are obsolete. Continue through 45–48
and final audits, preserving private credentials/traces and retaining QA volumes.

## Earlier checkpoints (superseded by the status above)

## Current checkpoint: real customer asset journeys pass; task45 continues

Follows e3e9708b; locate containing commit with git log -1. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit/push.
No subagents authorized. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

customer-assets.spec.ts passes4/4 real tests (365.5s), desktop1280/mobile375, no retry,
skips or mocks. Actual UI tenant/catalog/receipts/roles/scopes/WO, restricted technician
and QA, signatures,17.5m residual intake and exact917.5m/9available. Fixture now sets
category ONU and requires real onuId. SALE: remove, vendor repair, reset, RMA ACK and
reinstall to original customer, stillCUSTOMER title. LOAN: swap, recover/reset oldunit,
reuse sameasset atB preserving closedA history, final917.5m/8available/2installed.
Safe proof task45/customer-assets-browser-verification.json; full private artifacts:
.omo/runtime/task45-matrix-r1-customer-assets-{report.json,artifacts,source.json}.

V178.7 NEW/APPLIED/IMMUTABLE in default browser warehouse_e2e public schema.
SHA726b103ef37215b2af736cde9b6cedb336b02b7726b042f4707b673ee02fc081.
Optional RMA SKU category NULL previously compared createsOnu=false to SQLNULL;
coalesce predicate now matches application without weakening other bindings. Added
real catalog-change regression: red3tests/1fail(NULL); ONU andROUTER passed. Focused
post-fix green and retainedOLD-volume upgrade PENDING. Nextfree178.8; do not edit7.
Currentqa.sh shares PID/readiness helpers in qa-processes.sh and gates E2E TypeScript.
Browser artifact includes7; focused server result must be recorded before release.

ACTIVE/QUEUED under existing fd8 host lock (inspect logs before new work):
- session22133 .omo/runtime/task45-browser-matrix-r1.sh/log: customer-assets4PASS,
  now numeric -> issue -> returns -> exceptions. Archives each spec separately;
  stops on first failure. Extended issue/returns and exceptions are still drafts.
- session14743 rma-category-green.sh/log: queued4suites; archives XML privately.
- session22932 rma-category-populated-upgrade.sh/log: queued; swaps to preservedOLD
  markerabc63ab0045a7096566cf763c8f477fc, fingerprints23tables, appliesonly178.7,
  runs RMA deployment+guards, restoresdefaultmarker98b38fbf54518f766065f31955d52574.
- session36699 legacy-browser-r2.sh/log: queued; newowned schema eachrun.
Finished: rma-category-red30247 (3tests1fail), exceptionsR2 74347 (2testsfailedonly
expectedREJECTED vs actualcorrectREWORK_REQUIRED; expectation fixed), legacyR1
41652 (historical V172 app boots;2beforeUItests createcustomers but wrong rowselector;
no ONU seed/upgrade yet). Private legacyR1 schema/artifacts retained at
.omo/runtime/warehouse-legacy-933016b17c7d5f1cb02fee66da1699c0.
Legacy R2 fixes button selection/Aksi sel/Edit and expects oldONU canonicalization.

UNCOMMITTED legacy runner and web/e2e/warehouse-legacy fixtures/config are NOT PASS.
They build clean historicalV172 abaecd9e129cdfe12058d777fad3da2a9320c3c2, create
customer+ONU through oldUI, forward-upgrade same isolatedschema, resolvehistoryonly,
independentzeroopening/finalize, restart and compareoldIDs/checksums. No stockSQLseed.
QA oldcheckout .omo/runtime/warehouse-legacy-ui-base built successfully. Never edit
running scripts; preserveprivateenv/traces, retainvolumes; do not kill unrelatedJava.

Remaining45: complete current browsermatrix and actuallegacy cutover; add screenshots
that scroll into drawer history cards (current viewport images omit lower history).
Then46 fullregressions and version-correct175.21 ProjectionUpgrade fixture; new real
portal AFTER_COMMIT contact-sync transaction failure is recorded in task-46.md (not
fixed yet). Then47runbooks/preflight,48requiredCI,F1–F4currentartifactaudits. No native
or hardware claim, no prematurefinal. Prior checkpoint history preserved below.

## Prior checkpoint context (superseded by current status above)

Current resume status is at the top of `continuation.md`: tasks1–44 complete;45 in progress;46–48/F1–F4 remain. Numeric browser desktop/mobile checkpoint green; continue the active full-scope goal. Historical notes below are superseded.

# Warehouse Workorder Asset Provenance Handoff

## Resume point

- Plan: `.omo/plans/warehouse-workorder-asset-provenance.md`
- Tasks 1-20 independently confirmed and checked in the plan.
- Next task: Task 21, loan/sale handover and title. Task 21 remains unchecked; do not start it in this checkpoint.
- Task 20 product head: `6c3701e4a6b8e76f2753dc3c464f704a227b5553`.
- Task 20 migrations: `V175.59` through `V175.66`.
- Task 20 executor: `ses_f5fb981d9ffeaRTXaIe71novG2`; verifier: `ses_f5eb68d44ffeifB4XAh374xCWn`.
- Task 20 verdict: confirmed/high; final JAR SHA256 `3be595c434a76fce4e1ccadc5efc2fa7da5a9b712aedc7c51eb7f3f5caa80bf9`.

## Recovery command

```text
/start-work warehouse-workorder-asset-provenance
```

## Delivery rules

- Push each approved checkpoint immediately with a normal fast-forward push.
- No merge, rebase, amend, reset, force-push, or product/task13 edits during checkpoint sync.
- Keep runtime, evidence, logs, archive, environment, Boulder, and secrets paths excluded.
- Future commits use the effective global identity `fajarxfce <fajaralamsyah000@gmail.com>` without conflicting per-command overrides.

## 2026-09-14 - Task20 confirmed checkpoint

- Task20 is checked in the plan at product head `6c3701e4a6b8e76f2753dc3c464f704a227b5553`.
- Final verifier: `ses_f5eb68d44ffeifB4XAh374xCWn`, confirmed/high; migration range `V175.59` through `V175.66`; 249 distinct tests.
- Executor: `ses_f5fb981d9ffeaRTXaIe71novG2`; task21 remains unchecked and is the next action.

## 2026-09-15 - Task21 confirmed checkpoint

- Tasks 1-21 are checked in the tracked plan at product head
  `729f245998117b646feb56577273f6a75da4a152`; task22 remains unchecked.
- Task21 executor: `ses_f5d380e4fffeKBNKRFZ5C8Ubp1`; final verifier:
  `ses_f5c054b5dffevN0uLx6c1tzeD3`, confirmed/high, 570 executions.
- Migrations V175.67-.79 and task21 receipts remain immutable; next action is
  task22 asset swap/removal/topology relocation. Do not start it in this checkpoint.

## 2026-09-15 - Task22 confirmed checkpoint

- Tasks 1-22 are checked in the tracked plan at product head
  `dfa25e793d186eb8a4a0c5b96cd33e4c549edbbb`; task23 remains unchecked.
- Task22 executor: `ses_f5b0136f1ffeJmjJSpMn6LnyGT`; final verifier:
  `ses_f59e67eacffeVf28hlgEdebi2F`, confirmed/high.
- Migrations V175.80-.89 and task22 receipts remain immutable; next action is
  task23 discovery/auto-provision/CPE integration. Do not start it in this checkpoint.

## 2026-09-14 - Task19 confirmed checkpoint

- Task19 is checked in the plan at product head `b219e9e87cda6d5f85df3eac8f40c022a79b7c58`.
- Final verifier: `ses_f6106f2ddffeO2YMQRB9OAUq0x`, confirmed/high; migration range `V175.48` through `V175.58`; 497 distinct tests.
- Task20 remains unchecked and is the next action. No task20 behavior was started.

## 2026-09-11 - Task15 confirmed checkpoint

- Tasks 1-15 are checked in the plan; task16 is the exact next action and remains unchecked.
- Executor `ses_f6e4cc107ffeee5YCvm3LNHQjo` completed product head `b3294ba3c5f7cafdf3508294ce88266ef9ab8964`.
- Verifier `ses_f6d70ecd9ffeZ8bbAtuczW5Nk4` returned `confirmed`/`high`, safe to mark task15, with 483 distinct tests.
- V175.14 SHA256 `391da11be6b5704402b02d1d47d2f02ffe3027c2b07ea49251d797e318595e5c`; next action is task16 physical use.

## 2026-09-11 - User-requested pause checkpoint

- Pause recorded for compaction at the task14 re-verification boundary; do not continue implementation or verification in this checkpoint.
- Tasks 1-13 remain checked in the plan; task14 remains unchecked and task15 must not start.
- Implementation SHA: `3a4f2f1ffbb0343066b503a305ef540800e508b0`.
- Executor: `ses_f70d4a1fcffe1ahZ1RGUJIhQxo`.
- Verifier: `ses_f6fd0321cffeCyHsPe5gpV05FO`; partial evidence only: prior failures reject correctly, first exact run 35/35, extra immutable snapshot/destination probes pass, but no final verdict.
- Next action: resume that verifier for the final verdict; if confirmed, mark task14, otherwise return findings to the executor.
- Resume command: `/start-work warehouse-workorder-asset-provenance --make-pr`.
