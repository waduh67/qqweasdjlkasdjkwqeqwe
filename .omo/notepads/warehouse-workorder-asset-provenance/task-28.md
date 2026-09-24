# Task28 — independent loss, scrap and compensation (in progress)

## Task28 compensation draft implementation checkpoint — validation pending

The published20-green initial return-disposition evidence is adb4ca79 (product
18447663). Overall task28/whole-plan goal remain open. New work in this checkpoint:
InventoryCompensationApi, typed input/view/context/record, WarehouseCompensationService,
WarehouseCompensationStore and controller. POST/GET/list are under
/api/v1/warehouse/dispositions/{dispositionId}/compensations; GET detail adds/{id}.

Request requires original POSTED1 LOSS/SCRAP, its actual APPLIED movement and
linked latest return revision, whole remaining ISP sink position, no prior linked
compensation, no active assignment/reservation, and an open material lifecycle.
WO is locked through the public port before topology/return/stock. The new
source captures WO/asset/material revisions; destination is quarantine only.
Immutable actor/key replay returns original draft after current access checks.
List authorizes original locations before querying and filters new quarantine
destination scope before pagination; DTOs expose no cost/customer fields.
WarehouseReturnStore.position gains explicit status parameter default QUARANTINE,
with compensation passing LOST/DISPOSED. Existing callers retain Q behavior.

140 was reserved before creation. inventory_compensation_request has forced RLS,
append-only snapshots, actual original effect/ledger/return/material source capture,
and exact DRAFT0 header/line/no-posting seal. DISPOSITION_REVERSAL is admitted as
new document kind. New SQL is not yet known applied: inspect current run before
changing it;139 and older remain immutable. No compensation approval owner or
physical effect is implemented yet; 141 or later must extend lifecycle forward.

Current .omo/runtime/compensation-request.sh / .log selects WarehouseCompensationIT
(2 real LOSS/SCRAP reversal journeys) + ModularityTests (3). Archive is
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-request/xml,
database log compensation-request-database.log. Expect new request to progress
to approval/request missing owner; do not call these5 tests green without logs.
No changes to main/deploy; checkpoints push HEAD:refs/heads/feat/warehouse-workorder.

Next effect: add ApprovalPostingKind.DISPOSITION_REVERSAL, map policy to ADJUSTMENT
without taking existing ADJUSTMENT owner from transfer discrepancy. Seal approval
source with compensation record; revalidate current sink and material revision
before decision and posting. One new REVERSAL movement compensates original ID,
restores QUARANTINE/QUARANTINE at target, preserves owner/quantity and original
posting. Exactly one explicit matching outbox kind; update both PostingDocuments
default kind and WarehouseApprovalStore.event selection. Add linked nonphysical
warehouse.return.restore step, original return history validators, new effect
capture/exact deferred guards and old/new table routing. Original139 effect already
allows later return revision and uses generic current ledger reconciliation.
Reject closed settlement, duplicate reversal, downstream reused/installed/consumed
state. Reinspection after restoration must be required before availability and
returned residual re-settlement. Task28 also needs broader loss scope assessment.

## Task28 initial return LOSS/SCRAP VERIFIED — compensation remains open

The disposition-verified run against18447663 product source completed20 tests/4
suites,0 failures/errors/skips,2m42s. All2 residual LOSS/SCRAP settlement journeys,
11 source/permission/concurrency/replay/integrity guards,4 serialized LOAN/SALE
cases, and3 modularity checks passed. Owned QA cleanup completed with volumes
retained. Portable sanitized evidence is committed at
.omo/evidence/warehouse-workorder-asset-provenance/task28/return-disposition-verification.json.
It contains every test name/count, XML digest, product main-tree identity and
138/139 checksums; raw private XML/logs remain excluded from commits.

