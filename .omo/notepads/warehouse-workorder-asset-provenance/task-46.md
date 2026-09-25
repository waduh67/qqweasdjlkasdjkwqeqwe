## Historical regression supplement: five version-correct application fixtures prepared

Follows pushed0ee64efd. Full R3/session11018 STILL RUNNING, now over1386passed and
39failed observed; no final result. Preflight R2/session47709 remains queued. Freeze
server/test/QA sources until R3 exits and preserve all available reports before fixes.
CI36116539885 at c622a85b is still active; queued checkpoint-only CI36120239630 was
cancelled before execution. No active full report has been cancelled.

The latest pending-historical-upgrades.patch.gz now covers FIVE tests and supersedes
its old3-test version plus obsolete pending-episode-upgrade.patch.gz. Confirmed R3
failures include both WarehouseFulfillmentITOwnerUpgrade and BngLineageUpgrade:
modern ONU mapping requires retired_at absent in their175.29/175.34 schemas.
Their historical app is pinned3bfe12331428eb43740c8c0399b61a956c8820a7 (schema175.36).
The only test behavior change is removing the mocked later MaterialSettlementService;
all original source, tenant timing, replay and preservation assertions remain.
Customer title/deployment use fd2cf7c5; episode uses dfa25e79. All groups add exact
current migrations, current isolated-DB adapter and migration inventory helper;
the customer deployment group also overlays the reviewed orphan-revision fixture.

Future qa.sh full regression deadline becomes14400s; CI server job360minutes,
including bounded historical groups. R3 remains on its original7200s deadline and
has not been modified. If it times out, preserve log and in-progress binary results,
mark the attempt incomplete, and run the fixed full suite again. Never count old
focused XML as a complete server result. The private prepared compatibility-focused-r1.sh
assumes a normally finished R3 with matching WAREHOUSE_COUNTS/XML; adapt a NEW runner
if timeout prevents complete XML. It has NOT been launched. It selects14 modern
suites, requires every selected suite, archives results before historical groups,
and validates the old full report hashes before clearing stale root XML.

Additional pending patches remain as listed in the preceding checkpoint. Latest
historical draft passes bash syntax, actionlint and git apply --check, but has not
compiled/executed. No product or migration changes made at this checkpoint.
The next core step is still complete R3 -> fix all failures -> focused/full reruns.

An optional asynchronous question was sent asking the user to authorize separate
reviewer agents for the plan's independent F1–F4 audit. NO ANSWER YET; do not spawn
agents without explicit authorization. Core work continues while awaiting a reply.
Goal ACTIVE; tasks46–48/F1–F4 remain open. Continue after commit/push; do not final.

## Latest recovery checkpoint: expanded regression fixture corrections prepared

Goal ACTIVE. Tasks 1–45 DONE;46 IN PROGRESS;47–48/F1–F4 OPEN. Continue after push.
Remote prior checkpoint362f321c; local work/warehouse-completion targets
origin/feat/warehouse-workorder. No main merge, image publication or deployment.

Full server R3/session11018 is STILL RUNNING; at least1208 passed/37failed observed,
not final counts. Exact safe observation: task46/full-server-r3-progress.json.
Preflight R2/session47709 remains queued on the host lock. Sources/wrappers are
FROZEN. Archive complete R3 XML/counts before applying any patch or focused rerun.
CI36116539885/c622a85b full server also continues. All other jobs passed. Duplicate
queued CI36119073644 for docs checkpoint was cancelled before it began; bb0a's
older pending run was replaced by concurrency. Do not cancel the active full run.

Prepared patches in task46 (all gzip source-only, never runtime credentials):
1. pending-network-fixtures.patch.gz —9legacy network classes, real receipt/issue/ACK/install.
2. pending-predictive-history.patch.gz —existing historical LEGACY_UNRESOLVED fixture.
3. pending-rma-source-control.patch.gz —fake positive becomes negative; actual RMA positive strengthened.
4. pending-handover-source-denial.patch.gz —CLOSED/INACTIVE historical corruption returns
   SOURCE_NOT_VERIFIED before ordinary state checks; all no-effect assertions stay.
5. pending-deployment-orphan.patch.gz —deferred orphan probes use next unused physical
   revision; a separate test retains immediate duplicate revision23505/no-effects proof.
6. pending-historical-upgrades.patch.gz —SUPERSEDES pending-episode-upgrade.patch.gz.
   Move three unchanged historical tests into mandatory qa.sh historical-upgrades;
   pinned fd2cf7c5 for title175.69/deployment175.63 and dfa25e79 for episode175.86,
   each applying the entire current migration chain in a separate database. Overlays
   the updated orphan helper after patch5. No product sources or old migrations altered.
