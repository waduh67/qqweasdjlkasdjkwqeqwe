# Task37 discovery notes (task36 verification still pending)

## Task37 blind count reads implemented; backend verification next

Task36 COMPLETE a95c35a0 (product99b98b41/test2c82db3f), proof completion.json,
178web/build/lint plus source-specific backend and actual transfer browser saved.

Task37 now has raw typed count commands/review and approval request/decide/rework
wrappers; TypeScript/oxlint passed. New InventoryCountQueryApi and isolated query
DAO/service/controller provide named /counts/workbench, /{id}/details, submitted-only
/{id}/review/details, /positions and /locations/{locationId}/counters. Position
choices OMIT all physical/book/reserved/capacity/cost quantities, including HTTP.
Zero verified positions remain selectable; identity/status/custody distinguish them.
Count list filters SKU/serial/location/state/code/paired dates; creator or assigned
counter and current topology scopes apply BEFORE page/count. Reviewer-only users
use review owner without count/stock permissions. Public IAM names/current count
permissions+scopes filter counter choices. Raw mutation/views unchanged.
New /{id}/history/page bounds latest facts and own-counter visibility before total;
legacy history now default25/max100 ascending. Internal mutation facts stay complete.

New WarehouseCountWorkbenchIT two HTTP workflows cover no stock permission/quantity
leak, named assignments, scoped paging, reviewer-only submitted comparison, revoked
scope and two counters/history pages. NOT BACKEND VERIFIED YET. NEXT run private
count-workbench-server.sh (new2+existingWarehouseCountIT15 =17 expected/2suites),
await compilation/tests/cleanup and save fresh proof. No migrations; V175_147 current.
UI not built yet; next typed named reads+count screen, approval document/action/cost
projection and saved source links, disposition forms, actual separate-actor browser.
Whole37–48/F1–F4 active. Keep remote checkpoint commits; no merge/deploy.

Approval discovery caution: raw source() contains full internal canonical JSON,
including cost/storage references—never expose raw. Calling owner locks and catching
access exceptions inside a shared transaction may poison rollback-only via mandatory
public WO proxies; use nonthrowing visibility or well-defined transaction boundaries.
Current list only checks snapshot locations; current owner/WO gates must match get.
See task-37.md for further contract notes and task40 camera requirement.

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
