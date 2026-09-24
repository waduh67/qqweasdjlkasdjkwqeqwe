# Warehouse Workorder Asset Provenance Checkpoint

## Task28 approval event and asset coverage checkpoint — regression running

Published base0daab7e3 preserves applied139 (SHAa9a1f5567678d9b4841bad38b3f2fd79f4256efeeb65b8563fe442923de2e489).
The disposition-costed run executed5 tests/2 suites,2 failures,0 errors/skips,
2m12s. Costed draft and independent approval request passed; requester self-decide
returned403 as expected. Both actual checker decisions reached posting but the
transaction rolled back before commit: WarehouseApprovalStore.event selected only
older event kinds and threw NoSuchElementException for DISPOSED. Added that exact
event to its lookup; no SQL changes and no fallback success.

Added4 actual serialized-device cases: LOAN LOSS/SCRAP retain closed assignment
history and survive balance rebuild; SALE LOSS/SCRAP must reject customer-title
writeoff without creating a request. Shared serial receipt fixture now also uses
the optional actual-cost hook, default unknown for all prior fixture users.

Current .omo/runtime/disposition-guards.sh / .log runs WarehouseDisposition*IT,
WarehouseReturnSettlementIT, WarehouseReturnITIntegrity, WarehouseApprovalITDelegation,
WarehouseApprovalITExpiry and ModularityTests. Archive task28/disposition-guards/xml;
database log disposition-guards-database.log. Results pending; do not claim a
committed loss/scrap effect until this run proves it.13 new guard/asset cases are
included with the2 full residual settlement cases and affected regressions.

Next complete missing compensation as a NEW linked approved movement restoring
only quarantine, with a source-bound return transition and open/closed obligation
checks. Do not duplicate ADJUSTMENT owner (transfer discrepancy owns that kind).
Active/reused/consumed downstream state must reject implicit rollback. Vendor and
other outstanding loss paths remain to assess before task28 can be marked done.

## Task28 source-cost checkpoint — 139 applied, posting validation pending

139 is now APPLIED and IMMUTABLE. SHA256:
a9a1f5567678d9b4841bad38b3f2fd79f4256efeeb65b8563fe442923de2e489.
The first effect run failed42601 from ERaRCODE at SQL187 (4 tests,1 failure,
1m7s); Flyway rolled back21:46:37.717 JKT. The next run failed the guarded
return terminal-state anchor:117 had inserted DRAFT handling (4 tests,1 failure);
Flyway rolled back21:48:04.973 JKT. Both fixes preceded139's FIRST successful
application at21:49:43.056 JKT. Never edit139 or earlier applied migrations again.

The disposition-ledger run executed5 tests/2 suites,2 failures,0 errors/skips,
1m53s. Both real LOSS and SCRAP request201/replay paths passed. Approval request
correctly returned COST_BASIS_REQUIRED because the shared material fixture had
no receipt cost. No physical disposition has passed yet;3 modularity tests passed.

Added a shared fixture cost hook (default unknown preserves prior behavior),
WarehouseDispositionFixture with declared actual source receipt cost, and9 new
behavioral guards: unknown cost, bad quantities, changed inspection, rejection
and fresh request, competing approvals, requester delegation, revoked scope,
scoped paging and posted MM rewrite/rebuild. Guards are authored, not yet run.

Current validation .omo/runtime/disposition-costed.sh / .log selects the2 full
LOSS/SCRAP settlement cases plus3 modularity checks. Archive task28/disposition-costed/xml.
After it passes run WarehouseDispositionGuardsIT and affected return/approval
regressions; add forward migration140 if runtime invariants need correction.
Compensation, returned asset loss/scrap and remaining task28 acceptance still open.

## Task28 approved physical effect checkpoint — validation running

Supersedes the older draft-only status below. Published base7944bb50 includes
138; disposition-request actually ran4 tests/2 suites,1 failure,0 errors/skips
in1m46s. POST disposition201 and exact replay passed; the failure was missing
approval source owner at test line43. Flyway138 applied21:34:36.248 JKT and is
IMMUTABLE:34a2b183f2a4f1395981b5efdf5e14d3033ca9f7121b767c9d3f2ab52c74ea3b.

This checkpoint adds independent LOSS/SCRAP owners, admission revalidation,
approval-kind wiring, one paired physical posting and a linked nonphysical
warehouse.return.dispose operation.139 was reserved before creation. Its new
immutable effect captures the live source, binds both operations/approval/legs,
validates EA and MM current positions against applied ledger, extends existing
return histories/lifecycle forward, and counts approved disposal as settlement
of the original returned residual without counting another issued disposition.

The initial verification is .omo/runtime/disposition-effect.sh / .log; owned
private archive task28/disposition-effect/xml and disposition-effect-database.log.
It selects WarehouseDispositionIT + ModularityTests. No result or successful139
application is claimed yet; check logs before changing this SQL. Earlier138 and
older migrations must never change. Task28 stays OPEN; loss/compensation and
adversarial/scoping/concurrency/rebuild checks remain. Task26 remains COMPLETE
with its published53-test22-suite portable evidence at7c6eb6e5.

## Task28 draft request implementation checkpoint — validation pending

Task26 completion checkpoint7c6eb6e5 is published with53 green tests/22 suites and
sanitized portable evidence. Overall goal remains active. Task28 is NOT complete.

The real initial disposition-red baseline executed1 test/1 failure/0 errors/skips,
1m32s. All actual receipt/use/residual return/inspection/policy setup passed; the
new POST /api/v1/warehouse/dispositions returned404 at test line37. Archive:
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-red/xml.

Authored InventoryDispositionApi, typed LOSS/SCRAP input/view, controller/service,
WarehouseDispositionStore/Record. Initial request accepts exact ISP-owned RETURN
quantity in RECEIVED_IN_INSPECTION; SCRAP additionally requires DAMAGED. It locks
WO through the public inventory-owned port before warehouse topology/documents
and physical source, captures actual receipt/lot cost, checks custody/return and
approval-request permissions, and stores immutable actor/key/source snapshots.
Read/list expose no cost or customer fields; current location/area/site scope is
applied before pagination. No physical posting or approval owner exists yet.

138 was reserved BEFORE creation in docs/warehouse-migrations.md. It captures the
real return operation, current balances/asset/segment, WO revision and original
cost, and seals a DRAFT0 header/line with NO movements/operations. Draft-only
restriction must be extended forward when implementing real independent effects.

First disposition-draft compile succeeded;4 tests/2 suites/1 failure/0 errors/skips,
59s:3 modularity passed,1 context startup failed. Flyway138 failed42601 near CASE
inside the large IF at SQL line29 (position5663), and logged at21:32:45.351 JKT
"Changes successfully rolled back". No138 apply succeeded. Added parentheses to
that CASE before successful application; old137 and earlier files unchanged.
Minor get/list state handling was also tightened before the corrected build.

Corrected run is .omo/runtime/disposition-request.sh / .log, archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-request/xml,
database log disposition-request-database.log. It selects WarehouseDispositionIT
and ModularityTests. Confirm migration application before treating138 immutable.
The behavioral test should next reach approval/request; that owner and posting
are deliberately unfinished, so do not call4 tests green without actual results.

Next: implement independent LOSS/SCRAP owner + exact effect, nonphysical linked
return state transition, and settled-return calculation for authorized disposal.
Then add loss/vendor custody, compensation, and adversarial/scoping/replay cases.
Detailed design constraints follow below; no resetting DB or changing applied SQL.

## Task26 COMPLETE —53 combined tests passed

return-combined-check passed53 tests/22 suites/0 failures/errors/skips,
6m47s, against product source c050efeb. All return, recovered-loan reuse, original
customer RMA/reacquisition, source/title integrity, independent approval, scope
revocation, vendor repair/replacement, inspection/rebuild and pagination cases
passed together. Separate ordinary receipt inspection regression passed10 tests
in4m44s. Product source has not changed since that successful compilation.

Sanitized portable evidence is committed at
.omo/evidence/warehouse-workorder-asset-provenance/task26/verification.json:
actual suite/case names, nonzero counts, XML digests, main tree identity and131–137
SQL digests. Raw XML/logs remain private because they may contain HTTP tokens.
All applied migrations remain immutable; no QA volumes were reset. Task26's plan
row is now checked. Original-device CUSTOMER RMA is supported; distinct customer
replacement remains quarantined and cannot borrow the old device's permit.

Continue task28 (not complete): real17.5m scrap scenario and design notes are in
task-28.md / WarehouseDispositionIT. Its queued disposition-red run starts after
combined QA cleanup. Record its actual failure, then implement the document,
independent approval, exact posting, return transition and obligation settlement.
No task28 migration is reserved yet. Tasks28,30–48,F1–F4 remain open; the overall
goal is still active, not achieved. Commit and push each coherent checkpoint.

## Task28 behavioral checkpoint; task26 combined checks still running

Task26 compiled product source c050efeb is under return-combined-check. RMA
acceptance/deployment/guards/handover/reacquisition have passed so far; the full
suite is still pending, not a completed gate. No new SQL has been reserved.

Task28 adds WarehouseDispositionIT and task-28.md. The exact17.5m damaged residual
scenario is authored; .omo/runtime/disposition-red.sh is queued under the same
QA lock after task26. It selects only WarehouseDispositionIT, archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-red/xml.
No disposition endpoint exists yet. Expected initial failure is POST404 after
real return+inspection setup. Do not call this task28 implementation or success.
Continue with its owner/approval/effect/settlement implementation after recording
that actual baseline; retain all task26 and137 immutability checks.

## Combined return regression restarted after pagination compile correction

Published f35e8621 contains the inspected replacement flow and scoped-list change.
First combined run stopped at main compilation (13s, no executed tests): Jackson3
JsonNode.map resolved to its member instead of Kotlin collection map. Corrected
with asSequence().map(...).toList(). No SQL changed;137 remains immutable. The
failed return-combined-regression/xml may contain copied stale receipt results;
DO NOT use them as combined evidence.

