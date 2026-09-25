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

## Current checkpoint: web serial identity fixed; finish task45 browser journeys

Follows cf8bc93c; locate containing commit with git log -1. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. CONTINUE after commit/push.
No subagents authorized. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

Product fixes: sameSerialIdentity helper compares observed versus stored raw serial
with trim/uppercase, blank false. Applied to customer install/discovery, MaterialSaya
receipt/unused return/handover, return inspection/repair and RMA. Raw history, source
snapshot equality, frozen command bytes and exact retries preserved. Existing wrong
serial/quantity/role/session/reset checks remain. Reproduced3RMA failures and1install
failure before fixes; combined9files/45tests PASS8.40s. Actual web production build,
TypeScript and targeted lint PASS. Safe proof task45/serial-identity-web-verification.json.

Original complete numeric LOAN browser gate remains2/2green at cf8bc93c; safe proof
numeric-browser-verification.json. New asset browser r1 failed2 tests because the
fixture tried canonical option names while the actual source list preserves raw
serial. That exposed real customer installation comparisons now fixed. R2 failed
before execution due a closing-parenthesis typo in loan-reuse-scenario.ts; fixed and
added warehouse E2E TypeScript config/script (draft). No zero-test PASS claimed.

CURRENT runner session37432: .omo/runtime/customer-assets-browser-r3.sh/log,4cases
(2scenarios x desktop/mobile). Firstcase reached QA successfully then failed only
QA text expectation: frozen review shows canonical serial, not raw. After runner
finishes, revert completeNumericJourney review assertion to fixture.serial, leaving
installation source option on fixture.recordedSerial. All four currently share that
wrong QA expectation. Wait for completion/archive reports before next run.
No product fix required for canonical QA witness. R1/r2 raw reports/traces privately
archived with customer-assets-browser-r1/r2-report.json and -artifacts. r3 must archive.
Never print/commit authenticated traces or env. Volumes retained by all wrappers.

UNCOMMITTED browser drafts (compile+lint checked; not yet passing real gates):
asset-journey.ts actual createWO/signature/remove/intake/inspect/service/RMA;
loan-reuse-journey.ts serial-only plan/explicit reservation/ACK/swap/return/reset/reuse;
loan-reuse-scenario.ts checks preserved A history and same-unit B, final8available/2installed;
customer-assets.spec.ts 2cases; numeric-journey raw/canonical names and signature helper;
fulfillment optional mixed receipt serial prefix and cable cost; approvals optional
COUNT_VARIANCE; count-journey.ts blind counter/create/start/observe/submit/recount;
exceptions.spec.ts rejection and transit count stale, tablet768/themes, final900m/9available.
Exceptions never executed yet. Added tsconfig.warehouse-e2e.json and package command
npm --prefix web run typecheck:warehouse-e2e. All drafts compiled+targeted lint passed.
Product-only checkpoint intentionally leaves these drafts to finish next. User asked
frequent recoverable commits: keep working until browser gates are coherent, then
commit/push them and the actual evidence. Do not stop/final at this checkpoint.

Remaining45: finish customer-assets andexceptions, actuallegacycutover browser, extend
issue/returns realMaterialSaya coverage if needed. Legacy newtenant ENFORCED report is
covered by provenance.spec but is not an oldtenant cutover. Need legitimate populated
pre-upgrade fixture, no fabricated stock/admission bypass. Then46 full regressions
(including known175.21 ProjectionUpgrade fixture),47 runbooks/preflight,48requiredCI,
F1–F4 currentartifact audits. V178.6 immutable bothQAenvs; next178.7. No native/hardware
claim. All QA serialized under existing host fd8 lock; never kill unrelated processes.

## RMA and numeric checkpoint after aa22c32e

V178.7 passed14 focused RMA tests and9 tests on retained OLD populated upgrade.
2142 preexisting tenants/23 tables unchanged; old migration checksums unchanged.
Both environments immutable through178.7, next178.8. Numeric browser2/2 passed with
actual ONU record917500MM/9available/1installed. MatrixR2 issue2/2 passed; returns,
exceptions and visual asset rerun still executing. HistoricalV172 UI2/2 passed;
post-upgrade cutover failed fixture area-access ordering, requires correction.
No task45 completion claimed.

## Legacy area bootstrap fix

R3 proved historical UI2/2, then exposed first-area picker deadlock after upgrade.
CustomerAreaField now honors existing customer administration unrestricted-area
contract, without changing warehouse authority. Red3/1; green4/4 in2files.
R4 real legacy rerun queued; no cutover completion claimed.

## Issue, return, count browser gates

8 real tests PASS across both projects: issue2(R2),returns4(R3),exceptions2(R3).
Actual restricted ACK/unused return, independent warehouse intake and inspection,
60/40transfer discrepancy, blindcounter/independent rejection and append-only stale
recount. Exact scenarios and source hashes in issue-return-count-browser-verification.json.
Assets screenshot rerun and legacy UI cutover remain pending.
