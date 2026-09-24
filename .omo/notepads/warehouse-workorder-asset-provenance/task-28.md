# Task28 — independent loss, scrap and compensation (in progress)

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