7. pending-browser-reassignment.patch.gz —additional real UI review of reassignment,
   no inherited custody, cancellation and same-unit return in returns.spec.ts. Draft
   TypeScript compiles in private browser-draft; NOT APPLIED or executed in browser.
Apply check passes for every patch; backend compilation/runtime has NOT run yet.
Do not apply obsolete episode-only patch alongside expanded historical patch.
Actual result binary confirms missing inventory_repair_replacement_request in old
schemas, RMA_HANDOVER_EXECUTION_REQUIRED, SOURCE_NOT_VERIFIED vs STALE_REVISION,
and23505 masking the five deferred orphan checks. Final XML remains authoritative.

Two other historical HTTP fixtures may need the same treatment if full R3 proves
failures: WarehouseFulfillmentITOwnerUpgrade at175.29 and BngLineageUpgrade at175.34.
They currently mock the later MaterialSettlementService. Inspect actual failures;
prefer the correct historical application without disabling current guards. Do not
weaken expected tenant-scope, authorization or immutable-history assertions.

All declared G/W/B QA sources for48planrows exist; safe inventory is source mapping
only, not evidence that all tests passed. Runbooks are committed but SQL preflight
still must execute. Next: complete R3 -> inspect every failure -> apply/fix focused
fixtures and historical gates -> returns browser supplement -> full server/final CI
-> preflight/runbook proof -> final audits and concrete review. No subagents or
independent reviewer approval claimed; no native release from compile evidence.

## Current checkpoint: CI evidence retained; full server regression still running

Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Tasks 1–45
DONE; 46 IN PROGRESS; 47–48/F1–F4 OPEN. Goal stays ACTIVE. Continue after push.
The previous remote checkpoint is bb0a4603. No merge, publish or deployment.

CI run 36116539885 at c622a85b has passed all eight browser jobs (22 tests), legacy
upgrade/restart (6), web (576), shared (44), both iOS compile targets and actual
Docker smoke. All 11 encrypted archives were downloaded, hash-checked, decrypted
privately and matched against successful executed cases. Safe aggregate proofs:
task46/ci-browser-web-shared-r2.json and ci-native-legacy-r2.json. Full server and
aggregate acceptance are still pending; do not call the workflow green.

Local full-server-r3.sh/log remains ACTIVE (session 11018), over 990 passed and 30
failed cases observed so far, not final counts. Preflight R2 (session 47709) waits
for the same host lock. Preserve complete R3 XML/counts before any focused rerun.
Do not edit executing/queued wrappers, server/test/QA sources or applied migrations.
Network, predictive-history and RMA source patches remain unapplied in task46
pending-*.patch.gz. A fourth pending-episode-upgrade.patch.gz moves the unchanged
V175.86 test into a mandatory historical-application gate, applies all current
migrations, and preserves every original assertion. Syntax/actionlint/apply-check
pass only; no compile or runtime PASS. Confirm the actual XML cause before applying.
Handover CLOSED/INACTIVE also fail an expected-code assertion; inspect final XML
and current source validation order before changing expectations.

Runbook and review drafts are now saved with this checkpoint. UI labels/relative
links match source; existing browser runs execute the documented flows. SQL preflight
is NOT yet verified: queued runbook-preflight-r2.sh must pass the actual app-role
probe plus wrong migration/unit/cutover negatives before task47 can close. The Arch
Chromium install note and cleanup preserve real failures. Draft historical runner
is not enabled until the full suite finishes. Next: full report -> fixture fixes ->
focused verification -> full regression/CI -> runbook proof -> final audits/review.
No subagents authorized; no independent-review approval or native release claimed.
Raw reports, decrypted archives, private env and traces remain in ignored runtime.

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

# Current task46 regression checkpoint (supersedes older notes)

Separate database fixture adapter compiled. R3 executed10 tests:5PASS/5FAIL.
PASS: public-qualified migration isolation1, receipt SIGKILL/retry2, legacy normalized
provisioning data upgrade/rejection2. FAIL: schema fixture target0 has no matching
migration3; two provisioning fixtures targeted126/124 although actual immutable
guards are129/127 and prerequisite columns only exist at128/126. Corrected adapter
allows explicit no-migration initialization; fixture targets match actual guarded
versions and assert exactly one migration before checking downgrade, then upgrade
to latest. No applied migration edited; app authorization unchanged.