Corrected run: .omo/runtime/return-combined-check.sh / .log,
archive task26/return-combined-check/xml and return-combined-check-database.log.
Main compilation has passed; test compilation/execution remains pending.

Task28 preparation only: WarehouseDispositionIT now describes a real17500MM
damaged residual -> independent SCRAP approval -> exact disposed sink and WO
settlement. No disposition endpoint exists yet, so that test has not passed.
It is not selected by the combined task26 run. Do not infer task28 complete.

## Supplier inspection verified; scoped pagination under combined regression

Published parent checkpoint11c3b518.
return-replacement-inspection-green passed10 tests/4 suites/0 failures/errors/skips,
4m44s: replacement LOAN/SALE inspection and putaway/rebuild2, ordinary receipt
11-line disposition1, receipt inspection guards4, modularity3. Evidence archive:
task26/return-replacement-inspection-green/xml. Real private MinIO attachment used.
Receipt inspection derives expected owner from the immutable vendor replacement
request; ISP putaway remains mandatory. CUSTOMER inspection succeeds but stays
QUARANTINE, ISP inspection+putaway becomes AVAILABLE/SERVICEABLE. Both preserve
origin/old vendor custody and rebuild without extra movement.

Additional review found replacement list filtering after LIMIT. It now reuses
WarehouseQuerySql visible_locations (location/area/site ancestry) before pagination,
returning only public link views. One new guard puts a hidden draft before a
visible draft at size1. This pagination change is NOT yet verified.

Combined run queued/starting: .omo/runtime/return-combined-regression.sh and .log,
archive task26/return-combined-regression/xml, database log return-combined-database.log.
It selects WarehouseReturn*, WarehouseSupplierRepair*, WarehouseCustomerRma*,
WarehouseSupplierReplacement* and ModularityTests under the host QA lock. Confirm
nonzero full counts and zero failures/skips before marking26 complete.137 remains
immutable; this checkpoint contains no SQL edits. Full new-device CUSTOMER RMA is
not inferred from same-asset RMA permits. Task28 explicitly handles old-device
loss/scrap; receipt of a replacement does not dispose the old physical device.

##137 verified supplier replacement policy and current position

Published checkpoint a0d5f17b contains136. It applied at21:05:46.921 JKT;
20 tests/5 suites/7 failures/0 errors/skips,4m35s. All7 failures were filled-cost
requests rejected before admission: JSON operator precedence interpreted `cost`
as JSON. Null-cost requests and existing receipt/approval/modularity passed.
136 is IMMUTABLE, SHA2569c1eb477eca82db04b769074e0f60d400637ea724953593515478594b0b5cb50.

Forward137 fixes only that cost expression and connects replacement current
asset/balance/movement dimensions to the existing APPLIED-ledger validator.
return-replacement-policy-green passed10 tests/2 suites/0 failures/errors/skips,
2m53s: supplier guards8 plus LOAN/SALE admission2. Actual approval+replay, unknown
cost, stale source/direct and approval, rejection/fresh request, concurrent drafts,
revoked-scope replay and raw CUSTOMER->ISP asset/balance rewrite all pass.
137 is now APPLIED AND IMMUTABLE, SHA256847e1c48684fba0f9d59bb987d3b682875c2e2023e78e10d45b46caf2a1502c3.
Evidence archive: task26/return-replacement-policy-green/xml; private matching
runtime .log and return-replacement-policy-database.log. No DB reset.

Next validation queued under the same host lock: return-replacement-inspection-green,
new2 LOAN/SALE inspection/putaway/rebuild cases, ordinary receipt disposition and
inspection guards, modularity. Its two source files remain a separate uncommitted
change: receipt inspection derives CUSTOMER title only from verified replacement;
normal putaway still requires ISP. Do not claim these tests have passed yet.
Task26 remains open pending this and combined return regressions. Task28 remains
open for loss/scrap/compensation, including explicit old-vendor disposition; do not
silently close old repair when a distinct replacement arrives.

##136 declared cost/rejection and8 supplier guards — first validation running

135 is APPLIED AND IMMUTABLE, SHA256: `ab998db9effece220beed9b979164b54aee915531ec81a3c9bc4f722ba1b2abd`.
The corrected run passed12 tests/4 suites/0 failures/errors/skips,1m45s:
LOAN/SALE vendor replacement2, ordinary receipt1, approval guards6, modularity3.
New replacement asset is distinct, real same-vendor RECEIPT, owner ISP/CUSTOMER
as captured, QUARANTINE; old asset remains vendor custody. Archive
return-replacement-posting-green/xml. Published25ebf655 includes the pre-apply
SQL delimiter correction; failed135 attempt fully rolled back, then correct apply.

136 reserved BEFORE creation and now authored. Optional SupplierReplacementCost
(totalMinor,currency) feeds normal receipt cost input. Null cost is omitted from
new input serialization so old134 canonical replay is preserved; no zero invented.
136 captures exact vendor-declared cost/denominator1 and freezes document-line
cost against intake. It supports DRAFT1/REWORK_REQUIRED only with matching rejected
approval; generic rework returns controlled409 directing a new immutable request.

New WarehouseSupplierReplacementFixture/GuardsIT adds8 cases: actual customer
replacement approval+cost+replay; unknown cost policy rejection; competing drafts
one new asset; direct stale source; durable approval stale+replay; rejection then
fresh request; current scope on receive replay; and SQL unposted CUSTOMER->ISP
asset/balance rewrite. That last case deliberately tests possible missing current
physical-ledger binding for a replacement with no installation history. If it
exposes a gap, use existing warehouse_assert_recovered_position (120) and final
asset/balance routing in a NEW forward version after136 applies; never weaken it.

Current `.omo/runtime/return-replacement-guards-green.sh` / matching log and
return-replacement-guards-database.log, archive return-replacement-guards-green/xml,
session96350 runs the8 new cases + prior12. Read log for actual compile and136
apply status; after successful apply136 is immutable. No137 declared/created.
Then finish further tenant/source/immutability checks as justified, replacement
inspection/reset and CUSTOMER original-customer handover/installation, old-vendor
custody disposition, packaged HTTP and remaining plan. Task26 still OPEN.

##135 first SQL attempt rolled back; delimiter corrected before any successful apply

return-replacement-admission-green compiled but failed Spring/Flyway startup:
SQLSTATE42601 syntax error near patch$, at135 line126. Log20:57:27.517 JKT explicitly
says changes successfully rolled back (also repeated in later test contexts).
The adjacent dollar tags at END $function$$patch$ accidentally contain the outer
DO $$ terminator. Inserted a newline between the tags.135 had NOT applied, so this
is a correction to an unapplied failed version;134 and all older bytes unchanged.

Current `.omo/runtime/return-replacement-posting-green.sh` / matching log, DB log
return-replacement-posting-database.log, archive return-replacement-posting-green/xml,
session74078 re-runs the12 selected cases. Check log for actual135 success before
any further SQL edits. Last70536553 published; correction follows in new commit.

Further functional gap found during source audit: replacement input currently has
no optional declared cost, while configured RECEIPT policy correctly rejects
unknown cost (COST_BASIS_REQUIRED). Add explicit optional public replacement cost
only from actual vendor evidence, never synthesize zero. Omit null new input field
from serialization to preserve canonical replay of existing134 requests; update
capture validator via a later declared version after135 applies. Then test the
actual replacement approval path, not merely ordinary receipt approval regression.
Rejection/new immutable request lifecycle and receipt source/physical graph guards
also remain pending, followed by inspection and original-customer handover.

##135 replacement admission/effect — first database validation pending

134 is APPLIED AND IMMUTABLE. Corrected initial creation (internal
ReceiptDraftContext/replacementDraft, normal HTTP input unchanged) now passes
LOAN/SALE request201 and replay. That run finished6 tests/3 suites/2 failures/
0 errors/skips,2m40s:3 modularity and normal WarehouseReceiptITReceive pass;
both supplier cases reach receive and are blocked by134's intentional DRAFT-only
REPLACEMENT_REQUEST_RECEIPT_BINDING. Archive return-replacement-draft-green/xml.

135 was reserved before creation. Source now adds one immutable replacement receipt
mapping per repair/receipt/asset/operation, capturing current old-asset/vendor/
return/WO source and checking actual RECEIPT/APPLIED legs/owner/outbox at commit.
Direct receipt and approval receipt use the same typed binding; owner defaults ISP
only for ordinary receipts. The new asset has real vendor RECEIPT provenance;
old physical asset/assignment are preserved. Receipt operation ID is generated
before admission to bind the deferred mapping to its actual operation. Approval
source includes replacement snapshot. SupplierReplacementAdmission locks original
WO before warehouse locks; current source is checked with receipt/return/asset
held before any admission or final approval decision. Authority/view replay still
checks current WO/locations.

First return-replacement-receipt-green attempt failed production compilation in14s
because AssetHandoverWorkOrderPort import pointed at port/outbound instead of the
public inventory package. Import fixed; no tests or135 application occurred in
that attempt (copied XML is stale). Corrected current run:
`.omo/runtime/return-replacement-admission-green.sh`, matching log/DB log,
archive task26/return-replacement-admission-green/xml, session28075. It compiled
both source sets and is running2 supplier,3 modularity,1 normal receipt and6
ordinary approval tests. Read log for135 apply/result; after successful apply
135 bytes are immutable. No136 reserved or created.

Still needed: real replacement approval path, competing distinct drafts/receives,
source mutation/scope/tenant/replay/SQL graph tests, controlled rejection/new-request
flow (135 presently supports only DRAFT0 and received receipt; generic replacement
rework is not implemented), replacement inspection/reset, CUSTOMER original-
customer custody/reinstallation, and explicit old-vendor disposition/history.
Then packaged HTTP proof and the rest of the plan. Task26 remains OPEN.

##134 supplier replacement request/source draft — first validation running

The supplier baseline compiled and ran2 tests/1 suite,2 failures/0 errors/skips,
1m33s. Both LOAN and SALE reached missing POST replacement-receipts404 after real
receipt/install/handover/removal/intake/inspection/vendor dispatch. Archive
`task26/return-replacement-red/xml`; no fake physical seed or invented provenance.

