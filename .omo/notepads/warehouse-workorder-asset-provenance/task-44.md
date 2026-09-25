## Current checkpoint: reassignment QA and real envelope recovery verified

This checkpoint follows pushed0e530ae1; locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–43 DONE,44 IN PROGRESS,45–48/F1–4 OPEN. CONTINUE after this checkpoint.

Both real mixed and serial-only jobs now complete and pass QA after reassignment.
The old technician is denied new use, then loses roles/areas; the new assigned tech
uploads completion proof while preserving the real customer handover signature.
Original physical source actors remain immutable and fully validated. Only today's
roster membership is removed from historical QA-source checks. No new consumption,
deployment, fake NONE or material review command. Mixed final917500MM available,
82500MM consumed,0techMM,9availableONU,1installed; serial-only cable stays1000000MM.
Forged original actor/asset, omitted deployment or bulk usage still fail real SQL.

Final current coverage108 tests/12 suites through broad+focused gates. Broadr1
ran108/7m5s,104passed4failed:1 test expected403 after role+area revoke but actual404;
3 old envelope fixtures attempted forbidden synthetic legacy checkpoints. Current
reassign suite2/2 PASS, and complete binding suite24/24 PASS1m48s replace those
results. Broad raw XML/counts preserved before reruns. Existing full numeric HTTP
loss/app restart/outbox redelivery gate also reran successfully on these changes.
Envelope tests now use real usage/completion/approval, actual persistent claim and
reconciliation; only delivery-copy bytes/type are corrupted. Original persisted
payload survives, no effects before reconciliation, valid retry applies1 settlement
and2 owner effects with no physical mutation. Test-only public port decorator.

V178.5 APPLIED/IMMUTABLE in BOTH owned QA environments, SHA256
85ae2a27ec50781cadc01871682aed0b26a17078fb64a2a0777df968d444ef81. Next178.6.
Actual populated upgrade2 tests PASS2m7s, only178.5 added;2136 existing tenants
retain identical rows/digests in13 physical/history tables and all earlier Flyway
checksums. All services stopped, NEW default restored, BOTH volume sets retained.
Safe proof: .omo/evidence/warehouse-workorder-asset-provenance/task44/reassignment-settlement-verification.json.
All runner sessions95084/96289/21790/91891 are finished; no active QA processes.

NEXT44: draft .omo/runtime/WarehouseNumericCountIT.kt (NOT compiled/executed) uses
full numeric fixture, full-bin count of900000MM+9ONUs before actual17500MM remnant
return; new position must invalidate submission withCOUNT_STALE and no adjustment,
then original917500MM/9ONU/1installed QA result. Copy into fulfillment tests and run.
Add different-key concurrent use/cut on full numeric fixture (one200,one409, one
usage/split, complete return+QA). Actual time-aware reuse still required: existing
WarehouseReturnITReuse/WarehouseReturnAssetFixture implement real loan remove,
inspection+reset, reissue and same-asset install for a second customer. Extend actual
history with delayed metric/staleACS checks; WarehouseDiscoveryITTemporal SQL-seeded
episodes remain supporting only. Decide whether full numeric case can force select
returned ONU among9others via actual reservation choices; do not seed stock/episodes.
Then45 full real browser,46 full regression incl known historical175.21 projection
fixture/schema mismatch,47 runbooks,48 requiredCI,F1–F4. No subagents authorized.
No production deploy/main merge/native release claimed. Keep immutable migrations.

Earlier checkpoints below are historical.

## Current checkpoint: full numeric restart/outbox recovery verified

