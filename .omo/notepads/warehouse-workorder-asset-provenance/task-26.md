# Task26 — returns, inspection and repair (in progress)

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

##135 twelve green;136 reserved for declared cost and immutable rejection

return-replacement-posting-green passed12 tests/4 suites/0 failures/errors/skips,
1m45s:2 genuine LOAN/SALE vendor replacements,6 ordinary approval guards,
1 normal receipt and3 modularity cases.135 is APPLIED AND IMMUTABLE after the
previous syntax failure was fully rolled back. New assets have distinct identities,
real same-vendor RECEIPT provenance and correct ISP/CUSTOMER quarantine ownership;
original asset remains with vendor. Archive return-replacement-posting-green/xml.

Reserve136 BEFORE creation to permit an explicit optional source cost from vendor
evidence (null omitted to preserve old canonical input/replay) and terminal
rejection/DRAFT revision1 with matching REWORK_REQUIRED approval. Bound replacement
intake remains immutable; generic rework must direct callers to a new request.
Test actual replacement approval, competition and stale/current scope/SQL guards.

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

##135 reserved before creation — exact physical vendor replacement receipt

The corrected initial INSERT now passes both replacement request201/replay cases.
Both tests advance to receive at line23 and fail with expected draft-only134
REPLACEMENT_REQUEST_RECEIPT_BINDING (13:51:52.558/13:51:55.806 UTC). Three modularity
cases passed. Current run24771 still finishing normal WarehouseReceiptITReceive;
do not claim its result yet.134 is applied/immutable; no135 SQL exists yet.
Reserve135 for verified admission owner and immutable exactly-once receipt effect.

##134 applied; first draft field initialization corrected (run pending)

134 applied successfully at20:48:32.541 JKT and is IMMUTABLE. First request run
compiled both source sets, passed3 modularity cases, failed both supplier cases
at POST creation:5 tests/2 suites/2 failures/0 errors/skips,1m53s. PostgreSQL logs
13:49:19.038 and13:49:23.563 UTC identify warehouse_revision_guard rejecting the
post-creation UPDATE inventory_document SET customer_id/work_order_id/revision.

Working Kotlin correction initializes replacement customer/WO/owner at initial
INSERT via internal WarehouseReceiptService.replacementDraft and a separate
ReceiptDraftContext (normal HTTP ReceiptDraftInput remains unchanged). Removed
header/line UPDATEs from SupplierReplacementStore.insert. Shared normal receipt
payloads remain byte-shape compatible. No schema change required for this fix.
Current .omo/runtime/return-replacement-draft-green.sh / matching log (session24771)
runs2 supplier cases,3 modularity and WarehouseReceiptITReceive. Compile passed;
no test outcome claimed yet. Read live log. Still no135 declared/created.

Next physical admission design: add immutable effect/consumption row unique per
repair and per new receipt/asset, with deferred FK to actual receive operation.
Generate operationId before origins.admit in ReceiptTransitionService (approval
already has it), pass it to admission, capture the new asset mapping before post,
and validate full real RECEIPT/APPLIED legs/outbox at commit. Owner comes from
verified replacement binding, default ISP for ordinary receipts, never from HTTP
or an arbitrary draft line. Validate/lock original source before topology and
receipt locks; approval prepare must record durable STALE before deciding if
source changed. Include replacement context in frozen approval source.134 draft
validator must be extended via135 only for complete verified received effect.
Multiple drafts allowed, one physical replacement per repair. Rejection needs
controlled new-request flow, no generic rewrite of immutable bound intake.

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

##134 reserved — supplier replacement draft/source binding

Direct quarantine final run is13/3/0/0/0,3m41s; e5d2cef1 published/verified.
Supplier baseline ran both genuine LOAN/SALE repair setups and reached the absent
replacement-receipts route; XML/log in return-replacement-red (inspect exact
failures). New InventorySupplierReplacementApi and SupplierReplacementModels
are being authored. Reserve134 BEFORE SQL creation; no134 SQL exists yet.

Implementation direction: create a normal RECEIPT draft via the existing receipt
service, with immutable source binding to original return/repair/assignment and
captured owner. Separate request idempotency namespace/key from nested receipt
creation (derive internal key from request identity). Validate current original
WO/return/asset and source/destination scope before replay. Keep old asset and its
vendor custody/history intact. For CUSTOMER source, physical admission must use
CUSTOMER from verified binding; ordinary receipt inputs must never choose title.
No physical receipt is enabled until exact graph/owner/one-replacement consumption
proof is available. Use forward migration for post-draft lifecycle as necessary.

Next integration points: WarehouseReceiptOrigins.admit currently hardcodes ISP
for asset/legs; WarehouseReceiptPersistence.saveDraft sets line owner ISP.
ReceiptTransitionService and ReceiptApprovalOwner both call origins.admit, then
post. ReceiptTransitionService needs source locking before topology/receipt locks
for linked replacement receipts; use same source-lock owner pattern as approval.
WarehouseApprovalStore.source must capture replacement binding when present.
Generic receipt inspection/putaway requires ISP, so CUSTOMER remains safely
quarantined until replacement-specific inspection/original-customer handover is
implemented. Preserve normal receipt payload/snapshot shape and historic SQL.
Multiple draft attempts may exist but only one physical replacement may be
consumed per repair; do not strand a rejected immutable request behind a draft
unique constraint. A separate immutable consumption/effect row is appropriate.

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