Source now implements InventorySupplierReplacementApi POST/GET
returns/{id}/replacement-receipts, strict request, original WO/return/asset locks,
current locations, tenant/actor/hash replay, a real nested RECEIPT draft with an
internal key based on new request UUID, and original customer/WO/owner binding.
New134 was reserved BEFORE creation and captures/seals actual vendor custody,
closed original assignment, repair case, prior return and normal receipt intake.
134 deliberately allows only DRAFT0 with no physical receipt effect; admission,
one-replacement consumption, subsequent inspection and handover remain pending.
CUSTOMER draft line retains CUSTOMER; no change to old asset or old assignment.

Current `.omo/runtime/return-replacement-request-green.sh`, matching log and
`task26/return-replacement-request-green/xml` (session25146), compiles/runs both
supplier cases plus3 modularity cases. Read log for compile/apply status. After
any successful134 apply its bytes are immutable. No135 declared or created.
Expected next missing behavior is physical receipt admission; first fix request
capture/binding if exposed. Last published e5d2cef1 is the fully verified direct
quarantine implementation:13/3/0/0/0,3m41s (6 title,6 approval,1 full history/reset/
replay/rebuild);133 and all earlier migrations immutable.

Next implementation must lock replacement source before topology/receipt locks
for BOTH normal receive and approval receive, validate current source before
approval final decision, choose admission owner only from captured replacement
binding, and seal one receipt/new identity per repair with an immutable effect.
Include replacement binding in approval source snapshot. Normal receipt payloads
and historical snapshot shapes remain unchanged. CUSTOMER inspection/return-to-
original-customer and old vendor custody disposition must remain explicit.
Then finish task26 packaged proof and remaining plan; task26 is still OPEN.

## Direct quarantine reacquisition verified —13 tests green

return-title-final-green passed13 tests/3 suites/0 failures/errors/skips,3m41s:
6 return title guards (changed source, competing approvals, SQL forgery/append-only,
rejection/new request, vendor repair after reacquisition, current scope/replay),
6 existing approval guards and the full direct quarantine -> ISP -> reset release
case. The latter also proves request/decision replay, changed payload conflict,
historical CUSTOMER/CUSTOMER/CUSTOMER/ISP/ISP views and projection rebuild without
new movements. Archive `task26/return-title-final-green/xml`.

133 is APPLIED AND IMMUTABLE, SHA256
`c874e69adf2098a8caf956ebf5918f6154f1b2188979601e45c40d40c0700410`.
132 is immutable (`e38b4f88081afc6c625ec60cb5e5da6fdc4e333f7bf2703e04c4541a19a57f47`).
Previous shared run:15 tests/6 suites/2 failures/0 errors/skips,4m4s. Its2 failures
were test expectations of200 for STALE; existing durable approval contract is409.
Those expectations now pass. Shared supplier repair2, return integrity3,
installed RMA reacquisition1 and modularity3 all passed that run.

Rejected immutable RETURN_TITLE requests now return controlled409 on generic
approval rework; callers create a new request with current evidence. Documented
API and updated test include this behavior. No historical SQL was edited.

Next: actual vendor replacement. New WarehouseSupplierReplacementIT specifies
LOAN/SALE genuine recovered repair -> replacement receipt request -> receive new
physical identity with normal RECEIPT provenance, same vendor/owner and quarantine.
Endpoint POST/GET returns/{id}/replacement-receipts is NOT IMPLEMENTED yet.
The baseline `.omo/runtime/return-replacement-red.sh` / matching log (session45699)
is queued/running after the final green run under the fixed host QA lock.
Read the log; no baseline outcome or new migration134 is claimed. It will use the
retained owned test DB/volumes and expects the new route to expose missing support.

Implement replacement through a real vendor RECEIPT plus immutable repair linkage;
CUSTOMER replacement must not become ISP available stock or acquire a fabricated
old issue/source ID. Preserve original asset/history/vendor custody explicitly.
Then inspection/original-customer return or ISP issue, packaged HTTP proof and
remaining plan. Task26 stays OPEN;28/30–48/F1–F4 remain. No134 declared/created.
Canonical remote is `git@github.com:waduh67/qqweasdjlkasdjkwqeqwe.git`; push only
`git push origin HEAD:refs/heads/feat/warehouse-workorder`.

##132 immutable;133 physical leg revision correction under validation

Checkpoint includes RETURN_TITLE independent approval owner, policy exclusions,
canonical approval source, one CUSTOMER->ISP quarantine posting and immutable
return-title effect/return revision. Current return/repair reads now use the
latest proven owner; original customer assignment remains unchanged.

132 applied and immutable, SHA256 `e38b4f88081afc6c625ec60cb5e5da6fdc4e333f7bf2703e04c4541a19a57f47`.
First run compiled production/tests, passed3 ModularityTests and failed the one
full reacquisition case at approval commit: RETURN_TITLE_EXACT_QUARANTINE_POSTING.
4 tests/2 suites/1 failure/0 errors/skips,1m49s. Evidence
`task26/return-title-effect-green/xml`; private DB log captures13:32:39.697 UTC.
The comparison incorrectly equated revisions of distinct CUSTOMER/ISP balances.
133 is a forward correction excluding only that per-dimension revision.

Current `.omo/runtime/return-title-guards-green.sh` / matching log (session91757)
runs direct reacquisition,5 new stale/race/SQL-integrity/rejection/repair cases,
ModularityTests, return integrity, supplier repair and installed RMA reacquisition.
Read current log before editing133: after any successful apply it is immutable.
No result claimed yet. New5 cases were not previously compiled. Owned QA cleanup
retains volumes; fixed host QA lock remains required. Prior131 request/replay201
and3 modularity cases passed; its only failure was the then-missing approval owner.

Next resolve this run, add current-scope/evidence/exact-history checks as needed,
finish supplier replacement with actual new-asset/vendor-receipt provenance,
packaged proof, then remaining plan. Task26 remains OPEN. Push only
`git push origin HEAD:refs/heads/feat/warehouse-workorder`; origin must remain
`git@github.com:waduh67/qqweasdjlkasdjkwqeqwe.git`.

##130 ten green;131 direct quarantine request is in its first validation run

130 is APPLIED AND IMMUTABLE, SHA256 `77379d190d8291dd7303f06fb4c19693e863b28a9653ba0faa6b8a25f079311b`.
The renewal run passed10 tests in3 suites,0 failures/errors/skips,2m47s:
6 RMA guards including scope restoration/new permit and competing distinct permits,
3 signed acceptance/key/history cases and full independently approved RMA ->
removal/inspection/new-customer normal reissue. Archive
`task26/rma-authorization-renewal-green/xml`.129 earlier passed4/2/0/0/0,2m30s.

Source now includes InventoryReturnReacquisitionApi POST returns/{id}/reacquisition,
strict input, current WO/return/asset/scope locks, canonical replay and a separate
RETURN_TITLE draft request.131 captures real CUSTOMER quarantine position,
original closed assignment/accepted handover and signature evidence, then seals
source/request/document binding. No stock/title posting is enabled in131. The
full direct-return test previously proved404 after a genuine recovered SALE.

Current `.omo/runtime/return-title-request-green.sh`, matching log and archive
`task26/return-title-request-green/xml`, runs that full case plus ModularityTests.
Compilation and first131 apply/status are pending. It should next expose missing
approval owner/policy dispatch; those and the approved posting/return revision
transition are NOT IMPLEMENTED. Read current logs before editing131; after any
successful apply it is immutable. No132 declared or created.

Needed next: implement RETURN_TITLE approval owner and policy exclusions from
requester/receiver/original handover/removal actors; approved CUSTOMER->ISP posting
must remain in QUARANTINE at the same custody/condition, append linked return
revision/operation without editing closed assignment, and require reset inspection
before availability. Adapt return inspection to view.legalOwner and strict return
history validator through explicit approved-title step. Keep one physical posting
and preserve current source/approval/cutover/replay fences. Then vendor replacement,
packaged proof and remaining plan.26 stays OPEN.

##128/129 title continuity; focused runs and missing direct-return route

rma-reacquisition-green completed17 tests/5 suites/2 failures/0 errors/skips,4m29s.
The4 previous shared regressions are repaired:2 acceptance races,2 allowed draft
DELETEs (all5 scope variants passed). Ordinary title approval, all3 RMA acceptance
and4/5 RMA guards passed, including simultaneous RMA installs. Failures:
1. RMA independent approval hits historical ASSET_REMOVAL_HISTORY_POSITION_BINDING.
  129 now uses the existing continued recovery/APPLIED ledger owner proof while
  keeping physical identity and original episode title immutable. See task-26.md.
2. Revoked pending install returns409 STALE_AUTHORITY, matching the existing WO
  epoch contract; the probe incorrectly expected404. Corrected to assert exact
  stale code and zero writes, then require a fresh permit after restoration.
  A unique RMA handover execution constraint may block that legitimate renewal;
  prove with the queued focused test before any source/schema correction.

127/128 are APPLIED AND IMMUTABLE.129 may already apply in the running direct-
quarantine red probe; check current status before edits. Migration bytes:
- V175_127__warehouse_draft_delete_return_row.sql: `1210635a11525370c6e35efcedc919239225a97b776d147907ee632f496eb024`
- V175_128__warehouse_rma_reacquisition_title.sql: `5c57b1faec84685ac29ecd7b09fb070f7645bcfe227aed3c178fc41fbf5c2da8`
- V175_129__warehouse_recovered_title_continuity.sql: `9fc3857eb00db6e7dbd4a237fa2e86e9d9b6b13387beae9999f6969ccb01965d`

Active/queued wrappers under the host QA lock (each same-name log/archive):
- return-reacquisition-red: real SALE recovery/inspection, new direct quarantine
  reacquisition route missing, NOT YET VERIFIED. Request test requires independent
  title approval, preserved old assignment, Q until inspection and replay no effects.
- rma-title-continuity-green: full RMA approval/removal/reissue plus3 physical
  ledger integrity cases (including unposted CUSTOMER->ISP rewrite). Pending.