This checkpoint follows pushed636209cf; locate its containing commit with git log -1.
Goal ACTIVE, tasks1–43 done,44 in progress,45–48/F1–F4 open. Continue working.
WarehouseRecoveryIT runs2 ordered tests with a full real warehouse/customer/QA flow.
Actual HTTP inspection response is held after commit and client socket reset before
any bytes arrive. Whole first Spring app closes; fresh app replays exact durable
inspection outcome. Actual outbox claim/reader/fulfillment delivery loses ACK; fresh
production dispatcher redelivers once, old lease fenced, attempt2DELIVERED,1 inbox
and1 observation. All numeric physical totals and counts survive unchanged:
917500MM available,82500MM consumed,0 techMM,9availableONU,1installed,10assets.
Only scheduling lease metadata is expired in SQL. No physical seed, fake delivery
algorithm or process kill. Historical usage current-scope access tested with oldJWT.
2 tests PASS1m17s; full reports privately archived before next run. Proof:
.omo/evidence/warehouse-workorder-asset-provenance/task44/numeric-recovery-verification.json.
R1 failed only a final404 expectation on terminal WO write; r2 explicitly proves
history200 before revoke/404 after, and correct terminal409 write with no effects.

NEXT: WarehouseReassignmentSettlementIT is currently an UNCOMMITTED regression.
First red attempt stopped at redundant signature upload requiring correctionReason;
fixture now reuses the real original customer handover signature while the new
technician uploads fresh completion proof. Redr2 running via
.omo/runtime/reassignment-settlement-red-r2.sh, log same basename.log; inspect
actual failure before product changes. If local drafts are lost, recreate2 boolean
cases(includeCable=true/false): full actual use/install/handover/return, reassign
before completion, new tech completes with original handover signature, QA should
verify those physical facts; old tech new-use403 and no new physical movement.
Suspect checks requiring original usage/deployment actor still in current roster.
No actor guards changed yet. Three current SQL definitions captured privately as
.omo/runtime/warehouse_{assert_fulfillment_snapshot,fulfillment_deployments_guard,assert_deployment_only_settlement}-1784.sql.
Do not edit applied migrations. V178.4 immutable in BOTH QA volumes; next178.5.
After reassignment proof/fix continue remaining44 races,45 browser,46 full regression
including historical175.21 fixture repair,47 runbooks,48 CI andF1–F4. No subagents.

## Current checkpoint: serial-only QA verified; recovery next

This checkpoint follows pushed aebe3912. Locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–43 DONE;44 IN PROGRESS;45–48/F1–F4 OPEN. Continue after this checkpoint.

QA now accepts a fully installed all-SERIAL plan without fabricating measured usage.
Bulk source is nullable only with complete actual receipt-backed deployment witnesses;
NONE still needs its explicit snapshot. No extra material review command or movement.
Named frozen device review remains byte-identical after real signed removal; foreign
and revoked original receipt-location access return404. Mixed bulk-source omission and
missing/foreign deployment witnesses are rejected by real SQL with atomic rollback.
Web/KMP hide measured-use actions for serial-only plans and retain default-compatible
metadata and exact prior/defaulttrue KMP guard bytes. Source actor guards remain intact.

PASS:16 server tests/6 suites2m44s;31 web tests/6 files5.98s; full TypeScript and
targeted lint;44 KMP JVM reported tests/14 suites28s and module graph. Only core:mvi
JVM test cached; changed modules ran. No native runtime claim. Actual old populated
upgrade adds only178.4;2133 existing tenants retain identical physical/history rows
across13 tables and every prior Flyway checksum.2 more serial tests PASS2m12s.
Proof: .omo/evidence/warehouse-workorder-asset-provenance/task44/serial-settlement-verification.json.
V178.4 APPLIED/IMMUTABLE in BOTH QA environments, SHA256
b00cf665eaebae1959e5d75113674e98efba3f6c6838fda57604c1cda0545003. Next free178.5.

NEXT: copy prepared private drafts .omo/runtime/WarehouseRecoveryIT.kt and
WarehouseResponseLossProbe.kt into server test inventory package; make numeric
saveCommand protected open. They use actual HTTP, response held after commit,
socket reset, @DirtiesContext full shutdown/fresh app, exact original inspection replay,
real outbox claim/reader/delivery, lost ACK, expired lease metadata and one owner effect.
Drafts have NOT compiled or run. Also prepared WarehouseReassignmentSettlementIT.kt
for the suspected stranded-QA path after fully used materials then reassignment.
Run it red before changing current actor guards. Fix only observed contract violations.
Remaining combined races, task45 real browser,46 full regression/historical175.21
fixture repair,47 docs,48 CI and F1–F4 still required. No subagents authorized.