##132 applied;133 reserved for independent balance revisions

return-title-effect-green compiled both source sets and applied132 successfully
(Flyway log20:31:53.544 JKT). Four tests/two suites:3 modularity pass, direct
reacquisition fails at final approval;0 errors/skips,1m49s. Database error
RETURN_TITLE_EXACT_QUARANTINE_POSTING at13:32:39.697 UTC: full leg JSON comparison
incorrectly demands equal balance revisions across CUSTOMER and ISP dimensions.
Reserve133 BEFORE creation to omit only per-dimension revision from this equality.
PostingStock.legs intentionally uses each position's own prior revision+1; shared
posting ledger guards retain revision enforcement.132 bytes are now IMMUTABLE.
New5 guard/repair cases are authored but not yet compiled/run.

##132 reserved before creation — independent quarantine title effect

131 is APPLIED AND IMMUTABLE. Its run compiled production/tests, passed all3
ModularityTests and created/replayed the request201. Full return reacquisition
failed at approval request with SOURCE_NOT_VERIFIED: missing approval owner.
Total4 tests/2 suites/1 failure/0 errors/skips,2m8s; return-title-request-green/xml.
Reserve132 to add one independently approved CUSTOMER->ISP quarantine posting,
its immutable return revision link, and exact history/owner/approval validators.
Owner/policy/source-dispatch Kotlin changes are in progress; not verified yet.

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

##131 declared — verified direct-quarantine title request

130 passed10 tests/3 suites/0 failures/errors/skips,2m47s:
all6 RMA guards (renewal, competing distinct permits, same-key installs),3 acceptance
and the complete reacquisition/removal/reissue cycle. Archive
rma-authorization-renewal-green/xml.130 is applied and immutable.

Direct quarantine baseline remains genuine404 (1 test/1 failure,1m28s). Source now
adds a strict public request API and separate RETURN_TITLE document, current return/
asset/WO authority locks, canonical replay and proof from original closed customer
assignment/accepted handover. Reserve131 BEFORE creation to capture and seal this
request without any stock/title effect. Approval owner/policy/effect and return
revision continuation are not implemented yet; the baseline test must continue
beyond request creation only after those later pieces. Do not claim task26 done.

##130 declared — restore current authority without consuming twice

129 passed the full RMA reacquisition/removal/inspection/new-customer issue/install
cycle and3 physical-ledger integrity cases:4 tests/2 suites/0 failures/errors/skips,
2m30s, task26/rma-title-continuity-green/xml. Read-only owner query confirmed
175.127|t,175.128|t,175.129|t; all immutable. Direct quarantine reacquisition baseline
failed1/1 with404 after real SALE recovery and inspection,1m28s; archive
return-reacquisition-red/xml. Its API is still absent.

Corrected scope probe now verifies409 STALE_AUTHORITY and zero writes after
revocation/restoration. It failed at a genuinely NEW mint key after restoration:
IDEMPOTENCY_CONFLICT,1 test/1 failure, archive rma-authorization-renewal-red/xml.
Source125 has UNIQUE(tenant_id,rma_handover_id) on execution, so a stale unconsumed
permit prevents fresh current authority. No raw PostgreSQL error was captured
before that runner removed its owned container; do not claim a raw constraint log.

Reserve130 before creation. Replace mint uniqueness with an immutable per-handover
consumption map, backfilled only from genuine existing execution/result joins.
Map insertion follows real result creation, binds its exact handover/authorization,
and keeps one durable consumption across distinct permits. Existing RMA source,
current authority/custody/revision/result/episode validators stay in force. No new
columns in execution/result, avoiding changes to sealed historical origin shape.
New unverified test races two distinct permits (one201/one409, one result/episode).
No131 declared; direct-quarantine implementation/vendor replacement remain open.

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

##129 declared — historical removal must allow later approved title

127/128 applied in rma-reacquisition-green and are immutable. The original4 shared
failures are now green: both acceptance races and NORMAL/RESTORED draft deletion;
also all5 draft scope cases and ordinary independent title approval passed.
RMA reacquisition passed request creation and approval submission, correctly denied
self-approval, then failed the independent approval decision (SOURCE_NOT_VERIFIED).
Owned PostgreSQL log at2026-09-24 13:07:00.731UTC proves underlying
ASSET_REMOVAL_HISTORY_POSITION_BINDING (function line32), not a permission failure.
Raw private database capture .omo/runtime/rma-title-database.log; current function
.omo/runtime/rma-reacquisition-removal.sql.