Verified behavior: independent posting moves the exact quantity once, preserves
original returned quantity and old customer assignment history, and closes the
returned residual obligation without new physical postings on WO settlement.
Customer title, bad quantities, unknown cost, currency mismatch, direct SQL fake
effects, requester delegation, revoked scope and stale sources reject correctly.
Competing approvals produce one effect; both MM and serialized projections rebuild.
All10 old return/approval regressions also passed in preceding28-case mixed run.

Task28 is still OPEN. WarehouseCompensationIT has2 authored but unexecuted cases;
its proposed endpoint is not implemented. Next: a new DISPOSITION_REVERSAL source
kind with ADJUSTMENT policy, actual original movement linkage, independent approval,
current disposed position and closed-material lifecycle checks, and one paired
REVERSAL restoring QUARANTINE plus a nonphysical return-history step. Preserve
original posting; reject duplicate/reused/installed/consumed rollback. Broader
vendor/outstanding loss paths still need assessment.140 is available but NOT yet
reserved/created.138 and139 remain immutable. Overall goal continues beyond28.

## Task28 exact outbox/error contract checkpoint — 20-case rerun pending

The disposition-guards combined run against dac2434b completed28 tests/8 suites,
12 failures,0 errors/skips,6m18s. All10 return/delegation/expiry regressions and
3 modularity checks passed. New disposition source-stale, unknown-cost and scoped
paging checks passed. Failed positive posts raised
DISPOSITION_EXACT_APPROVED_POSTING_REQUIRED because PostingDocuments generated a
default DISPATCHED event in addition to supplied DISPOSED. Its new LOSS/SCRAP
mapping now derives DISPOSED, retaining the existing exactly-one-event DB guard.
No139 SQL changes;139 remains applied immutable.

Customer-owner and invalid-quantity requests were rejected in the service but
escaped as ServletException: the new controller was missing from WarehouseHttpErrors
assignableTypes. Added it, preserving existing error/status contracts. The new
delegation test failed while creating the grant (INDEPENDENT_APPROVER_REQUIRED):
its delegator was not configured in policy. Corrected fixture policy to include
requester + independent checker before requesting approval, then delegates after
request to test actual decision-time requester exclusion.

Added2 source-control guards (USD vs IDR policy and raw SQL pending-approval effect)
and docs/warehouse-dispositions.md with current supported workflow/limits. Current
.omo/runtime/disposition-verified.sh / .log selects only WarehouseDisposition*IT
and ModularityTests: expected20 tests (2 residual,11 guards,4 asset,3 modularity).
Archive task28/disposition-verified/xml, DB log disposition-verified-database.log.
Results pending; do not claim committed disposal until this run passes.

WarehouseCompensationIT is separately authored (2 LOSS/SCRAP cases) and NOT in that
run. It expects POST /dispositions/{id}/compensations, then independent ADJUSTMENT
approval of a new document, one REVERSAL linked to the original movement, restored
QUARANTINE, outstanding17.5m until fresh inspection, original history unchanged,
replay/duplicate protection. No compensation implementation or140 migration exists.
Use140 onward for new SQL; reserve before creation. Closed-settlement and reused
asset reversal guards still needed. Task28 remains OPEN and whole-plan goal active.

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

Task26 combined regression is running against compiled c050efeb product source;
do not mark it complete before its nonzero result. Current task28 work is only
the WarehouseDispositionIT behavioral test. No disposition service, endpoint or
new migration has been implemented. No task28 test has passed yet.

The first scenario uses the existing real MaterialLifecycleFixture: receive1km,
issue100m, use82.5m, return and acknowledge17.5m, inspect DAMAGED. It requests a
SCRAP document, rejects requester self-approval, accepts an independent scoped
checker, and expects17.5m DISPOSED/SCRAP,900m AVAILABLE,82.5m CONSUMED. The original
returned quantity stays17.5m; settledReturnBase becomes17.5m. WO closure must add
no physical posting. No fake stock/source fixtures or generic SQL balance setup.