QA currently STOPPED; new default restored. Current marker
warehouse-f7c0d53b912f-98b38fbf54518f766065f31955d52574; retained old marker
warehouse-f7c0d53b912f-abc63ab0045a7096566cf763c8f477fc. ALL volumes retained.
Private env archives must never be printed/committed. Host fd8 QA lock unchanged.
R3 server and populated-upgrade reports archived privately before any next rerun.
All serial runner sessions29728/92986/20829 finished0. No unrelated processes killed.

Earlier phases below are historical.

## Task44 checkpoint: install/use ordering verified; serial-only and recovery NEXT

This checkpoint follows pushed c92151f5. Locate its containing commit with git log -1.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Goal ACTIVE.
Tasks1–43 DONE;44 IN PROGRESS;45–48/F1-F4 OPEN. Keep working after this checkpoint.

First measured use after ONU install now advances the shared physical revision and
remains a first usage. A positive delta after installation binds the actual latest
usage predecessor across deployment revisions. DB unique document revision and
owner sequence validation preserve historical reads; physical fact/snapshot/posting
revisions match. Web/KMP choose first/corrective use from latestUsageId, retaining
current revision/source/authority preflight and exact attempted-command replay.

Verification:51 server tests/6 suites PASS3m52s;14 web/3 files PASS3.87s; TypeScript
and targeted lint pass; KMP42 reported tests/14 suites green27s, module graph passes
(only core:mvi test task cached; changed modules executed). No native runtime claim.
Actual new numeric cases: install then82500MM use;80000MM use,install,2500MM delta.
Both complete return/QA with917500MM/9 available ONU/1 installed and1,000,000MM/10EA
conservation. Original3 numeric concurrency/rollback cases and old usage guards pass.

V178.3 APPLIED/IMMUTABLE in fresh AND retained populated QA databases. Next178.4.
Populated upgrade2 more numeric tests PASS2m11s. All previous tenant physical rows
in13 tables retain exact counts/digests; all previous migration checksums unchanged;
only178.3 added. Safe proof:task44/usage-order-verification.json. Prior178.1–.2 intact.

CURRENT default QA marker:warehouse-f7c0d53b912f-98b38fbf54518f766065f31955d52574.
OLD retained marker:warehouse-f7c0d53b912f-abc63ab0045a7096566cf763c8f477fc.
Private env archives under .omo/runtime/environment-archives/<marker>/warehouse-test.env;
never print/commit them. Both volume sets retained, all owned services stopped, new
default restored after upgrade. Host lock/ports/roles/JDK21 unchanged; no reset/kill.

NEXT: serial-only QA source model, then actual HTTP loss/full app restart/outbox
redelivery and remaining combined races. Avoid fake NONE/cable/stock or a second
DEPLOY. An optional bulk source alongside real deployment witnesses may be simpler
than adding a separate operator review command; investigate current nullable/FK and
snapshot/settlement guards first. task44-serial-review-design.md is an unimplemented
alternative, not a requirement. Also inspect QA after reassignment of fully used
material: original actor must not post NEW use after revoke, but historic physical
facts should not require fictitious extra usage. Add real regressions before changes.
Then45 full real browser,46 final regressions/historical175.21 fixture repair,47 docs,
48 CI gates,F1–F4. No subagents authorized. Preserve reports before reruns. Earlier
sections below are history; detailed task44.md has phase notes and remaining work.

## Current dirty phase after pushed c92151f5

V178.3 APPLIED/IMMUTABLE, SHA256 5fe75939a012d8e7e039c05269813c52847dbe9a6bf4b9d023f8eb2d3e0390e6.
Do not edit it even if tests expose a bug. Next free178.4. This phase fixes first
bulk use after deployment and positive delta after deployment. Usage now advances
the shared physical revision, and binds its actual latest usage predecessor. The
initial immutable-use guard queries actual usage, not all deployment activity.
SQL validates historical preceding revisions, new current revision, exact predecessor
and unique posted USAGE/DEPLOYMENT document revision. Fact/snapshot/posting revisions
agree. Web/KMP choose report vs correction using latestUsageId, preserving all
current revision preflight and exact attempted-command replay.