The original removed CUSTOMER assignment remains unchanged, but its validator
compares current physical legal_owner with the historic removed asset after a later
RMA episode legitimately acquires ISP title. Reserve129 before creation: only for
continued=true (which already validates accepted recovery and exact APPLIED ledger),
exclude mutable legal_owner from immutable physical identity comparison. Keep source
owner vs original episode, all origin/identity/evidence/legs and current ledger checks.
Add an app-role unposted owner rewrite case; no unapproved title change is authorized.

Full17-test green runner still running (acceptance3 and custody/install guards5
pending at this checkpoint). A separate direct-quarantine reacquisition probe is
queued as return-reacquisition-red.sh/log; missing route expected but NOT YET RUN.
No direct-return API/schema implemented and no130 declared.26 remains open.

##128 declared — RMA title request baseline rejected after real acceptance

rma-reacquisition-red completed2 tests/2 suites/1 failure/0 errors/skips,2m23s.
The fixed simultaneous duplicate acceptance PASSED. The new full RMA reacquisition
probe reached its title-correction POST after genuine RMA install/signed acceptance,
then returned SOURCE_NOT_VERIFIED (expected201). No approval or removal was reached.
Raw XML task26/rma-reacquisition-red/xml. Main/test compilation passed. Current
source checker scope now uses the actual CUSTOMER_INSTALLED location, correcting
the earlier fixture before the later independent approval step is attempted.

Reserve V175_128__warehouse_rma_reacquisition_title.sql BEFORE creation. Actual
current function captured in rma-title-current-functions.sql still assumes each
accepted SALE adds initial titleRevision1; RMA acceptance preserves title0. Branch
only on sealed assignment history purpose, keep original non-RMA behavior. The
consumed RMA authorization currently hardcodes live CUSTOMER and would also reject
any real independently approved owner transfer; require exact episode expected
owner for consumed permits, CUSTOMER before consumption. All existing immutable
source/approval/posting validators remain.127 first application also pending.

##127 declared — shared regression166, four concrete failures

rma-acceptance-green completed166 tests/5 suites/4 failures/0 errors/skips,8m28s.
RMA3, forward-fix3 and provenance31 passed; ownership72 had2 race failures (409
vs200), final-state57 had2 valid draft DELETE failures (count1 vs0). Contrary to
the initial reading, draft deletion fails at the count assertion BEFORE replay.
Read-only current pg_trigger capture .omo/runtime/draft-delete-guards.sql proves
warehouse_transfer_binding_guard always returns NEW even on DELETE, suppressing
otherwise allowed non-transfer draft deletion. Its scope/transfer immutability
checks remain necessary. Reserve V175_127__warehouse_draft_delete_return_row.sql
before creation for conditional RETURN OLD only after those same checks.

Kotlin custody validation was already moved after owner locks and awaits the
focused race rerun. All166 original results reflect pre-fix Kotlin. RMA reacquisition
red runner now starting (two cases); no title migration declared until its result.
All SQL through126 immutable. The fixed retained-pre125 app-role probe succeeded.

##126 retained pre125 evidence validated without rewriting the seal

Read-only `.omo/runtime/verify-retained-rma-origin.sh` passed for the exact tenant/
handover previously rejected by125. The warehouse_app validator now succeeds;
raw stored origin still lacks BOTH new RMA execution fields. MD5 before/after
validation is830f78be6b5c7e982261480deccfc2f1, f/f extension flags; transaction
ROLLBACK. Archive task26/rma-origin-upgrade-green/probe.log. This is not a claimed
pre-migration hash capture: the earlier red probe did not capture a hash.126 SQL
contains no evidence writes. Owner read confirmed175.126|t.

Current Kotlin correction keeps an unchecked immutable custody preview for routing,
then validates the graph after the existing work-order and assignment locks. The
prior race failed in shared regression; its assertion now includes response bodies.
New WarehouseCustomerRmaReacquisitionIT is a real full-cycle probe: repaired RMA,
signed acceptance/title0, independent approval (requester denied), owner transfer,
removal, quarantine, reset inspection, ordinary issue/install to a new customer.
Run `.omo/runtime/rma-reacquisition-red.sh` (queued under host QA lock; two selected
cases including acceptance race). Source changes are pending compilation/results;
no127 declared. Current main shared run still in final-state deployment cases.

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

##126 declared — accepted RMA and stable historical origin comparison

All3 acceptance probes failed as intended after valid RMA installs: signed
handover and normal-key reuse returned MALFORMED_REQUEST; public history failed
KotlinInvalidNullException decoding a RMA source as normal DeploymentSource
(receiptId missing).3 tests/1 suite/3 failures/0 errors/skips,1m31s; archive
`task26/rma-acceptance-red/xml`.

An additional READ-ONLY retained-data probe found a real125 upgrade regression:
pre125 immutable acceptance origin contains to_jsonb(execution) without the new
nullable RMA columns; live generated origin now includes them. App-role validator
rejected a genuine retained pre125 handover with TITLE_ACCEPTANCE_RECONCILIATION_REQUIRED.
Private command `.omo/runtime/probe-rma-origin-upgrade.sh`, log
`.omo/runtime/rma-origin-upgrade-red.log`, fixed probe SQL same stem. Transaction
only reads and rolls back/disconnects; no stored evidence was changed.

