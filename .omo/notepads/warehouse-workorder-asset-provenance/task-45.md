## Current checkpoint: historical UI cutover6 PASS, including restart

Follows pushedfcdd987a; locate containing commit with git log -1. Goal ACTIVE;
tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. Continue after commit and push.

LegacyR6 PASS6 real browser cases: before2/14.7s, after2/46.3s, restart2/7.5s,
desktop1280 and mobile375. Actual V172 application creates2customers+2ONUs through
UI in a fresh marker-verified database. Latest unchanged migrations upgrade it;
first customer area assignment, reviewed PROVENANCE_ONLY evidence, independent
restricted OPENING_BALANCE approval, zero baseline and ENFORCED finalization all
work. Same IDs/raw serials/checksums persist through restart; available stock empty.
Both shared QA databases retain identical function definitions. Two inspected safe
mobile images and legacy-browser-verification.json saved under task45. Raw traces,
fixture credentials and complete screenshots remain private in the retained run.

R5 before2PASS/after2FAIL was only an E2E raw-response expectation: HTTP uses
sourceSnapshot.serialNumber, whereas the decoded web model has source.serial.
R6 corrected the assertion without changing product behavior. Legacy fixture uses
separate database/public schema; QA health and readiness bind exact database name.
No sibling-schema migration runs remain. R6session26618 complete0; all services down,
all volumes and isolated historical DB retained. BothV178.6/V178.7 immutable.

Next launch prepared .omo/runtime/task45-browser-matrix-r4.sh/log: all8 specs after
shared QA function restoration, including history-card screenshots. It is NOT yet
running at this commit. Do not edit any E2E source while that matrix is active.
Then fullserver/web/KMP, runbook preflight, CI/release gates, F1–F4 and user review.
Portal60PASS, isolation10PASS and historical projection1PASS are saved in task46;
full regression has not run. Unfiltered server deadline draft is now7200s because
there are2456 test declarations plus real restart processes; focused gates stay1800s.

Other uncommitted drafts: asset screenshots; docs/preflight; Docker build/context
fixes; CI count validator9testsPASS and encrypted archive helper. age1.3.2 installed
with pacman; five actual report files encrypted/decrypted byte-identically using
existing SSH identity. No private key printed or uploaded. GitHub workflow/recipient
variable not configured yet; no CI gate completion or image build claimed. No agents,
main merge, deployment or native runtime claim. Keep raw credentials/logs/XML private.

## Current checkpoint: portal regression60 PASS; historical upgrade1 PASS

Follows pushedddf494b3; locate containing commit with git log -1. Goal ACTIVE;
tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. Continue after commit and push.

Portal contact fix has real red1FAIL (new committed email401), green60PASS/6suites,
no failures/errors/skips. AFTER_COMMIT calls a dedicated REQUIRES_NEW entrypoint;
ordinary credential creation retains REQUIRED sync. Email/phone replacement, old
identity revocation, rollback, unrelated tenant, auth/recovery/self-service and
customer asset privacy all verified. Safe proof task46/portal-contact-verification.json.

Historical projection R2 PASS1test with two genuine historical corruption cases,
version-correct pinned175.21 application and separate database; exact175.22 migration
turns both replay/read paths into409 without new accounting. Cleanup succeeded.
Safe proof historical-projection-upgrade-verification.json. The full qa.sh server
gate runs this regression before modern tests. Isolation10PASS remains valid.

QA restoration PASS: only3 e2e functions replaced from unchanged clean migrations;
322tables/database preserved with identical counts/digests (146219 test/31173 e2e
rows), original activation cutoff and all Flyway history preserved. Safe proof
qa-function-recovery-verification.json. No production or stock SQL repair. Both
QA volumes retained; V178.6/V178.7 immutable, next178.8. FollowupR3/session19087 done0.

ACTIVE legacy UI runner .omo/runtime/legacy-browser-r5.sh/log (session50850), using
new dedicated database, expected database readiness and shared-function fingerprint.
Do not edit ANY E2E source while this runner or the subsequent browser matrix runs.
Legacy6 UI cases not yet passed. Existing CustomerAreaField bootstrap fix already
pushed71d5; source/history screenshots and legacy harness still uncommitted pending
this verification. Prepared all8-spec task45-browser-matrix-r4.sh NOT launched yet.

Other uncommitted drafts: operational docs/preflight; Docker context/build cleanup;
ci-results.py allowlisted report helper, currently only tried against real JUnit
and Playwright reports (negative validator checks/CI workflow remain to implement).
No Docker image build, publish, deployment, main merge, native runtime or independent
review claimed. No subagents authorized. Next: finish legacy/allbrowser; fullserver/
web/KMP; runbook preflight; requiredCI/image smoke; F1–F4 and concrete user review.
Raw env, logs/XML and authenticated browser traces stay private. Do not final here.

## QA restoration completed after isolation checkpoint ddf494b3

Default QA functions restored from fresh unchanged migrations362/version178.7.
warehouse_test required0 replacements; warehouse_e2e required3 (approval guard,
returned asset, repair step). Each322 public tables has identical before/after rows
and digests:146219 server-test rows,31173 browser rows. All deterministic warehouse
functions match the clean reference; original per-database activation function and
all Flyway history preserved. R1 stopped before DDL on expected activation timestamp
difference; R2 passed. Safe proof task46/qa-function-recovery-verification.json.

Current runner is isolation-followup-r3.sh/log (session19087), now historical
projection R2 then portalgreen. Earlier followupR2/session61520 has finishedfailed;
no partial function repair from it. Prepared legacy-browser-r5.sh and all8-spec
browser matrixR4 are NOT launched. Freeze E2E source while either browser runs.
Historical V172 base is on origin/main, projection base is feature ancestry; both
available with full fetch-depth0. No main merge needed. Continue goal work.

