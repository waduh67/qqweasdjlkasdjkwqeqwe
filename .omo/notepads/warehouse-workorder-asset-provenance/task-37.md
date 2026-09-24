# Task37 discovery notes (task36 verification still pending)

Scope: blind count create/start/observe/submit/recount, independent approval queue
source detail/history/effect, adjustment/disposition forms as owner contracts allow.
Actual separate-login transfer discrepancy browser extends returns.spec.ts.
Do not mark task37 started/done until task36 checkpoint records current checks.

Existing frontend WarehouseRoutes has read-only ApprovalQueue (no detail/action),
counts/adjustments route fallback. Typed approvals.ts only list/basic raw decoder.
Return/replacement/transfer receipt links now persist sourceDocumentId for task37.

Approval backend:
- InventoryApprovalController maps /api/v1/warehouse/approvals AND legacy
  /api/inventory/approvals. Raw get legacy handled separately. Keep compatibility.
- request/decide/rework return direct typed JSON (WarehouseApprovalResponse.body).
  Decision input requestId,expectedRevision,APPROVE|REJECT,reason?,evidenceReference?
  No client tier/movement selection. Actual eligible current tier from decisions
  +WarehouseApprovalAuthority.authorize with current directory/scopes/delegations.
- get runs CONTROL_PLANE/current owner locks/current scopes, may durably EXPIRE.
- list currently iterates store.candidates and checks snapshot locations before
  paging, but does not use sourceLocks/current WO gates that GET uses; review this
  before extending named/filterable discovery. Need bounded strict unknown/repeated
  filters and sourceDocumentId continuation; history currently unbounded array.
- source() locks inventory_document and loads canonical full internal source JSON:
  document,lines,intake plus title/returnTitle/disposition/replacement/count. It
  contains costs/internal evidence storage data. NEVER send raw source JSON to UI.
  Project minimal named allowed document/line/evidence refs, redact cost entirely
  without inventory.cost.view. Public modules for external names/authority, no
  inventory cross-module implementation joins.
- Stored WarehouseApprovalSnapshot has evaluation,source,locations,requesterId,
  code,cutoverEpoch; evaluation includes excludedUserIds, captured policy tiers.
  WarehouseApprovalAuthority.authorize is actual current actor gate; use it to
  derive read-only action availability, command rechecks. Handle current permission
  denial/own requester/used tier/delegated participant clearly, no fake reviewer.
- Requester must equal source.requester. Source detail must read actual current
  source revision and persisted approval(s), not hardcoded0. Rework expects SOURCE
  revision, not approval revision! RETURN_TITLE, replacement receipts, LOSS/SCRAP/
  DISPOSITION_REVERSAL/ASSET_LOSS reject rework: create new owner request with current
  evidence. COUNT rejection enters RECOUNT_REQUIRED instead. Normal rework increments
  source revision and returns {requestId,sourceDocumentId,sourceRevision,status:DRAFT}.
- Approval effectOperationId actual only after final effect. Need typed effect
  presentation by operation; never infer receipt/title/discrepancy from generic200.

Count backend InventoryCountApi /WarehouseCountService:
- create{locationId,partialLocation:true,reason,entries:[{balanceId,counterId}]}.
  1..100 unique actual balanceIds same location; active counter inventory.count.manage.
  Existing raw view OMITs expected quantities and creator/code/names entirely.
- get/list requires count.view and creator OR assigned counter, current location.
- start/submit/recount only creator; observe only assigned entry counter.
- observe requires expectedRevision,balanceId,quantityBase integer string,reason,
  documentReference. Immutable per entry/round; must recount to replace observation.
- submit all entries; dimension changed => durable RECOUNT_REQUIRED+COUNT_STALE409;
  unchanged count autoPOSTED with no physical variance; changed awaits independent
  approval. Recount only RECOUNT_REQUIRED. Review only approval.view and current
  scope AFTER SUBMITTED/APPROVED/POSTED, exposes book quantity/comparison.
- history currently unbounded and scoped to requester/own counter facts. Add bounded
  paged fact history and named metadata/current actor actions without reading normal
  stock quantities in blind count page (including hidden HTTP DTO fields).

Cross-plan note: shared WarehouseSerialLookup currently supports manual/keyboard
only; C8 camera where supported still needs delivery, likely task40 field scan with
reusable primitive. Keep manual fallback and scan alone never mutates. Do not mark
camera support verified today.