Reserve V175_126__warehouse_rma_customer_acceptance.sql BEFORE creation. Add
non-posting signed RMA acceptance with CUSTOMER unchanged, exact witness/origin
seal and original customer. Normalize ONLY absent/null RMA extension columns on
both sides of the historical execution-origin comparison; preserve every sealed
byte and verify retained evidence unchanged after upgrade. All applied SQL through
125 remains immutable. Kotlin source readers distinguish shared custody data from
normal issue source; mint-key conflicts reject before decoding the wrong source.


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

##125 declared — acknowledged RMA deployment

Custody guards passed3/3. Original-customer install baseline failed at authorize
with MALFORMED_REQUEST after a genuine repaired/inspected/received RMA handover.
Combined4 tests/2 suites/1 failure/0 errors/skips,1m30s; archive
`task26/rma-deployment-red/xml`. No physical install was attempted past the failure.

Reserve V175_125__warehouse_customer_rma_deployment.sql BEFORE creation. Preserve
common authorization/result/document/ONU guards; add an explicit handover-backed
execution source, with no material receipt/plan/issue IDs. Capture its real
CUSTOMER physical position and original closed assignment. Serial use stays
historically on the original issue; RMA does not claim another ISP material unit.
Normal issue-backed paths retain their existing receipt/plan bindings. All SQL
through175.124 remains immutable. Current read-only applied function capture is
`.omo/runtime/rma-current-functions.sql` (no data/credentials).

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

## RMA custody124 declared before creation

Reserve V175_124__warehouse_customer_rma_handover.sql for a dedicated document
binding a closed inspected CUSTOMER repair to the original customer and an
assigned REPAIR work order. Separate warehouse dispatch and technician receipt,
immutable source/requests, exact two-leg posting and tenant-scoped final guards.
No fabricated normal ISSUE, stock origin or ISP availability. Installation permit
and original-customer reinstallation follow this custody slice. New baseline
WarehouseCustomerRmaHandoverIT is queued as rma-handover-red.sh/log; pending.


##123 first bootstrap rollback — corrected before first apply

The first green attempt failed before scenarios: PostgreSQL42601 rejected the
reserved alias authorization in123. Flyway explicitly logged19:25:56.313JKT:
"Changes successfully rolled back." Raw XML task26/material-obligation-green/xml.
123 was never applied; alias corrected to permit. No applied SQL edited.
Second run material-obligation-green-second.sh/log (same archive stem), includes
all79 serial/integrity/lifecycle/rework/closure cases. Results pending.


## Serial settlement and complete history —123 declared before creation

V175.122 passed the full material lifecycle/rework/return closure regression:
76 tests in14 suites, zero failures/errors/skips,4m51s. Archive
`task26/return-settlement-green/xml`; owned lifecycle exited0, volumes retained.

The next real baseline ran3 tests in2 suites and failed all3 (zero errors/skips,
1m17s): LOAN/SALE actual installed issue unit reports used0 instead of1, and a
new app-role CLOSE with an empty snapshot set is accepted after valid inspection.
Archive `task26/material-obligation-red/xml`; omission was savepoint-rolled-back.

Reserve `V175_123__warehouse_deployed_material_settlement.sql` BEFORE creation.
Count actual bound APPLIED deployment once against its original issue line;
title handover must not count another use. Seal the complete source-key set for
new lifecycle snapshots at transaction end; preserve historical snapshots and
all existing closure fences. All migrations through175.122 remain immutable.


## Applied122 — core closure passed, shared regression still running

V175.122 applied successfully and is immutable, SHA256 `97b18a1b9ffbd7eac315da93788df8d572575b001373b31700277557b0edd0c4`.
WarehouseReturnSettlementIT passed the real17.5m closure/replay and quantity
preservation case. The complete lifecycle/rework run is still running at
`.omo/runtime/return-settlement-green.sh` (log same stem); do not infer the final
count from this one pass. Archive destination task26/return-settlement-green/xml.

This test checkpoint adds WarehouseMaterialSerialSettlementIT (LOAN/SALE actual
deployment must count one used issue unit before/after handover) and
WarehouseMaterialSettlementIntegrityIT (app-role close cannot omit all historical
material lines after accepted inspection). Both are unverified probes, not proven
bugs yet. Their command `.omo/runtime/material-obligation-red.sh` is queued under
the shared host lock after lifecycle/rework. Log same stem; archive destination
`task26/material-obligation-red/xml`. The omission probe always rolls back its
savepoint, including if the invalid close is accepted. Inspect actual failures
before changing production. No code change sincec40bc0a1; no123 declared/created.

After these results, correct reproduced issues with a new declared forward SQL
version above175.122, then finish task26 replacement/RMA/reacquisition and shared
packaged proof.26 and the whole remaining plan stay open.