## Current checkpoint: dedicated database isolation gate passes 10/10

Follows pushed1f4ebece; locate containing commit with git log -1. Goal ACTIVE.
Tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN. Continue after commit/push.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. No subagents,
production deployment, main merge, or native release. Both QA volume sets retained.

R4 database-isolation gate PASS10tests/4suites/5m44s, zero failures/errors/skips:
SchemaUpgrade3, ProvisioningMigrationCompatibility4, DatabaseIsolation1, ReceiptRestart2.
New marker-verified database fixtures prevent historical public-qualified migrations
from replacing shared QA functions. App remains non-owner/NOSUPERUSER/NOBYPASSRLS/
NOCREATEDB; migration owner NOCREATEDB. Exact129/127 provisioning guards downgrade
invalid old provenance; clean expansion/collisions/units preserved. Real receipt
socket-loss/SIGKILL and transaction-termination recovery pass. Safe proof task46/
database-isolation-verification.json; raw R1–R4 failures/results remain private.

Known QA corruption: prior legacy sibling-schema runs replaced four shared public
functions from V175.132 although Flyway remains178.7. Asset browserR3 failed4cases
as a result. No business rows were manually changed; no production migration needed.
V178.6 andV178.7 immutable in BOTH retained environments. Next version178.8.

ACTIVE followup: .omo/runtime/isolation-followup-r2.sh/log (session61520). It checked
10/10 then runs repair-qa-functions-r1.sh: migrate fresh reference, restore only known
four drifting functions, compare ALL public rows/counts/digests and Flyway history.
Then projection-upgrade-r2.sh (new database adapter; historical application pinned),
then portal-contact-green.sh. Inspect actual results; failure stops later phases.
Do not launch duplicate QA or wait for old canceled wrappers. Current default QA is
new marker98b38fbf54518f766065f31955d52574; no environment override or volume deletion.

Historical projection regression moved unchanged to server/src/historicalTest.
Full qa.sh server now makes its version-correct pinned application gate mandatory,
then runs all modern tests. R1 historicaltest1PASS, but wrapper cleanup failed;
R2 helper verification still pending at this checkpoint. No skipped historical gate.

UNCOMMITTED drafts: legacy browser now uses a dedicated DB plus shared function
fingerprints and expected database readiness; still needs6 UI phases. Customer area
bootstrap fix already pushed71d5; legacy rerun remains. Portal REQUIRES_NEW committed
contact entrypoint has real red regression; green pending. Asset history screenshots,
runbooks/preflight, Docker build/privacy fixes are still drafts, not release proof.

NEXT: inspect repair/historical/portal; commit each verified checkpoint. Finish legacy
cutover6 and all real browser gates after repair; fullserver/web/KMP regression;
runbook preflight; mandatoryCI/image smoke; F1–F4 and concrete user review. Never
print/commit raw auth traces, env, logs/XML. Do not final at checkpoint. Older notes
below include superseded targets, failed runs and canceled queues.

## Active checkpoint: QA database isolation repair

Follows pushed1f4ebece. Goal ACTIVE; tasks1–44 DONE,45 IN PROGRESS,46–48/F1–F4 OPEN.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Continue after
checkpoints. No subagents authorized; no production deployment/main merge.

V175.132 explicitly replaces four public.* functions. Legacy sibling-schema browser
migration overwrote shared warehouse_e2e functions despite shared Flyway178.7. That
caused matrixR3 customer-assets4 failures (RETURN_ASSET_INSPECTION_BINDING). Earlier
asset4PASS predates corruption; issue/returns/exceptions8PASS remains recorded. No
product migration needed. V178.6/V178.7 immutable BOTH environments; next178.8.

Uncommitted database-fixture.sh creates marker-verified generated databases; owner
stays NOCREATEDB and app non-owner/NOSUPERUSER/NOBYPASSRLS/NOCREATEDB. Migration,
provisioning, restart and legacy browser fixtures use separate databases/public.
Shared function fingerprints must remain identical. IsolationR1 failed8setup cases:
TimescaleDB requires CREATE EXTENSION first. Corrected. R3 passed5/10; schema target0 and two stale provisioning targets failed.
Those fixtures are corrected; R4 is running with10-case gate. See task-46.md.
isolation-followup-r2.sh runs repair/historical/portal only after R4 is fully green.
Shutdown grace is60s, volumes retained. ShellCheck new/changed harnesses PASS;
test-environment.sh bash syntax PASS, four preexisting ShellCheck warnings remain.

Historical projection test moved to server/src/historicalTest. qa.sh full server
runs pinned version-correct application gate first. R1 test1PASS/two corruptions,
but wrapper cleanup failed; new database adapter must rerun via projection-upgrade.
Old queued legacyR4/portalgreen/preflight/fullserver are CANCELED. Portal contact
AFTER_COMMIT fix has real red test; green pending. Legacy6browser cases pending.

Prepared private repair-qa-functions-r1.sh creates a fresh migrated reference,
restores only the four known drifting shared QA functions and verifies ALL public
row counts/digests and Flyway history unchanged. Run after isolation gate passes.
Then historical gate, portalgreen, legacy6/assets4, fullserver/web/KMP, preflight,
CI/imagechecks, F1–F4 and concrete user review. Docs/preflight/Docker drafts untested.
Archive raw reports privately before reruns; never commit env/auth logs/XML/traces.
Do not final at checkpoint. Notes below are historical, including stale queues.

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
