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