R4 is running .omo/runtime/database-isolation-r4.sh/log. Followup runner
isolation-followup-r2.sh requires10/10 before QA function restoration, historical
projection R2 and portalgreen. If this prerequisite fails it stops without repair.
Do not reuse old canceled queues. Private logs/reports retained. No full-suite PASS.

Historical projection1test passed in version-correct application R1, but its wrapper
cleanup failed; new separate database helper must pass R2. Its regression source is
server/src/historicalTest and full qa.sh server makes the historical runner mandatory.

Portal contact real HTTP red test failed new committed email login401. Draft fix
adds a dedicated REQUIRES_NEW committed-contact entrypoint while ordinary credential
sync keeps caller REQUIRED transaction. Green broad portal gate pending.

# Task46 pending regression work

Task45 remains IN PROGRESS. No full regression gate claimed yet.

1. WorkOrderMaterialUsageITProjectionUpgrade runs current application services against
   a schema stopped at175.21. New receipt/source readers require later columns
   (transfer_receiver_id), so initial consumedCase fails before intended historical
   corruption. Restore a version-correct historical fixture/runner; do not skip the
   test, add production old-schema fallbacks, weaken authorization, or fabricate a
   passing HTTP409 from an unrelated missing-column error. Historical source commits:
   c164c4eb added test;94d6d2ec added immutableV175.22 guard;6f7d2426 changed fixture later.
   Current projection regressions and the historical upgrade contract must both pass.

2. Actual task45 browser customer creation repeatedly logs PortalCustomerContactListener
   failure: InvalidDataAccessApiUsageException / TransactionRequiredException, No active
   transaction. Source: portal/application/service/PortalCustomerContactListener.kt
   handles AFTER_COMMIT, invokes PortalIdentitySyncService.sync with REQUIRED. That
   joins completed transaction resources. Need real HTTP regression showing credential
   username/email/phone index changes after committed contact edit, rejects old contact,
   preserves unrelated/other-tenant accounts and ignores rolled-back contact changes.
   Keep ordinary credential-service sync within its caller transaction; do not globally
   switch sync to REQUIRES_NEW and lose uncommitted credential visibility. A separate
   committed-contact entry point invoked inside TenantContext.runAs can establish a
   new transaction. This finding has NOT been fixed or tested yet.

3. Run full server + ModularityTests, web lint/unit/build/E2E typecheck, KMP shared and
   actual macOS native compile gate. Preserve full reports before focused reruns.
   Native compilation is not hardware/runtime/release proof. No subagents authorized.

## Full regression R3 running — fixture repairs prepared, NOT APPLIED
Modern full suite currently reports27 failures:26 older network fixtures still
create serial-only ONUs (alarm, GIS, incident, notification, topology, predictive,
survey), plus one obsolete positive RMA SQL control that relabels an original issue
instead of providing the inspected-return/acknowledged-RMA source now required.
The full suite must finish and archive ALL XML before applying repairs or rerunning.
Do not mistake strings such as FAILED inside a passing test name for failed cases;
parse anchored PASSED/FAILED result suffixes or the final JUnit reports.

Recovery drafts saved in task46 evidence (not claimed verified):
- pending-network-fixtures.patch.gz: nine classes/11registration sites, new common
  warehouse receipt ->issue ->technician ACK ->authorization ->customer API helper.
  Reuses real catalog/technician per tenant, permits multiple devices per test, no
  stock SQL seed. Original source hashes in pending-network-fixtures.json.
- pending-predictive-history.patch.gz: seven-day historical metrics use explicit
  LEGACY_UNRESOLVED ONU fixture; they must not be rebound to a new installation.
- pending-rma-source-control.patch.gz: old fake RMA source becomes a negative guard;
  positive decoder assertion moves into the actual full physical RMA scenario.
All three patches pass git apply --check only. Apply AFTER full-serverR3 completes;
then compile/focused affected suites, inspect and fix actual failures, preserve old
full reports, and run the complete suite again. Sources are still unchanged.

## 2026-09-25 temporary development swap

Added4GiB `/swap/warehouse-development.swap` after availableRAM dropped to~400MiB
and original512MiB swap was full. Btrfs filesystem mkswapfile; swapon; nofstabchange.
Preserved all existing processes/resources. Reactivateafterreboot withsudo swapon;
only deactivate/remove afterworkstops and sufficientRAM is available. LocalR3still
RUNNING1513pass39failobserved; no finalreport/claim.