## Inspected material closure implementation checkpoint — tests running

Sourceb78489df passed read/access4 tests in4 suites, zero failures/errors/skips,
1m59s: scoped list counts and pages after two real residuals, paged repair history,
return and repair revoked replay. Archive task26/return-reads-green/xml.

WarehouseReturnSettlementIT then reproduced the outstanding bug: after actual
17.5m accepted inspection, expected0 but actual17500;1 failed,0 errors/skips.
Archive task26/return-settlement-red/xml. The earlier DAMAGED inspection and close
rejection passed; the failure occurred at post-acceptance outstanding read.

Declared V175.122 adds a separately derived settled return quantity from exact
acknowledged origin, terminal accepted inspection and matching APPLIED inbound.
Historical returned_base and conservation remain unchanged. Nullable response
settledReturnBase is absent for zero/old payloads. Lifecycle snapshots bind the
settled amount and outstanding; insert/final close checks retain reservations,
accountable quantities and rework-demand fences. Close still posts no stock.
Material summary now reads real CLOSED lifecycle instead of a constant OPEN.

Normal main/test compilation passed. Current command
`.omo/runtime/return-settlement-green.sh`, matching log, archive
`task26/return-settlement-green/xml`, runs new closure plus material lifecycle and
rework suites. Results and first122 applied status pending; inspect logs before
changing SQL. Existing175.121 and earlier stay immutable. Task26 remains OPEN.

Source-audit follow-ups requiring reproduced tests: serial deployment is not yet
included by old warehouse_material_obligation_totals used_base; check real serial
lifecycle totals. Old lifecycle final guard only counts supplied snapshots vs
body lines; test omission of all lines after accepted return before altering it.
Then continue vendor replacement, original-customer RMA, approved reacquisition,
packaged proof and remaining plan. No new feature closure is claimed here.


## Material closure declaration — next step

Reserve V175_122__warehouse_inspected_return_settlement.sql BEFORE creation.
Current source still computes outstanding as accountable+returned even after
accepted inspection. Preserve historical returned quantity/conservation and
add a separately proved settledReturnBase. Snapshot/header and close validators
must use the same sealed inspection evidence. Do not weaken unresolved or
damaged quarantine, reservations or rework-demand close guards.

New WarehouseReturnSettlementIT exercises17.5m acknowledged return, damaged
inspection/denied close, serviceable release, zero outstanding with returned
17500 retained, non-posting close and exact replay. Current baseline command
return-settlement-red.sh/log, archive task26/return-settlement-red/xml; pending.
Read actual failure before claiming reproduced defect. No122 SQL created yet.

## Scoped return list/history implementation — validation pending

Supplier repair access/integrity, ModularityTests, WarehouseContractTest and
MaterialWarehouseContractTest passed24 tests in5 suites with zero failures,
errors/skips,1m32s. Archive task26/repair-guards/xml; lifecycle exited0, volumes
retained. This completes those guard checks, not all of task26.

New read tests initially failed compilation because Jackson JsonNode.map shadows
Kotlin iterable map; using asSequence fixed the fixture. No feature-red claim for
that compile run (its copied XML may be stale). Correct baseline then failed2/2:
list GET405 after two real residual intakes in one tenant; history ignored size2
and returned6 revisions after a real supplier round trip. Archive
`task26/return-reads-red-second/xml`, zero errors/skips.

Current implementation adds SQL-scoped list totals/pagination/filtering using the
existing effective-area/site/location ancestry gates, plus bounded history pages.
No SQL migration added or changed; ceiling175.121 immutable. Normal Kotlin/test
compilation passed. `.omo/runtime/return-reads-green.sh` currently tests these
reads plus prior return and repair access checks; matching log and archive
`task26/return-reads-green/xml`. Final results pending; do not claim success yet.

Next: inspected material return obligation closure, actual vendor replacement,
original-customer sold RMA and approved reacquisition; remaining regressions and
packaged proof.26 remains open; continue whole plan after completion.


## Supplier repair verified — current checkpoint

Corrected first-application121 passed12 tests in6 suites, zero failures/errors/
skips,1m36s. This includes both LOAN/SALE vendor dispatch/receipt, wrong-serial
and premature inspection denial, exact replay, separate post-repair inspection,
unchanged physical identity/customer title, plus all10 prior return scenarios.
Archive task26/repair-green-second/xml. Owned lifecycle exited0 and retained
volumes. V175.121 is NOW APPLIED AND IMMUTABLE, SHA256 `2ffd54f2adb0668687daff40ac43a98ea300d2728b366d35a03d52f0982cb7a4`.
The only in-place change was its parenthesized CASE before any successful apply;
first failure's explicit Flyway rollback evidence is preserved below.

This checkpoint adds two unverified guard scenarios and a shared repair fixture:
WarehouseSupplierRepairAccessIT (revoked scope blocks original JWT/key replay)
and WarehouseSupplierRepairIntegrityIT (vendor reference immutable, fabricated
receipt without physical posting denied; legitimate receipt/inspection closes
case). Current host command `.omo/runtime/return-repair-guards.sh`, matching log,
archive task26/repair-guards/xml. Also runs ModularityTests and WarehouseContractTest.
Read final outcomes. No new production change after12-test success except docs.

