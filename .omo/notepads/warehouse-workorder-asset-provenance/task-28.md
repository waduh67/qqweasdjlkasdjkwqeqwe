# Task28 — independent loss, scrap and compensation (preparation)

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