Proposed request route: POST /api/v1/warehouse/dispositions, Idempotency-Key.
Input: sourceDocumentId, expectedRevision (source), stockIdentityId, quantityBase,
baseUnit, destinationLocationId, action, reason, evidenceReference. The test uses
action SCRAP and sourceDocumentId pointing to the inspected RETURN. Result201
contains id of a new DRAFT0 document; use existing approvals/request and decide.
The command must not move stock until approval. Rejection requires a new request
with current evidence; do not edit an immutable source snapshot.

Implementation constraints and reusable pieces:

- Use Inventory-owned API, service and persistence; cross-owner WO locking goes
  through AssetHandoverWorkOrderPort.lockTitle or a suitable inventory-owned port.
  Resolve WO from inventory_material_residual or inventory_asset_removal; RETURN
  headers currently do not populate work_order_id. Take WO before topology and
  inventory document/asset/stock locks. Use current authority before replay.
- WarehouseApprovalOwner has one owner per kind. Existing ADJUSTMENT belongs to
  WarehouseTransferApprovalOwner; do not steal or duplicate that kind. LOSS and
  SCRAP already map to corresponding policies in WarehousePolicySource. Extend
  ApprovalPostingKind and DurableApprovalService's business action mapping for
  concrete new owners. Any reversal kind must map to ADJUSTMENT policy while
  retaining its original disposition movement link.
- WarehouseTransferStock derives actual original receipt/lot cost and segment
  identity. WarehouseReturnStore.position includes balance and segment revisions.
  Lock/check exact current dimensions again before posting; preserve unknown cost
  instead of inventing0. Exception policies require an independent first tier.
- Customer and unknown title cannot be disposed as ISP property. For the initial
  return path, require legalOwner ISP; explicit separately approved reacquisition
  already exists for customer returns. Never use an old sale signature as inferred
  consent to destroy property. Same-asset loan recovery history remains intact.
- Use one real paired posting to LOST or DISPOSED sink, preserving total quantity;
  SCRAP changes condition to SCRAP. Do not debit arbitrary negative stock. For
  returned residuals, dispose the exact measured segment. Broader stock/issue loss
  must preserve split lineage, allocations, customer assignments and obligations.
- New request/effect tables must have RLS, immutable source/current capture and
  deferred exact posting/approval/operation/outbox bindings. Reserve the next SQL
  version in docs/warehouse-migrations.md BEFORE creation. V175.137 is applied and
  immutable. V177/V178 remain reserved for43; never reset QA volumes or old SQL.
- Return history is strongly validated. Extend warehouse_assert_return and
  warehouse_assert_returned_asset with a narrowly validated new nonphysical
  return transition linked to the approval's physical disposition effect; reuse
  the pattern in132 warehouse.return.reacquire, with its own exact assertion.
  Preserve all older source/inspection/repair/title checks. Add LOST to public
  ReturnState if needed and final state checks, never reinterpret it as ACCEPTED.
- warehouse_material_settled_return_base (122) currently counts only exact
  ACCEPTED inspection postings. Extend it for exact independently approved return
  disposition, counting each acknowledged residual once. MaterialLifecycleStore
  and warehouse_material_obligation_totals (123) still hardcode disposed0 for
  direct issued-stock loss; update when supporting that path. Do not subtract a
  returned-and-later-disposed quantity twice in MaterialPhysicalTotalsStore.
- Compensation must be a new document tied to the original APPLIED movement.
  Require its exact remaining source position, prevent active/consumed/reused asset
  rollback and duplicate reversal, restore only to quarantine, and keep a closed
  WO's obligations consistent. Do not implement generic ledger edits.

Remaining task28 tests: scoped current replay, requester/delegate exclusion,
unknown/mixed-currency cost, competing decisions/source change, overquantity,
customer title refusal, direct database fake effect, restart/rebuild, real loss,
and compensation including already reused/installed asset rejection. Only mark
28 complete after its actual command/effect/obligation paths and checks pass.