Next: physical vendor replacement with verified new receipt; purpose-bound
original-customer RMA handover/acknowledgement/authorization and installation,
independent title reacquisition, bounded lists, material closure and packaged
proof.26 remains open. Declare new migrations above175.121 before creating them.


## Repair bootstrap correction —121 never applied in first run

The first implementation run compiled normally but all12 tests failed at
Spring bootstrap, before feature execution. PostgreSQL42601 at121 line116:
PL/pgSQL needs the CASE expression parenthesized inside the IF condition.
Flyway explicitly logged at19:01:43JKT: migration175.121 failed and "Changes
successfully rolled back." Therefore121 never applied and its syntax is fixed
in place; no earlier applied migration changed. Raw XML retained at
task26/repair-green/xml. Second run: return-repair-green-second.sh/log, archive
task26/repair-green-second/xml. Check actual result and applied status.

## Supplier repair implementation checkpoint — not yet verified

Source3bde6567 passed the complete return suite:10 tests/5 suites/zero failures,
errors or skips in2m37s. Archive task26/position-correction-second/xml. This
includes unposted rewrite rejection23514, atomic delete/rebuild with no movement,
17.5m residual, revoked replay, LOAN/SALE inspection and actual new-customer reuse.
The supplier repair baseline then failed2/2 at missing repair-dispatch route404,
after legitimate LOAN/SALE recovery; archive task26/repair-red/xml.

Current implementation adds repair-dispatch/repair-receive on the linked RETURN,
plus a sealed repair case, vendor custody, same-serial receipt back to quarantine,
immutable original title and separate post-repair inspection. The nullable repair
view field is omitted when absent so earlier persisted JSON stays byte-compatible.
V175.121 was declared before creation; pending first application. No earlier SQL
bytes changed. Read actual runner output before treating121 as unapplied/editable.

Current command `.omo/runtime/return-repair-green.sh` runs supplier repair plus
all10 return tests; matching log; archive task26/repair-green/xml. Compilation
and tests are pending. If a migration has applied, any correction must use a
new declared version. No repair success/whole-task26 closure is claimed here.
Still required: repair access/SQL attacks, supplier physical replacement,
original-customer RMA, independently approved reacquisition, bounded lists,
material obligation closure, full regressions and packaged HTTP.


## Supplier repair declaration and failing-first scenario

Reserve V175_121__warehouse_supplier_repair.sql BEFORE SQL creation.
WarehouseSupplierRepairIT exercises legitimate LOAN/SALE removal and inspection,
vendor outbound custody, exact replay, wrong-device denial, physical receipt
back into quarantine and a separate reset/inspection. Title and old assignment
must survive. `.omo/runtime/return-repair-red.sh` is queued behind the current
return regression; archive task26/repair-red/xml. Read its actual result before
claiming a missing-feature failure. This does not yet test original-customer RMA
delivery or supplier replacement. The draft implementation must not contaminate
the baseline test resources before its compile/runtime snapshot is taken.

## Position correction checkpoint — validation in progress

V175.120 applied, SHA256 `c76419bc97d7fecd225f6f5e9e393f8b71ab92ffa308e3b7488426e736bed9d0`. Never edit its bytes.
The recovered-asset continuation now checks ledger net quantities by full
physical dimension, latest inbound status and the one positive asset position.
Atomic projection deletion/rebuild passed. Initial corrected integrity test
rejected23514 as intended but failed at the outer commit because its expected
exception crossed Hibernate doReturningWork and marked rollback-only. Catching
and rolling back inside the JDBC callback fixes the test, not product behavior.
The second complete return run is queued/running under the shared QA lock:
`.omo/runtime/return-position-correction-second.sh`; matching log; XML archive
`task26/position-correction-second/xml`. Preserve first run separately at
`task26/position-correction/xml`. Inspect full results before claiming success.
No complete task26/repair/RMA claim. Actual new-customer reuse and access checks
already passed separately against prior source; now rerunning with the guard.


## Proven recovery position gap — forward correction declared

At f6331db6, real second-customer reuse passed1/1 with no product change.
Access revocation/restoration with original JWT and replay keys passed1/1.
The app-role integrity probe failed1/1: jointly changing asset and balance to
DAMAGED/QUARANTINE without any posting was accepted after a valid return.
The SAVEPOINT probe rolled back, so the test did not retain altered stock.
Archived XML: task26/red-reuse/xml and task26/access-integrity/xml; no errors/skips.

Reserve V175_120__warehouse_recovered_position_ledger.sql BEFORE creation.
Require returned-asset continuation to reconcile the positive physical position
and all net quantities with immutable APPLIED movement legs, preserving lawful
reuse and projection rebuild. Prior migrations through175.119 are immutable.
Rerun all return suites plus meaningful projection/history regressions after fix.