- rma-authorization-renewal-red: one corrected scope/renewal/replay case. Pending.
Do not infer successful outcomes from stale copied XML on compile failures.

No130 declared. Direct quarantine API and supplier replacement not implemented.
26 and remaining28/30–48/F1–F4 stay open. Current work branch is
work/warehouse-completion; ordinary push to origin feat/warehouse-workorder only.

##126 regression recorded;127 draft deletion correction and RMA reacquisition probe

The completed rma-acceptance-green run has166 tests/5 suites/4 failures/0 errors/
skips,8m28s. RMA3, forward-fix3, provenance31 all passed; ownership72 had2 races
409 vs200, final-state57 had2 allowed draft deletions leave count1. Raw XML is in
task26/rma-acceptance-green/xml. New custody preview routes before locks and runs
DB validation after owner locks; focused simultaneous duplicate acceptance has
now PASSED in rma-reacquisition-red, whose other new RMA reacquisition test is
still running. Read actual result before title corrections; no128 declared.

127 is declared/created, NOT YET APPLIED by current runner (processResources ran
before creation). It preserves warehouse_transfer_binding_guard scope/bound-
transfer rejection but returns OLD for permitted DELETE. Previously RETURN NEW
silently suppressed every non-transfer draft deletion. Do not edit applied126.

The exact retained-pre125 handover now validates as warehouse_app. Its stored
origin still lacks both extension fields, same hash before/after validation;
archive task26/rma-origin-upgrade-green/probe.log.175.126|t confirmed. No claim of
a pre-migration hash (not captured by the original red probe).

Two extra RMA guard tests are UNVERIFIED: unacknowledged custody, wrong customer,
revoked scope before install/replay, and simultaneous duplicate installs. New
RMA reacquisition test is real signed title0 -> independent approval -> removal/
inspection -> normal reissue to a new customer. Checker fixture corrected to the
actual CUSTOMER_INSTALLED location (current compiled red still has earlier field
location, but source-title request precedes any approval decision). Next run should
include127, all4 failed shared cases, RMA3 acceptance and5 custody/install guards.
26 and whole plan remain OPEN; vendor replacement/direct-quarantine reacquisition
and packaged evidence still need completion. See task-26.md newest entries.

##126 applied — RMA acceptance3 and ordinary regression still running

V175.126 has applied in the running integration test and is IMMUTABLE. SHA256
`cd894209c09945e323c4cc80f397f6857bde25d7f066aad5071e28e57bda0e30`. Main/test compilation passed. All3
WarehouseCustomerRmaAcceptanceIT cases passed: signed non-posting acceptance with
CUSTOMER titleRevision0 (including public ownership read), cross-purpose mint-key
conflict409 and history decoding both normal/RMA sources.

Shared `.omo/runtime/rma-acceptance-green.sh` is still running, same-name log and
archive `task26/rma-acceptance-green/xml`. CustomerAssetOwnershipIT simultaneous
acceptance race has FAILED; most other observed cases passed. Read final XML and
response details before fixing. Source audit: new custodyView invokes a multi-read
DB validator BEFORE work-order/assignment serialization; previous preview did not.
Do not remove validation; investigate moving validation after the normal locks.
The read-only retained-pre125 origin validation is queued at script end and will
not run if Gradle fails, so run it separately after the lock if needed. The prior
red probe and fixed exact SQL are under .omo/runtime/rma-origin-upgrade-* and
rma-origin-upgrade-probe.sql.126 changes comparison only, no stored seal writes.

No127 declared. Next real probe: independently approved reacquisition after signed
RMA (source title revision0), then removal/inspection/reissue. Source audit finds
old title-request revision formula and current RMA-owner assumptions may reject it;
prove before forward correction. Also remaining actual vendor replacement and
packaged proof.26 and whole remaining plan stay OPEN. Save/push ordinary checkpoints
on work/warehouse-completion -> origin feat/warehouse-workorder, no main deployment.

##125 shared52 green — signed handover/key/history probes pending

Source567dc234 passed52 tests in3 suites, zero failures/errors/skips,4m47s:
real RMA reinstall, all48 CustomerAssetReplacementIT cases (including races and
SQL integrity) and3 RMA custody guards. Archive task26/rma-deployment-green/xml.
The unmatched CustomerDeploymentIT selector supplied no generic deployment cases;
those actual named suites still need the later shared regression.

Current checkpoint adds3 unverified WarehouseCustomerRmaAcceptanceIT probes:
non-posting signed customer handover, normal-vs-RMA mint-key conflict, and the
public inventory assignment history containing both execution source kinds.
The shared fixture first completes a real RMA installation for each probe.
Command `.omo/runtime/rma-acceptance-red.sh`, matching log, archive
`task26/rma-acceptance-red/xml`, queued after52 green. Read results before editing
production. No126 declared/created. Applied125 and earlier immutable.

API guide docs/warehouse-returns.md now covers actual material settlement and RMA
custody/authorize/install; signed acceptance and remaining vendor replacement/
reacquisition/packaged proof stay explicitly unfinished.26 stays OPEN.

##125 applied — original-customer RMA installation passed, shared run pending

V175.125 is APPLIED AND IMMUTABLE, SHA256 `61ce87ff20a7fcf0fc5eafc85c6618223a781074a853f6d0939b51d98eb5c00a`.
Read-only owner query during this run returned175.124|t and175.125|t. Normal
main/test compilation passed. Core WarehouseCustomerRmaDeploymentIT PASSED:
real acknowledged repair reinstalls the same physical asset for original customer,
CUSTOMER title retained, null issue, two historical assignments/ONU episodes,
one active episode, zero ISP availability and exact install replay.

RmaDeploymentSource is explicit; no fake material receipt/plan/issue IDs. Common
execution stores nullable receipt/plan ONLY for sealed RMA handover source, with
captured CUSTOMER asset/original assignment/SKU. Shared deployment result/document,
physical posting and episode validators remain in force. Inventory routes RMA
intent/consume to a dedicated service; WO owner validates REPAIR and current
assigned technician. Common mint/consumption/document writers are reused.

Current `.omo/runtime/rma-deployment-green.sh` and matching log/archive
`task26/rma-deployment-green/xml` still running custody guards and replacement
regressions. NOTE selector '*CustomerDeploymentIT*' matches no current class;
do not claim generic deployment coverage from that selector. Correct class names
are CustomerDeploymentFinalStateIT/ForwardFixIT/StrictInputIT/RootInputIT/
TwoCustomerIT/UpgradeIT, plus CustomerWarehouseProvenanceIT. Run appropriate
shared source/graph cases after the remaining RMA compatibility changes.

Next probes/fixes: signed customer handover for already-CUSTOMER RMA must be
non-posting (existing handover source loader assumes normal issue and PSB);
normal/RMA mint-key collisions and public assignment-history source decoding.
These are source-audit follow-ups, not yet reproduced tests. Then vendor
replacement, approved reacquisition and full packaged proof.26 remains open.

## Applied124 verified; RMA installation baseline and custody guards running

V175.124 is APPLIED AND IMMUTABLE, SHA256 `2c246b66a003534f27816277606f95447fb5d16de3f4efa0db5831bba246b2d2`.
RMA handover, supplier LOAN/SALE and ModularityTests passed6 tests in3 suites,
zero failures/errors/skips,2m23s. Archive task26/rma-handover-green/xml. Source
8ccd89d2 was pushed to feat/warehouse-workorder.123 remains immutable/79 green.

This checkpoint adds a shared real repair/REPAIR-WO RMA fixture, three custody
guard cases (wrong serial/work type, revoked receive/read replay, app-role source
rewrite/fabricated non-posting receipt) and an original-customer reinstall probe.
The latter expects purpose RETURN_CUSTOMER_RMA with null issue, then one actual
CUSTOMER installation/new ONU episode, old history retained and exact replay.
All4 tests are unverified: `.omo/runtime/rma-deployment-red.sh`, matching log,
archive `task26/rma-deployment-red/xml`. Check actual outcomes before production
changes. No125 declared or created yet. Current production remains8ccd89d2.

Next: bind RMA authorization/install to this acknowledged handover without a
fabricated ISSUE, preserve original sale title and episodes; then actual vendor
replacement, approved reacquisition and packaged proof.26/whole plan still OPEN.

## Applied123 verified; RMA handover implementation pending validation

V175.123 is APPLIED AND IMMUTABLE, SHA256 `fb47bf2fd2c6bf6c13fb1bac13addccacf5ad20628c609f8bbd619dbad6f1dde`.
The corrected run passed79 tests in16 suites, zero failures/errors/skips,3m56s:
serial LOAN/SALE once-only usage, omitted-close-history rejection and full material
lifecycle/rework/return closure. Archive task26/material-obligation-green-second/xml.

RMA custody baseline failed1/1 at missing rma-handover route404 AFTER a real SALE,
removal, warehouse recovery, supplier round trip and reset inspection. Archive
task26/rma-handover-red/xml,1m18s, zero errors/skips. Its main/test compilation
completed before new sources were copied from private staging; baseline ran123.

Current source adds dedicated CUSTOMER RMA handover: closed inspected repair,
original customer/assignment, assigned REPAIR WO, independent warehouse sender,
physical dispatch to transit and technician acknowledgement, unchanged title.
Inventory calls a workorder-owned validation port. Reads retain access to closed
WO history; commands/replays require current WO revision/assignment and scopes.
Declared124 captures origin, seals exact requests/legs/outbox and immutable
custody history. No regular ISSUE or ISP availability is created. Installation
permit/reinstall, vendor replacement and approved reacquisition remain unfinished.

Run `.omo/runtime/rma-handover-green.sh`, matching log, archive
`task26/rma-handover-green/xml`: custody case, supplier LOAN/SALE and ModularityTests.
Compilation/result/first124 application are pending; inspect before editing SQL.
Do not edit any successfully applied migration. Task26 and remaining plan open.

## Material obligation correction — verification pending