New WarehouseUsageOrderIT has2 actual complete numeric flows: install then82500MM
use;80000MM use, install, then2500MM delta, returning17500MM. Both require exact
917500MM/9ONU/1installed outcome and unchanged historical usage after newer activity.
Targeted server gate51 tests/6 suites PASS3m52s, including both complete ordering
regressions and the original3 numeric cases. Archived r1 reports before reruns.
Client gate PASS:14 web tests/3 files3.87s, full TypeScript, targeted lint; KMP42
tests/14 suites reported green, Gradle27s, module graph passes. Some unaffected
KMP tasks are UP-TO-DATE; changed module tests execute. No native runtime claim.
Populated old-volume upgrade is now running via usage-order-populated-upgrade.sh.
It TEMPORARILY selects the old default environment, fingerprints13 physical tables
for all pre-existing tenants, upgrades only178.3, runs2 real ordering tests, then
compares every old row count/digest and all prior migration checksums. The trap
restores the new default env from its private environment-archives/<new marker>
copy. If the VPS dies mid-run, inspect ONLY WH_MARKER (never whole env), preserve
both volumes and restore that new archive after owned old services are stopped.
Do not call the populated upgrade green until its final fingerprint check passes.

A fresh separately owned QA environment was created, retaining ALL previous volumes.
CURRENT marker: warehouse-f7c0d53b912f-98b38fbf54518f766065f31955d52574.
Old marker: warehouse-f7c0d53b912f-abc63ab0045a7096566cf763c8f477fc.
Old private env is .omo/runtime/environment-archives/<old marker>/warehouse-test.env;
never print or commit it. Default .omo/runtime/warehouse-test.env is the new one.
Ports/roles/host QA lock are unchanged. V178.1–.2 were also clean-applied in the new
DB; earlier applied migrations remain immutable in BOTH environments. Old data and
proofs remain recoverable from the retained old volumes. Only task-owned services
were stopped; unrelated processes untouched. c92151f5 is pushed to feature remote.

## Task44: mixed material QA correction and numeric fixture

Goal ACTIVE. Tasks1–43 done;44 in progress;45–48/F1–F4 open.
Current checkpoint follows pushed ee60908b on work/warehouse-completion, pushed
only to origin/feat/warehouse-workorder. Keep going after this checkpoint.

The real controller+PostgreSQL fixture exposed a product bug: QA required every
planned line in bulk usage even though SERIAL deliberately uses deployment.
InventorySettlementService now binds actual receipt-backed deployment witnesses
alongside measured usage. QA records verification only; no second debit, asset or
fake bulk line. Witness equality and complete plan-line coverage are checked by SQL;
shared assignment locks hold a live reviewed installation through the QA commit.

V178.1 and178.2 are APPLIED/IMMUTABLE in owned warehouse_test.
178.1 SHA256 c4b6b169403296df9943ee7f981ef21f215f3e43712f92a02524f57088c832e3
178.2 SHA256 ff2b6f79215053a7dd6f26ca54796887268e2b5926e5300e3027d270b36f893b
V178 unchanged f6b8780d898577c16f2b63298e9d3fd43244d6e32a02a4c921ae04c7a1c9997d
The exact V178 hash is authoritative in docs/warehouse-migrations.md; do not edit it.
Next free migration178.3. SQL corrections must be forward only.

WarehouseConcurrentLifecycleIT has3 cases: full real 1,000,000MM/10ONU receipt,
100,000MM/1ONU issue+ACK,82,500MM consumption, actual customer installation+LOAN
handover,17,500MM inspected return, proof/signature/completion+independent QA;
precommit failure after actual usage posting with exact retry; and missing ONU
installation still blocks QA. Final totals917500 available MM,82500 consumed MM,
0 technician MM,9 available EA,1 active assignment/ONU,10 original assets,1 handover.
Global balance conservation stays1,000,000MM/10EA. Concurrent same-key use and return
inspection replay the exact original outcome; QA leaves all physical counts intact.
A real PostgreSQL lock observation proves assignment share-lock blocking. A test-only
public settlement decorator omits/substitutes first-approval witnesses; actual SQL
rejects the recomputed frozen body and rollback leaves0 snapshots/PENDING QA.
The original one-snapshot-per-WO guard remains. Internal corrupted owner data raises
an SQL exception through MockMVC; it is not claimed as an ordinary HTTP409 response.