## Current step — recovery regression complete, reuse/integrity probes

Production checkpoint `6868f1a8` passed179 tests in7 suites, zero failures/errors/
skips,8m28s: CustomerAssetReplacementIT (including inherited guards/races),
CustomerAssetOwnershipIT, CustomerWarehouseProvenanceIT,
WorkOrderMaterialLifecycleITReturns, ModularityTests and WarehouseContractTest.
Ignored full XML: `task26/recovery-regression/xml`. Owned lifecycle exited0 and
removed only its containers/processes, retaining volumes.

This test checkpoint adds unverified next-step scenarios, not product success:
- WarehouseReturnITReuse: legitimate return/reset, new customer and WO,
  reservation/issue/acknowledgement and installation with the same physical ID.
- WarehouseReturnITAccess: original JWT/key replay after revocation/restoration.
- WarehouseReturnITIntegrity: app-role attempt to rewrite asset and balance
  condition without a posting after a valid return. A savepoint always rolls
  the probe back; it must reject with23514. No bypass is assumed proven yet.
- WarehouseReturnAssetFixture reuses the already verified receipt/reset journey.

Current host commands in order under the shared QA lock:
1. `.omo/runtime/return-reuse-red.sh`, log `return-reuse-red.log`, archive
   `task26/red-reuse/xml`; currently running/compiling all new test source.
2. `.omo/runtime/return-access-integrity.sh`, log `return-access-integrity.log`,
   archive `task26/access-integrity/xml`; queued behind reuse,600s bounded lock.
Read actual final outcomes; correct fixture/compile issues separately from
product findings. Do not claim these new cases passed until verified. No
production or SQL change since6868f1a8; ceiling175.119 remains immutable.

After these results, fix proved reuse/current-position issues with declared
forward migrations, then proceed to repair/vendor replacement/RMA/reacquisition,
scoped bounded list, material obligation closure and real packaged proof.26 is open.


## Latest checkpoint — recovered asset receipt and inspection

Published base `62139d01`; this checkpoint adds ASSET_REMOVAL intake with an
atomic open0/physical receipt1, immutable capture of recovery asset/balance and
independent receiving actor. The old removal permits live position changes only
through this sealed receipt, retaining original customer/ONU/title evidence.

Actual LOAN/SALE tests first failed2/2 at unsupported ASSET_REMOVAL intake after
legitimate handover and dismantle. Initial implementation applied175.118 but
failed both asset cases on ambiguous SQL origin; four residual tests passed.
Forward175.119 qualifies the column. Corrected run passed6 tests in2 suites,
zero failures/errors/skips, with exact receipt/inspection replay. Failed reset,
wrong serial and CUSTOMER-to-available release are rejected. LOAN becomes
AVAILABLE/SERVICEABLE/ISP; SALE stays QUARANTINE/SERVICEABLE/CUSTOMER and adds zero
ISP availability. One ended assignment remains. This is release proof, not yet
a complete second installation, supplier repair or original-customer RMA proof.

Applied immutable additions:
-175.118: `1c22a6ebf5eadc6050f45569d9e2b51787123c79da4c163d3346ef14aa8dcdfe`
-175.119: `ecf1bbe749782a18d1ecf910e6f3ec4f0657827d2bfc8db5e70d4bd5d7560db8`

Current host command `.omo/runtime/return-recovery-regression.sh`, stdout log
`.omo/runtime/return-recovery-regression.log`. It runs customer replacement,
ownership, source validation, residual returns, modularity and contract tests.
Archive: ignored `task26/recovery-regression/xml`. Wait for complete counts;
there is no regression PASS claim yet. The previous six-test owned lifecycle
exited0, stopped containers/processes and retained volumes. Full phase XML under
`task26/green-assets-second/xml`.

Next after regression: actual second loan issue/install (V175.83's asset-only
removal lookup needs assignment-specific handling), supplier repair and vendor
replacement, sold RMA original-customer authorization and approved reacquisition;
scoped lists/replay, adversarial SQL/physical continuity, material closure and
packaged HTTP.26 stays open. Declare any new migration above175.119 first.


## Latest checkpoint — verified residual inspection slice

Normal compilation and `qa.sh server --tests '*WarehouseReturnIT' --no-parallel`
passed4 tests, zero failures/errors/skips, in1m16s. Real HTTP/DB proves17.5m
remnant release: available900000->917500 MM, consumed82500 unchanged; original
segment ancestry retained; exact replay; unacknowledged/foreign/wrong origin,
missing permission, overmeasurement and invalid release location rejected;
concurrent inspection yields200/409 and exactly one physical posting.
Raw ignored XML: `task26/green-residual-second/xml`; host log
`.omo/runtime/return-green-second.log`. Lifecycle exited0, cleaned only owned
containers/processes and retained volumes. No packaged HTTP or task closure yet.