V175.122 passed76 tests in14 suites (zero failures/errors/skips). New serial and
omitted-history probes then failed3/3 on real valid fixtures. Declared V175.123
counts bound APPLIED serial deployment once and seals complete lifecycle source
keys. Its first bootstrap failed42601 reserved alias and explicitly rolled back;
only never-applied123 was corrected. Second run is
`.omo/runtime/material-obligation-green-second.sh`, matching log and
`task26/material-obligation-green-second/xml`;79 selected tests, outcomes pending.
Check actual applied status before any SQL edit. Ceiling122 is immutable.
Continue task26 original-customer sold RMA, vendor replacement, independent
reacquisition, then remaining plan. No whole-task closure yet.

## Active task:26 —2026-09-24

- Latest:175.122 applied and is IMMUTABLE. Core17.5m accepted inspection/close
  test passed; full lifecycle/rework run still active in return-settlement-green.
  Two new unverified serial-quantity and missing-close-history probes are saved
  here and queued in material-obligation-red.sh under the same QA lock. Read the
  newest task-26.md and final logs before claiming outcomes or editing SQL.
- Supplier/return12 tests, repair guard/module/contracts24, scoped reads/access4
  all passed with zero failures/errors/skips in their respective runs.
- New material closure test reproduced outstanding17500 after accepted17.5m
  inspection (expected0). This checkpoint adds declared V175.122: separate
  settledReturnBase from sealed accepted return, preserves historical returned
  quantity, binds snapshots/header and retains other close fences. No new stock
  posting on close; material summary now reports actual CLOSED lifecycle.
- Main/test compilation passed. `.omo/runtime/return-settlement-green.sh` and
  matching log, archive task26/return-settlement-green/xml; new closure plus
  lifecycle/rework suites. Check full outcomes and122 applied status before edits.
- Earlier migrations through175.121 are immutable.122 is in its first run.
  Latest task-26.md has receipts and source-audit follow-ups (serial used totals,
  omitted lifecycle snapshot lines) requiring real tests before correction.
- Continue actual vendor replacement, original-customer sold RMA handover/ack/
  authorization/install, approved title reacquisition and packaged proof.26 OPEN.
  Whole remaining plan28/30–48/F1–F4 remains active;25/27/29 closed.
- Work on work/warehouse-completion, ordinary push to feat/warehouse-workorder;
  no main deployment, database reset or agent delegation.

## Current integration checkpoint: 2026-09-24

- Transfer position correction passed37 tests in10 suites, zero failures/errors/
  skips. Optional source balance selection and document-owned transit dimensions
  fix mixed AVAILABLE/LOST stock and concurrent transfers of the same identity.
  All three real-DB regressions failed first. Legacy payload hashing is preserved.
- Packaged `qa.sh wave5` passed real public signup, transfer/count/independent
  approvals, stale recount and both JVM phases:14 response replays and16 stock/
  document/history snapshots. Artifact and reproduction are in `continuation.md`.
- Current follow-up is shared `qa.sh replenishment`, then closure25/27/29 and
  implementation26. No migration changed. Newest section overrides historical
  running/pending claims below; final whole-plan checks remain outstanding.
- Earlier combined regression117/1 failure/0 errors/0 skips had only an obsolete
  2-migration expectation versus9 combined migrations; corrected focused upgrade
  passed1/0/0/0 with preservation assertions. Chromium tooling launches.

- Resume from remote `feat/warehouse-workorder`, local continuation branch
  `work/warehouse-completion` in `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe`.
  Task29's separately verified checkpoint remains `work/warehouse-task29` at
  `9ccd5bb0`. No merge/deployment to main is part of this continuation.
- Cherry-picked all task25 source through `f4297aef`, task27 through `57bc533a`,
  and task29 through `9ccd5bb0`, in25→27→29 order, recording original SHAs.
  Shared error registration includes all three controllers. Approval dispatch,
  posting kinds and effect-event lookup retain both transfer and count paths.
  All nine imported migration files are byte-identical to their child branches.
- Tasks1–24 remain checked pending shared replenishment confirmation. Continue
  tasks26/28/30 and the remaining plan after closing the integrated child tasks.
  Read `continuation.md` in the plan's notepad directory for current commands
  and test receipts.
- Existing task29 QA environment/volumes are retained separately; generate a new
  owned environment to test the full migration order. Never apply the earlier
  child migrations out of order to task29's already migrated database.

## Current continuation: 2026-09-24

- User requests completion of the whole remaining plan with regular committed,
  pushed recovery checkpoints. Current workspace is on `work/warehouse-task29`;
  this checkpoint closes task29's outstanding local regression and HTTP proof.
- Task29:191 selected regression tests and1 clean-build live fixture passed with
  zero failures/errors/skips. Two packaged HTTP JVMs passed numeric replenishment,
  authorization, stale receipt rejection and persisted replay checks; eleven
  physical posting counts remained identical. Details and artifact SHA256 are in
  `.omo/notepads/warehouse-workorder-asset-provenance/task-29.md`.
- Global plan still has tasks1–24 checked. Inspect remote tasks25/27, integrate
  25→27→29 and verify the combined source before marking those tasks complete.
  Next implementation dependencies are26,28,30 followed by31–48 and final gates.
- Recovery: fetch all remote branches, read the newest entry here and the ledger,
  and prefer the newest integration checkpoint once published. Recreate the
  isolated QA environment; ignored runtime credentials/evidence are host-local.
  Continue normal explicit branch pushes. Do not merge or deploy main.
- Everything below is historical context; its former current-state labels do
  not override this dated continuation entry.

## Wave 5 Parallel Setup Published

- Task24 remains the latest completed task: 24 complete, 0 blocked, 28 pending. Ready lanes are task25, task27 and task29; no downstream implementation or checkbox change has started.
- Integration worktree remains `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume` on `work/warehouse-resume-20260916`, delivering checkpoints to `feat/warehouse-workorder`.
- Published setup-content base is `1f0564110a6a8a1d5361c8ab58c13dc7d71d08d8`. All three clean child worktrees and queried live remote child refs were created at that exact commit.
- Child recovery branches are `work/warehouse-task25`, `work/warehouse-task27`, and `work/warehouse-task29`, each with an isolated worktree under `/home/fajar/ftth/warehouse-wave5-taskNN` and its same-named remote child branch. Workers push only to those child refs, never the integration branch.
- Migration namespaces are reserved, not created/applied: task25=`V175.113`, task27=`V175.114`, task29=`V175.115`, with children allowed only before a higher migration is applied. Exact rules are in `docs/warehouse-migrations.md`.
- All host QA uses one bounded outer lock at `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock` across the entire up/check/Gradle/HTTP/stop/down lifecycle. Ports25432/29000/17880/14188 never overlap. Each child generates its own private env, marker and volumes.
- Shared warehouse contracts/errors/posting/documents/approval dispatch and the migration manifest have one integration owner. Task27 primarily owns approval dispatch; task29 must use existing generic documents/projections and not depend on a new task25 API.
- Reviewed child commits are cherry-picked by the integration owner in migration order, shared files reconciled once, then combined QA runs under the host lock. No main merge, rebase, force push or PR is authorized here.
- Portable execution map: `.omo/notepads/warehouse-workorder-asset-provenance/wave5-execution.md`. The child branches intentionally remain at the setup-content base; later integration-only receipts do not require resets or rebases.

## Current Status: Task24 Confirmed Complete

- Authoritative state is 24 completed, 0 blocked and 28 pending. Tasks25, 27 and 29 are now dependency-ready; none has started through this checkpoint. Task26 depends on25, task28 depends on26/27, and task30 depends on26.
- Corrected source is `d5344163b5bef69b2c483ced1bc41c90c1653183`. Source reviewer `ses_f4fee4e43ffefMaI3Uq4c5eotK` returned `CONFIRMED` with `safe_to_mark_task24_source=true`. Runtime reviewer `ses_f4fee4d34ffemYo7phG6cKJ5lR` returned final `CONFIRMED` with `safe_to_mark_task24_runtime=true` at `f3e73959fc43766ef38996b52eccb30687307a31`.
- Fresh non-overlapping evidence is exact compatibility 20 and focused 40, both with zero failures/errors/skips. Actual IAM/HTTP proves own-area 200; different-area, restricted-empty, null-area, foreign and missing 404; original JWT after revocation 404 and after restoration 200; legacy 37 plus V2 `82500 MM`/`82.500 M`, paging and portal privacy remain intact.
- Clean 19-task JAR SHA256 is `ccf100eb75a7838aaa026dba5dde74ade765d7be8fe757877e32233d9e219c35`. No SQL/migration changed and live ceiling remains `V175.112`. Cleanup left zero owned containers, JVMs, listeners, PID/private directories or temporary schemas; two volumes/images/data remain.
- Earlier task24 14+78 compatibility/import/source-gate evidence is carried only by unchanged identity; it is not represented as fresh or full-suite evidence. Historical catalog and NetworkEndToEnd caveats remain task46 work.
- Strict clean-code and task23's owner-approved documentation-only GPON boundary remain binding; no hardware certification is claimed.

## Historical Task24 Correction And Review Timeline

- Executor correction now has genuine red5/4, green targeted14/exact20/regression95, all green runs0 failures/errors/skips. Current step is checkpoint then clean build, real area HTTP/DB proof and cleanup; independent re-review remains pending. New customer-owner check uses existing CurrentAuthority scope semantics before facets; unscoped API/DTOs/inventory queries unchanged. See newest task-24.md.

- Source reviewer ses_f4fee4e43ffefMaI3Uq4c5eotK rejected checkpointa709b65a: Subscriber360's unscoped customer lookup permits cross-area legacy/V2 material exposure. Current step is failing-first IAM/HTTP reproduction and a customer-owned scoped lookup before any facets. Preserve the unscoped API for portal/background use.
- Runtime reviewer ses_f4fee4d34ffemYo7phG6cKJ5lR passed old14/78 cases without this area case; no aggregate approval. The historical executor claim below is superseded for this finding. Task24 unchecked; task23 and other reviewed behavior unchanged. No migration expected,175.112 immutable. Read newest task-24.md/EOF ledger.