Verification history (raw logs/XML private):
- r3:3 PASS after178.1, before final locking/tamper assertions.
- r4:181 tests,5 failures,0 skipped. Full report archived before reruns.
  1 cloned-snapshot test hit the existing duplicate-snapshot guard; replaced with
  actual first-approval corruption through the public owner port.
  3 isolation tests expected leaked exceptions although MaterialWorkflowErrors
  intentionally returns409 SOURCE_NOT_VERIFIED; now also verify exact-key retry.
  1 historical upgrade fixture starts CURRENT application at175.21 and fails at a
  newer transfer_receiver_id column. This remains OPEN for task46; no tests skipped.
- r5:2/3 PASS; first-approval guard correctly rejected corruption but the test
  incorrectly expected409 from WorkOrderController. Corrected to the actual SQL
  exception plus rollback assertions.
- r6: compile failure from referencing the runtime-only PostgreSQL driver class;
  corrected to java.sql.SQLException, retaining the precise guard message.
- r7:3 numeric tests PASS on final source,2m21s.
- r8:28 isolation tests PASS on final source,2m54s. Final focused total31, no
  failures/errors/skips. Proof task44/mixed-material-verification.json.

NEXT after this coherent checkpoint:
1. Fix install-before-cable and usage delta ordering. MaterialPhysicalTotalsStore
   includes DEPLOYMENT revisions, but reportUse assumes any nonzero revision means
   previous bulk usage. Query actual latest usage separately; first/delta revisions
   must advance the shared physical revision. MaterialUsagePreparation fact revision
   and PostingUsage/snapshot must agree. The current SQL owner function is privately
   captured at .omo/runtime/material-usage-current.sql; initial guard requires1 and
   delta guard requires predecessor+1. Preserve historical read/replay; add forward
   guarded SQL and validate no shared usage/deployment revision collision. Web and
   KMP initial/correction selection must use latestUsageId, not useRevision>0.
2. Serial-only jobs currently cannot create a required-material usage review; do not
   invent NONE/cable/stock. Prefer explicit immutable review of actual already-posted
   serialized deployments, positive real source witnesses and no new movement.
   Read/replay must keep current actor/location authority and preserve old hashes.
   Ordinary empty required-material usage still rejects. Test real serial-only QA,
   install-first mixed use and delta after install, plus missing/foreign/stale sources.
   Review of inherited/reassigned serial sources must be explicit, not an actor-check
   bypass. Actual deployment assertion supports legitimate ended episodes; a new review
   requires current installation, while historical immutable reads must remain valid.
3. Add WarehouseRecoveryIT: full numeric flow, real socket response loss after
   commit, whole app close/fresh context, exact original inspection replay, unchanged
   stock. @DirtiesContext(AFTER_METHOD)+ContextClosedEvent proves shutdown; existing
   WarehousePolicyITRestart has owned child SIGKILL alternative. Never kill unrelated
   processes. Use actual outbox claim/reader/delivery, drop ACK, restart/redeliver,
   fence old lease and verify one inbox/fulfillment observation. Only test lease
   scheduling metadata may be expired; no physical seed or sleep-based races.
4. Add remaining combined concurrent cuts/jobs, revocation/tenant isolation,
   stale QA/count/time-aware reuse proof. Existing task20/26/28 tests are supporting
   evidence, not a substitute for the full numeric acceptance fixture.
5. Task45 real empty-tenant browser journeys;46 full final regressions and historical
   fixture repair;47 source-matched docs/preflight;48 required CI gates;F1–F4 audits.

QA uses the shared host fd8 lock, JDK21,2 workers,Kotlin in-process,1536MiB test heap,
context cache1. Keep full reports before focused reruns. Never commit private env,
raw logs/XML/uploads/authenticated traces. Feature pushes do not deploy. No subagents
are authorized. Current functions sessions/runner status must be checked before QA.