175.116 and175.117 applied and are immutable:
- `V175_116__warehouse_return_inspection.sql`: `a43d1b5ebe1ad5f43d7c17dade2757b26c5036c6cc2220cabd02b90756b7c6d1`
- `V175_117__warehouse_return_lifecycle.sql`: `2065110a31cdbcbceb19888db4291230108384e4de1265ad3469a3bb7e1ea1ff`

Next: recovered loan/SALE asset tests and linked receipt, reset evidence, supplier
repair, actual reuse and original-customer RMA, independently approved title
reacquisition; current-scope replay tests, app-role adversarial seals, scoped lists,
material obligation closure and shared regression. Do not mark26 complete.
Declare next migration above175.117 before creation. API currently accepts only
MATERIAL_RESIDUAL; do not claim device recovery or RMA delivery exists yet.


Source base: `af0471bb`; current local branch `work/warehouse-completion`, remote
recovery `feat/warehouse-workorder`. The user authorizes sustained completion
and ordinary checkpoint pushes. Task26 is not complete.

## Migration declaration before creation

Reserved `V175_116__warehouse_return_inspection.sql` above applied175.115.1.
Scope: immutable return source/intake and inspection operation binding, measured
quantities and origin-linked release. No earlier SQL bytes will change. Additive
asset recovery continuation/repair/RMA migrations will be declared separately
before creation and must exceed any already applied version.

## Implementation steps

1. Real residual-return17.5m fixture: legitimate use/return/acknowledgement, new
   inspection intake, measured release and replay. The new HTTP test initially
   targets the absent returns endpoint; run and preserve the failing result.
2. Receipt of recovered asset from its removal record, independent custody and
   history-preserving live-position continuation; loan inspection and reuse.
3. Supplier repair, replacement linkage, sold RMA original-customer authorization
   and independently approved reacquisition. No customer/unknown availability.
4. Scoped bounded reads/history, adversarial tests, packaged HTTP and durable notes.

Findings: existing residual acknowledgement remains its own sealed document at
revision2. New inspection must use a new linked document. Asset removal's current
DB guard pins its asset to recovery forever; replace only its live-position check
with a proven downstream receipt/history chain, retaining original evidence and
customer episode validation. Do not simply relax quarantine constraints.

## Initial implementation status

The first test failed in fixture setup: BIN used `parentId` instead of the API's
`parentLocationId`. This is not feature-red evidence. Corrected the fixture. A
proposed baseline rerun with compilation exclusions was refused by qa.sh before
tests; no runner rule was changed. Proceed with the normal complete compilation
and integration test. No failing-first product claim for this slice.

Implemented initial residual intake/inspection source, immutable origin record,
command/physical posting validation, strict source/quantity/condition handling.
New175.116 SQL has been created but not applied yet. Correct compilation or SQL
errors before any success claim. Asset recovery, repair/RMA, list and material
closure integration remain outstanding.

## First normal build — lifecycle correction

Compilation succeeded and175.116 applied. The test reached return intake and
failed409 because all inventory documents must start DRAFT; the initial new
inspection document incorrectly began RECEIVED_IN_INSPECTION. This is an actual
implementation failure.175.116 is now immutable. Before creation, reserve
`V175_117__warehouse_return_lifecycle.sql`: keep the new linked inspection
document initially DRAFT while its view explains already received quarantine;
allow only source-bound return cases to inspect from DRAFT, and bind the sealed
initial document state to revision0. Preserve every other document lifecycle rule.

## Asset recovery continuation design (not yet implemented)

Published residual checkpoint `62139d01`. New WarehouseReturnITAssets uses actual
LOAN/SALE handover, witnessed dismantle, warehouse receipt and reset checks. Run
`.omo/runtime/return-assets-red.sh` before the asset implementation; preserve its
result, avoiding any missing-route success claim.

Reserve before creation: `V175_118__warehouse_returned_asset_continuity.sql` above
applied175.117. Add ASSET_REMOVAL origin, capture the locked recovery asset/balance
as immutable intake evidence, validate open/receipt/inspection operation chain,
and permit the prior removal's live position to advance only through that
proven receipt. Retain old assignment, title, removal evidence and ONU history.
Do not let receipt flip CUSTOMER title or add ISP availability. Use an internal
open revision0 followed atomically by physical receipt revision1; residual
intake stays non-posting revision0. Avoid recursive removal/return validation.

Follow-ups: V175.83 currently selects recovery by asset alone; repeat removal
cycles will need assignment-specific lookup. Existing title/history validators
use removal's frozen source after assignment ends and must continue doing so.
Stock release is not yet proof of complete reissue/install or sold RMA workflow.

## Asset test result and forward correction

Genuine asset feature red:2/2 tests reached return intake after actual LOAN/SALE
handover and dismantle, then400 because ASSET_REMOVAL was not supported. The
implementation compiled and175.118 applied. Both asset tests then failed on a
PostgreSQL ambiguous `origin` identifier in the new validator; residual tests
still pass. Reserve before creation `V175_119__warehouse_return_asset_origin_scope.sql`
to qualify that table column. Keep applied175.118 unchanged. The failing run is
`return-assets-green.log`, not PASS evidence.