- Task24 sourcec7e3647acb984cd48666ac49ff3a27e71b73691c is pushed. Exact13 twice, final14 including connection-loss rollback, and selected91 pass with0 failures/errors/skips. Clean19-task JAR SHA2568fc6327a7a37a3c834d685fb6bd7a3e65873fdf8e91edee3de9fbeabd1790f11 passed two actual isolated HTTP/DB journeys. No migration; live175.112 unchanged. Read newest task-24.md and local task-24/DoneClaim.md for complete scope, classified failures and source/artifact binding.

- Current step: independent task24 source/runtime verification, not more implementation. Task24 remains unchecked. Verify server-tenant background import behavior, explicit V2 deployment/legacy count distinction, additive owner provenance and portal privacy. Live multipart promotion was intentionally disabled; actual full promotion is DB-integration proof, not a claim from PROCESSING.
- Cleanup is complete: zero owned containers/JVMs/API/task listeners/PID files; local live manifests and dirty sentinel removed. Original environment/lock and two volumes/images/data retained. No temporary schemas created. Protected checkout, task23 status and GPON scope unchanged; no task25/later/PR/merge.

## Confirmed Task23 Snapshot (Unchanged)

- Authoritative top-level state is 23 completed, 0 blocked and 29 pending: tasks 1-23 are `[x]`; tasks24-48 and F1-F4 remain `[ ]`. Task24 is next. This is task23 completion only, not full-plan, task46, release or final-wave approval.
- Owner decision dated 2026-09-17: "yaudh skip aja dulu yg GPON ZTE/Huawei/FiberHome mah, buat sesuai yg ada di dokumentasi mereka aja". Physical ZTE/Huawei/FiberHome GPON capture, model/firmware certification and hardware validation are deferred and no longer block this plan.
- Traceability is now recorded in docs/gpon-profile-evidence.md: exact Huawei XPON mirror objects support bounded corrections; ZTE constants remain documentation-unverified compatibility assumptions; FiberHome's contradicted description/speed OIDs are explicitly unavailable. None is hardware-validated. No index formula or MAC-to-GPON identity was invented.
- Undocumented or unknown raw-index formats remain `UNVERIFIED` and quarantined. No decoder guess or raw-index relabelling is allowed.
- This decision does not remove existing GPON code or waive warehouse provenance, CPE ownership/freshness, temporal attribution, database/RLS, privacy, source-gate or strict clean-code requirements. All unrelated real DB/browser/stock gates remain intact.
- The HSGQ-E04I read-only field evidence is EPON-only. It is not ZTE/Huawei/FiberHome GPON firmware, mapping or certification evidence.
- Current tested/built source is `a028c6a934b191e2fabdc596000f4b40d8fd33eb`. Bounded GPON profile/parser corrections and direct fixtures are pushed. V3 ingestion/source/time logic and confirmed CPE/DB production blobs remain byte-identical; all SQL through175.112 is untouched.
- CPE-R2 reviewer `ses_f54d52690ffefwS6LHWH9xpiJt` and DB-R2 reviewer `ses_f54d5267bffeH6M87YAIQbBrrv` confirmed their `39295078930507d75b58213420c4bb63025d011d` scope. Their corresponding implementation and SQL blobs are unchanged by verify-03.
- Temporal/source reviewer `ses_f54d527eaffeQMhYzFuG21jaGJ` confirmed DISCOVERY-3 timestamp safety and T3 unique active legacy/no-ODP handling at assigned checkpoint `5e49bc4e4377d484629df18dd1692643c461ac04`. The review specifically accepted the named `appendUntrustedTime` helper, explicit typed/raw timestamp boundary, existing shared time policy and narrow legacy eligibility checks as clean, cohesive code.
- Source reviewer `ses_f54d527eaffeQMhYzFuG21jaGJ` returned `CONFIRMED` with `safe_to_mark_task23_source=true` at `d5e9a4fb09ebde79289d08a98e0596e28af94303`. Runtime reviewer `ses_f54d52609ffe9BiEMrmv2lwpMu` independently returned `CONFIRMED` with `safe_to_mark_task23_runtime=true` on the same artifact.
- Fresh current runs: exact WarehouseDiscoveryIT 142, SNMP 25 and collector 22, all zero failures/errors/skips. The default 30-minute exact attempt timed out before XML finalization and is not counted; the same validated environment, lock, filter and flags completed under a 45-minute bound with 142/0/0/0. No tracked harness timeout was changed.
- Clean JAR SHA256 `cc17447cd9ad0a5e383cef9734fcd7a185ee82b649163defef2e98f5bdb052cd` passed the real source-gate, delayed-A, privacy, mixed-clock and replay journeys. Cleanup independently verified zero owned containers, JVMs, listeners and temporary files/schemas; two volumes/data were retained.
- Prior CPE-R2/DB-R2 confirmations at `39295078` and V3 confirmation at `86419931` remain identity-bound receipts for unchanged blobs, not fresh 545/95 reruns. Historical catalog and `NetworkEndToEndIT` caveats remain for task46.
- Physical GPON validation is deferred by explicit owner decision. Huawei behavior is documentation-backed/not hardware-validated; ZTE remains documentation-unverified compatibility; FiberHome is unsupported where the prior profile contradicted available evidence; unknown connected raw indexes remain quarantined. No hardware or vendor-wide certification is claimed.
- Task24 is the exact next action. It depends on task23; tasks25/27/29 depend on task24 and no downstream task has independently started or become ready through this checkpoint.
- Strict clean-code requirement remains binding: cohesive small code, explicit types and errors, public ownership boundaries, no duplicate policy or silent success, and only narrow purposeful abstractions with focused tests.

## 2026-09-17 Owner Decision Superseding The Physical GPON Blocker

- This dated owner decision supersedes the current status of the historical blocked records below without deleting or rewriting them. Those records remain evidence of what was previously required and attempted.
- Physical ZTE/Huawei/FiberHome validation is deferred. Documentation/MIB traceability and offline fixtures now define the GPON evidence boundary, with explicit documentation-backed/not-hardware-validated labelling and fail-closed `UNVERIFIED` quarantine for unknown formats.
- Task23 returns from `[~]` to `[ ]` for revised-scope closure verification. It is not `[x]`, and task24 has not started.

## 2026-09-16 — User-requested remote recovery checkpoint

- User instruction: commit and push immediately, and keep the work position documented so another agent can resume if this VPS is lost. This supplements the existing immediate-push/no-merge policy; it does not authorize force-push, main deployment, or skipping verification.
- Latest recorded completion: tasks 1–22 of 48; wave 4 of 8. Tasks 23–48 and final gates F1–F4 remain unchecked. Source: the active plan and the 2026-09-15 task22 completion/checkpoint entries in `.omo/start-work/ledger.jsonl`.
- Exact next task: 23, discovery/auto-provision/CPE integration. Enforce the same warehouse-origin authorization for non-UI callers; observation must not create stock. Follow the full task23 references, acceptance criteria and QA in the plan.
- Historical evidence is not a fresh full-suite pass. This setup verified Git topology, note structure and the live remote only; it did not rerun product tests, complete a final gate, or start task23.
- Verified setup base: live remote `feat/warehouse-workorder` and both local branches started at `2105273f1de7783fdc2454de6bc9e4a3a86be38a`. The task-owned worktree is `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume` on local-only continuation branch `work/warehouse-resume-20260916`; pushes target `HEAD:refs/heads/feat/warehouse-workorder` explicitly.
- The protected initial checkout remains `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe` on local `feat/warehouse-workorder`, with its two pre-existing dirty note copies preserved and no branch displacement, stash, reset, rebase, or file edit by the checkpoint worker.
- The prior ledger path `/home/fajar/ftth/worktrees/warehouse-workorder-asset-provenance` does not exist on this host and is historical only.

### Checkpoint procedure

1. Use the separate local continuation branch for checkpoint and later task work; never force checkout the feature branch out of the protected initial checkout.
2. Push each checkpoint commit normally with explicit refspec `HEAD:refs/heads/feat/warehouse-workorder`; stop on divergence and verify the live remote SHA rather than relying on tracking refs.
3. The repository had no effective configured Git identity during setup. Plan line 486 authorizes `fajarxfce <fajaralamsyah000@gmail.com>` through commit-scoped author/committer environment variables only; do not modify Git config or carry those variables into other commands.
4. At every later completed task or interruption boundary, update this handoff and append a sanitized ledger receipt in the same checkpoint. Record completed tasks, current substep, implementation commit, migration versions, tests actually run, verifier status, failures and exact next action. Leave incomplete or unverified tasks unchecked.
5. Preserve concise, sanitized verification summaries in tracked notes. Raw evidence, local session IDs and absolute evidence paths are not portable proof; rerun unavailable checks after recovery before claiming new verification.

### Recovery on a replacement VPS

1. Obtain this repository from its configured remote and check out remote branch `feat/warehouse-workorder`; the local continuation branch and current absolute worktree path are host-local setup details, not required branch names on a replacement host.
2. Reconcile the newest remote checkpoint with plan checkboxes and ledger receipts. Historical append-only notes can say "unchecked" after later confirmation; use the latest matching receipt. The planning draft describes earlier plan approval, not current implementation progress.
3. Recreate the isolated QA environment using the task1 scripts/runbook. Treat local databases, retained volumes, binary evidence and agent sessions as unavailable unless separately restored; this Git checkpoint is not a database/object-store backup.
4. Known unresolved regression from task22: `NetworkEndToEndIT` had 10 legacy-fixture failures expecting serial-only ONU creation (201), while the task20 provenance guard returns `409 USE_WORKORDER_ASSET_WORKFLOW`. Do not report the full suite green.
5. Resume `/start-work warehouse-workorder-asset-provenance --make-pr` at task23 only after confirming no newer remote WIP/checkpoint. PR delivery waits for all plan work and review; merging remains forbidden.

## Resume

- Remote target branch: `feat/warehouse-workorder`
- Local continuation branch: `work/warehouse-resume-20260916`
- Task-owned worktree on this host: `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume`
- Verified checkpoint base: `2105273f1de7783fdc2454de6bc9e4a3a86be38a`
- Current product source SHA: `d5344163b5bef69b2c483ced1bc41c90c1653183`
- Current migrations: through `V175.112`; verify-03 added no migration and all SQL is unchanged from the confirmed `39295078` scope.
- Current task24 JAR SHA256: `ccf100eb75a7838aaa026dba5dde74ade765d7be8fe757877e32233d9e219c35`.
- Tasks 1-24: checked; tasks 25-48 and F1-F4: pending
- Active plan: `.omo/plans/warehouse-workorder-asset-provenance.md`
- Delivery mode: `--make-pr` after plan completion; immediate checkpoint pushes; no merge
- Resume command: `/start-work warehouse-workorder-asset-provenance --make-pr`
- Executor: `ses_f5b0136f1ffeJmjJSpMn6LnyGT`
- Task 22 verifier: `ses_f59e67eacffeVf28hlgEdebi2F` (confirmed/high)
- Historical task22 identity: migrations `V175.80` through `V175.89`; JAR SHA256 `b062548e6601935f073e7b12d468cb100497ff7ef1d88def78af99f37c84ac1c`. This is retained history, not the current task23 artifact identity.
- Next dependency-ready tasks: 25 transfer/discrepancy, 27 blind counts and 29 replenishment. No implementation has started through this checkpoint.
- Immediate normal fast-forward push is required; no merge.

## Verified State

Tasks 1-24 are checked with independent task24 source/runtime confirmation. Tasks25-48 and F1-F4 remain pending.

- Task 1: PASS after correction; isolated environment and fail-closed runner verified by `ses_f86494639ffe1UztWotWQf1lcy`.
- Task 2: PASS contract-only; strict public contracts, modularity, and packaged build verified by `ses_f86079a15ffeG2Er4fVXbaZqiC`.
- Task 3: PASS; exact quantity/identity tests, packaged probe, and regressions verified by `ses_f85d359e7ffeSj01vhkOZqkw17`.
- Task 4: PASS after forward-only corrections; schema, Flyway, RLS/GUC, tenant, and concurrency evidence confirmed by `ses_f8526de97ffeAXdgJag71WChmx`.
- Task 5: PASS after AV5 corrections; posting, custody, conservation, rollback, restart, and concurrency evidence confirmed by `ses_f8358f7b9ffezDBYdyrjPqXgXG`.
- Task 6: PASS after AV6 corrections; durable outcomes, current authority, outbox/inbox, replay, and concurrency evidence confirmed by `ses_f80ea045effes33EHCfzoajd7a`.
- Task 7: PASS after AV7 corrections; master APIs, strict decoding, topology, scope, restart, and privacy evidence confirmed by `ses_f8087024affeelaOdIEnVR6c8b`.
- Task 8: PASS after AV8 corrections; receiving, inspection, putaway, file validation, restart, and race evidence confirmed by `ses_f7bde4ef4ffepGrCcS6ZQv0d5K`.
- Task 9: PASS after AV9 corrections; bounded projections, filters, privacy, restart, and compatibility evidence confirmed by `ses_f7aee14dfffe6NP997sLvIMWMR`.

- Task 10: PASS after corrections; independent re-verification confirmed by `ses_f79532a97ffe5s56PUPGBJ6YKA`.
- Task 11: PASS after AV11 corrections; independent re-verification confirmed by `ses_f7719948affe0wmQEHImgzxMYe`.
- Task 12: PASS after AV12 corrections; approval, immutable effects, restart, and concurrency evidence confirmed by `ses_f73a315e5ffeibNLVDMKoYmrnu`.

## Exact Next Action

Resume one dependency-ready Wave5 lane: task25, task27 or task29. Coordinate shared environment and file ownership; task26 waits for25, task28 waits for26/27, and task30 waits for26. No later completion is implied.

## Continuation Policy

The active plan, draft, notepads, ledger, and this handoff are the portable state. Push immediately after every checkpoint commit; use regular fast-forward push only, stop on divergence, and never force-push. Do not merge or create a PR from this checkpoint.

`.omo/boulder.json` is intentionally omitted: it contains absolute main-checkout paths and ephemeral/stale running-session metadata, so it is not portable. Resume derives from the repository-relative plan checkboxes and the tracked ledger/handoff. Runtime state is not required for this checkpoint. Push immediately after every checkpoint commit using regular fast-forward push only; stop on divergence and never force-push.

## Excluded Data

No runtime env files, credentials, API keys, private keys, JWTs, connection strings, generated logs, screenshots, archives, binary evidence, database data, object-store content, build output, session caches, `.omo/runtime/**`, `.omo/evidence/**`, `.omo/run-continuation/**`, or `.omo/boulder.json` is tracked.

## 2026-09-16 Task23 Blocked Before Product Edits

- This entry supersedes the earlier next-action wording. Task23 preflight started from clean local/live remote0735b81c3fbc8ae8dd2c4b4f826b480d596db6a5, but stopped before baseline/red proof as required by the infrastructure gate. Tasks1-22 remain checked; task23 and all later tasks remain unchecked.
- Docker29.8.0/JDK21.0.12.1 are available. Isolated up failed pulling pinned minio/minio:RELEASE.2025-09-07T16-13-09Z with pull access denied; timescale/timescaledb-ha:pg17 pull was interrupted. Neither required image is cached. No registry credential or replacement image was guessed.
- Isolated check and exact WarehouseDiscoveryIT QA runner refused64 before Gradle because owned PostgreSQL is absent. Zero product tests, no real baseline/feature-red, no bootJar or manual HTTP/DB/privacy proof. Existing NetworkEndToEndIT10 caveat is unchanged and not rerun.
- No product/test/migration/harness/checkbox changes. Packaged SQL ends at V175.89; manifest reservations V175.90-.93 exist without SQL. Historical manifest applied-version text must not be treated as current DB proof.
- Stop/down exit0; no containers/volumes, task PID records or task-port listeners remain. Only default Docker networks exist. Private generated env/lock retained locally, ignored; no preexisting data removed. See tracked task-23.md for probe details.
- Needed external action: provide approved registry access or trusted restoration for the pinned images; if unavailable, approve and verify a compatible isolated-harness replacement separately. Do not weaken QA isolation or fall back to production.
- Exact current substep: infrastructure capability BLOCKED. Next: verify newest remote checkpoint, rerun up/check, then establish real legacy telemetry/source-gate baseline and genuine task23 red tests before implementation. Independent verifier remains pending; this is an incomplete notes-only checkpoint, not a DoneClaim.

## 2026-09-16 Isolated Infrastructure Recovered, Verification Pending

- Supersedes the infrastructure-blocked state above, not the task23 feature status. Only warehouse Compose MinIO pin and one direct WarehouseEnvironmentIT assertion changed. Task23 remains unchecked; no business logic or new migrations.
- Official same-release source: https://raw.githubusercontent.com/minio/minio/RELEASE.2025-09-07T16-13-09Z/README.md. Independently verified manifest-list digest sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e. Pin is quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e. Not a claim of byte equivalence with unavailable DockerHub content. Timescale and all harness safety settings unchanged.
- Standalone configuration assertion failed before the edit and passed afterward; original startup denial retained. Isolated up/check exit0; exact WarehouseEnvironmentIT executed7 tests twice, each0 failures/errors/skips, with real storage roundtrip/nonowner/RLS checks and actual compilation. Raw XML archived before rerun. Running release/digest and loopback bindings verified.
- Cleanup stop/down exit0, zero containers/owned JVMs/task PID records/task-port listeners; task volumes/images retained. LSP rejected external-worktree paths, compiler/tests used instead. No task23 baseline/feature-red, bootJar or full-suite claim; known NetworkEndToEndIT10 caveat retained.
- Exact current substep: infrastructure-only DoneClaim awaiting independent verification. Next after confirmation: run up/check, establish observable legacy active telemetry/source-gate baseline, then genuine task23 red tests and implementation. Do not start task24 or mark task23 complete. See tracked task-23.md and the latest ledger entry for portable evidence.
- Infrastructure repair implementation SHA: a61fdf5942063de1f994e1122744bfce6bcff9c7, immediately pushed and matched by live remote. Subsequent commits are safe notes only; task23 product implementation remains absent.

## 2026-09-16 Task23 Feature WIP

- Infrastructure was independently confirmed by ses_f56815f33ffe2OwxmAqXcjMN5o (fresh7/0/0/0); feature work resumed. Current implementation76abef67f9dac1837be63096838c4fb444334f78 is a pushed WIP, not a completion or approval.
- Baseline3 passed before production edits; genuine behavioral red4 failed before changes. Latest focused58 passed: direct task23 cases18, baseline3, modularity3, CPE regressions34. Broad final regressions, clean bootJar and built live HTTP/ACS/restart proof remain outstanding. Original failures/archives retained; NetworkEndToEndIT10 caveat unchanged.
- Forward SQL V175.90-.95 adds temporal live-state/path evidence, durable unassigned observations, episode-bound CPE snapshots and original discovery receipts. No applied predecessors changed and no task24/26/UI/mobile implementation.
- Exact current substep: complete task23 adversarial and live surface verification after this checkpoint. Isolated stack stopped, owned compiler daemons terminated, both volumes retained. Read task-23.md for scope/proof details; never mark task23 checked before independent feature verification.

## 2026-09-16 Final Artifact Verification Next

- Product head d67215edbc966216cd8622bead980d35cd601df6 is pushed. Current exact feature31, monitoring/CPE/provenance148, and customer/task22 batch235 pass with zero failures/errors/skips. SQL V175.90-.100 is forward-only; original source/hash histories remain intact.
- Final corrections cover parameter-level ACS freshness, actual assignment revisions, global ACS owner ambiguity (including suspended tenants), precise episode boundaries, recovered-device rediscovery, current-authority replay and deferred conflict mapping.
- The first built HTTP/DB/ACS and crash/restart proof passed; repeat it on the final clean JAR with timestamped ACS simulator fields before DoneClaim. Current substep is final artifact QA/cleanup, not another implementation task. Task23 remains unchecked and independent feature verification pending.
- Wider schema129 had one unchanged task22 catalog-string mismatch, characterized against175.89; do not claim it or the full server suite green. See task-23.md for retained failure evidence and precise next commands.

## 2026-09-16 Task23 DoneClaim Ready For Independent Verification

- Product head a3e9be5331bd02b8e2e8459f8686790fe127a281 is pushed; task23 is implemented and executor-verified, not independently approved. No checkbox changed. Tasks1-22 remain checked; task24 and later remain pending.
- Exact WarehouseDiscoveryIT33 passed twice with0 failures/errors/skips. Selected bounded gates148,235,64 passed;374 distinct passing cases across overlapping selected archives. Baseline3 and genuine red/green proofs are preserved. No full-suite-green claim.
- Final clean JAR SHA2568c298ec0d91544ec21bc4a20deebf6070a539e9762b971f876f262e377dae5e5,19 tasks executed. Actual curl/DB/owned ACS proof passed on17880/17881, then SIGKILL/restart retained original replay, exact stock/history counts and B privacy. Global SMTP health remained intentionally unconfigured; readiness/DB/MinIO succeeded separately.
- Forward migrations V175.90-.101, all predecessors unchanged. V175.101 fixes authenticated tenant/collector batch scope and retention, exposed by final live QA. No source/custody/WO/RLS validator bypass; no task24/26/UI/mobile work.
- Cleanup verified zero owned containers/JVMs/schemas/PID records/task listeners; two task volumes and images retained. Only runtime env/lock retained privately. Sanitized raw evidence is ignored; portable details are in task-23.md and ledger.
- Known caveats: unchanged schema catalog assertion in WarehouseSchemaIT129/1, plus historical NetworkEndToEndIT10 fixture failures. Exact next action is independent task23 AdversarialVerify using feature/DoneClaim.md, changed-files.txt, source-hashes.txt and archived XML. Do not self-approve or advance task23 until that verifier confirms.

## 2026-09-17 Rejected Task23 Recovery WIP

- Supersedes earlier DoneClaim: source review lanes NEEDS_FIX. Forty correction paths after2453c5db are being secured in focused commits before further edits. Focused38/0/0 passed; topology regression UNRUN.
- Applied ceiling175.105 confirmed live; immutable hashes/checksums and detailed remaining work are in newest task-23.md. Reserve new forward versions, never rewrite applied SQL.
- T1-T8, malformed-time behavior, global-fence lock order/selective GUC, exact repeats, regressions, fresh JAR and live/restart proof remain mandatory. No approval from old runtime scenarios.
- Owned stack/compiler daemons retained only during active use. No active test/API/simulator at inspection. Cleanup and normal push/live-SHA proof required before return; preserve volumes/data/images.
- Resume newest remote feat/warehouse-workorder checkpoint, then run WarehouseDiscoveryITReviewTopology. Task23 unchecked; no task24.
- Code/test checkpoint5ca646532be26a0a9dda47e10eaf07d8ab88043a is pushed/live-SHA verified with clean worktree;19 focused commits after2453c5db. Exact40-path inventory is appended in task-23.md. Still WIP, not corrected DoneClaim.
- Temporal WIP now has84 combined passing cases after genuine T1/T2/T3/T5/T7 and corrected T4/T8/fence reproductions; see newest task23 note for caveats and immutable106-109 hashes. T6, expanded DB/timing/retention qualification and final artifact QA remain open. This update does not mark task23 complete.
- Retry/DB qualification now passes14 scoped cases, producer17+22, and first exact task23 run81. T6 is stable transactional409/retry, not removal of every lock conflict. Populated marker upgrade and chunk retention now pass. See latest task23 note and observation-contract.md; final repeat/regression/build/live/restart/cleanup remain outstanding.
- Broader regressions now545 customer/provenance and95 monitoring/CPE pass after documented fixture and real bulk/legacy-bound corrections. Two final exact repeats and live artifact/restart/cleanup still required. Manual conflict seeder is unrun WIP until recorded otherwise. Do not infer completion from these counts.

## 2026-09-17 Corrected Artifact Ready For Independent Repeat Review

- Supersedes pending executor QA above, not the independent rejection. Source tree11b0e18f9eb380988a1148deaa5f370e789e3076 is pushed; final notes-only checkpoint follows. Task23 remains unchecked/unapproved; no task24.
- Final exact83 twice; bounded545 and95; producer17+22; manual seeds2+1 all passed with zero failures/errors/skips.742 distinct final selected cases across overlapping archives. Clean executable JAR SHA25692f4bb760b2f55523a9c8169f2b172f4a9bee8412658b7298001b435c4ec8ded,19 executed build tasks.
- Actual built HTTP/DB/ACS202/future/conflict and SIGKILL/restart passed. Original replay/counts persist; delayed A cannot alter B, future stays unassigned, and new competing owner blocks B reads/actions. Current SMTP health caveat is explicitly retained; readiness/DB/MinIO passed separately.
- Cleanup verified no owned containers/JVMs/temp schemas/PID records/task listeners. Original two volumes/images/data and private env/lock preserved; owned manifests removed.28 complete sanitized XML archives plus source inventory/hashes and corrected DoneClaim stay ignored under task23/verify-01.
- Applied SQL through175.109 immutable. All per-finding evidence, conservative GPON limitation, stable retry behavior and old catalog/NetworkEndToEnd caveats are in newest task-23.md and DoneClaim. All four source lanes and runtime must rerun independently on this artifact; prior runtime pass is not aggregate approval.
- Resume only independent task23 verification from newest remote feat/warehouse-workorder. Never treat this executor DoneClaim as permission to check task23 or start task24.

## Verify-02: Source Review Still NEEDS_FIX

- Second CPE/DB source review rejects67dc9513185ca907e0704125781fbf89d636ab76 despite scoped runtime success. Latest task23 note lists six CPE-R2/DB-R2 findings and additional timing/isolation qualification. Reviewers: ses_f54d52690ffefwS6LHWH9xpiJt and ses_f54d5267bffeH6M87YAIQbBrrv.
- Resume correction in the task-owned worktree only. First publish this notes-only checkpoint, then genuine failing-first proofs. Applied migrations through175.109 stay immutable; reserve new versions before creation. Temporal/discovery reviewers read immutable67dc9513 concurrently and do not own mutable QA state.
- Task23 remains unchecked/NEEDS_FIX. No task24/PR/merge. Previous DoneClaim and83-case runtime pass are historical scope evidence, not aggregate approval. New evidence and corrected claim belong under task23/verify-02, with normal checkpoint pushes and full owned-resource cleanup.
- Verify-02 corrections and qualification are now checkpointed with125 exact tests twice,545 customer and90 monitoring/CPE regressions passing. Source hash/chain/interval guards110-112 are applied and immutable. Manual seeds3+1 passed; clean artifact/live success/stale/pre-POST/restart/cleanup still pending. See newest task23 note, not the old83-case claim.

## Verify-02 Artifact And Cleanup Complete, Independent Review Pending

- Built/tested source6b06b14298111dc7f12346541e83f898efbdea6b is pushed; final notes-only handoff follows. Final exact126 twice, customer545, final monitoring/CPE95, gateway17 and seeds3+1 pass with zero failures/errors/skips. Classified earlier failures remain evidence.747 distinct final selected cases across21 full sanitized XML archives.
- Clean executable JAR SHA256cd0d1aeb566e185088086b74ad8637c362afc64f6c5268430e5acac1d78c1a68,19 executed tasks. Real HTTP/DB/NBI valid diagnostic success, static/late stale withholding, pre-POST owner loss, hash/chain/interval denial and SIGKILL/restart passed on the same artifact.
- Applied SQL through175.112 immutable; no predecessor rewrites. Cleanup verified no owned containers/JVMs/temp schemas/PID records/task listeners; seven generated manifests removed, original env/lock and two volumes/images/data retained.
- Read task23/verify-02/DoneClaim.md and latest task-23.md for exact findings, protocol assumptions, counts, hashes and preserved caveats. All affected source/runtime reviewers must repeat on the final artifact. Task23 remains unchecked/unapproved and task24 must not start.

## Verify-03 Narrow Ingestion Correction

- CPE-R2 and DB-R2 reviews at39295078 are CONFIRMED by ses_f54d52690ffefwS6LHWH9xpiJt and ses_f54d5267bffeH6M87YAIQbBrrv; preserve that scope. Temporal/discovery still require DISCOVERY-3 safe extreme timestamp quarantine and T3 known-unattached GPON telemetry compatibility. No aggregate task approval.
- User explicitly requires strict clean code: small cohesive functions/classes, explicit types/errors, public boundaries, no duplicated policy/silent success, only purposeful abstraction and focused tests. No unrelated refactors or CPE redesign.
- Entry39295078930507d75b58213420c4bb63025d011d is clean; prior verify-03 attempt was only rate-limited. Use verify-03 evidence, targeted tests and real HTTP proof; defer full suites. Connected mapping must remain blocked if authoritative vendor/firmware evidence is unavailable. Task23 stays unchecked; task24 must not start.
- Targeted source checkpoint864199313485a88521ae5b767b0f45ccf9ee62c3 now fixes extreme clock quarantine and unique active legacy/no-ODP GPON telemetry. Live18, scoped46, SNMP17 and serialization6 pass; standalone built HTTP/DB confirms sibling preservation, inert replay, episode-only data and mismatch denial. CPE/DB guard blobs and SQL remain unchanged. Strict clean-code requirement remains binding.
- Connected GPON positive mapping remains BLOCKED: obtain paired raw SNMP index/serial and authoritative vendor/firmware CLI/API port identity across multiple ports/ONUs plus MIB semantics. No decoder guessed or raw index relabelled. This is not full task23 compatibility or approval.
- Cleanup complete: owned API/listeners/compilers/containers/PID and private live files removed; original env/lock and two volumes/images/data retained. Read verify-03/DoneClaim.md and newest task23 note. Next is targeted temporal/discovery review and external mapping resolution; full suites wait for source scope settlement. No task23 checkbox/task24 advancement.
