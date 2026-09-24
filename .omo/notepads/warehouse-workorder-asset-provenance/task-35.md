# Task35 preparation — implementation not started

## Task35 typed issue discovery and paged WO selection — 9 web tests green

Frontend issueModels now decodes current header/revision/unpicked plus actual
accepted/dispatched/picked totals; refuses accepted>dispatched or inconsistentunits.
materials.listIssues uses actual newpagedendpoint. workOrders.ts uses strict small
WO views and legacy PageResponse.content conversion, server query/page25; preserves
nullable customer/assignee names, no first-page truncation.9focused tests passed;
TypeScript exit0 and warehouse API lint passed after defaulting optional decoder
error-path argument. No UI yet. Backend issue-list-initial remainsRUNNING against
e90857a3;3Modularity +1actual partial/fullreceipt case passed sofar. Await fullrun.

IMPORTANT remaining backend mapping before manual/partial reserve UI: current
MaterialLineTotals exposes planLineId but not demandLineId. IDs are DIFFERENT.
Add nullable demandLineId at end of public MaterialLineTotals and populate from
actual demandLine?.id in InventoryMaterialService.summary. Draft maynull; submitted
must match actual persisted demandline. Do NOTderive UUID or assume equalsplanline.
Only one production constructor exists; add defaultnull for compatibility and test
actual query/reserve beforeallocation exists. materialTotals decoder mustpreserve
null/notguess; UI requiremapping for selectedpartial/override. Do after active
backend run completes, keeping current proofsource stable. No migrationsneeded.

After metadata/discovery complete: build shared plan editor, paged WO selection,
request totals/reservation choices, allocations namedmetadata, issue-aware pick/
unpick/dispatch/currentreceiver and printable immutable slip. Browser actualUI
setup+WOplan/reserve/pick/transit; technicianack added40/45. Continuewholeplan.

## Task35 persisted issue discovery implemented — verification pending

Allocation metadata source2671ff3a passed10 tests/4suites in4m9s, zero failure/skips;
portable allocation-metadata-verification.json saved; owned cleanup completed.
Seven typed material/issue web tests +TS/focusedlint passed84591d57.

New GET /api/work-orders/{id}/materials/issues accepts strict page,size,state;
public InventoryIssueApi + existing workflow obtains currentWO/IAM/cutover context.
WarehouseIssueQueries uses current visible_locations before count/paging, requires
all source and actual movement destinations (includingreceipt), and excludes
substituted snapshots withoutoverride. No cost/evidence metadata. Returns header
state/revision/unpicked, latest immutable sender/receiver, createdAt, and per-line
actual picked/dispatched/summed accepted quantities. It does NOT infer physical
transit from dispatched-minus-accepted (loss/exception canclose pendinggoods).
Existing slip endpoint unchanged; receipt advances header beyond sliprevision.
New tests cover2issue cases(paging/unpick/repick/dispatch/scoperevocation/substitution/
permission/tenant/invalidqueries) +1partial/fullreceipt case. NOTYETRUN.

NEXT `.omo/runtime/issue-list-server.sh` against new source:3new tests, existing
serial/replayguards +Modularity. Keep backend source stable. Then actual typed
issue list frontend +request/plan/pick/dispatch/slip UI and realbrowser task35.
No migrations changed. Continue35–48/F1–F4,goalACTIVE.

## Task35 typed material/picking contracts checkpoint

Added materialModels.ts, issueModels.ts and materials.ts using actual source DTOs.
Plan PUT returns MaterialPlanSnapshot; submit-request returns MaterialSummary;
warehouse owner reserve/release returns document/revision/operation/shortage ack;
pick/unpick/dispatch returns immutable IssueSnapshot with named sender/receiver.
Strict decimal strings and requested=unpicked+picked+issued+backorder; no received
quantity fabricated from dispatched or still-accountable. Parent sourceIdentityId
and resulting cut dimension.stockIdentityId remain distinct. Missing plan remains
unplanned, nullable customer preserved, substitution keeps original named snapshot.
Captured commands use existing retry key/body; no new permission bypass.
Seven focused tests + awaited TypeScript exit0 + focused lint passed.

Backend allocation metadata run against2671ff3a stillRUNNING at checkpoint:
3WarehouseIssueITSerial +2WarehouseQueryITCompatibility passed; reservation
compatibility/supplyprojection pending. `.omo/runtime/issue-metadata-fixed.log`,
wrapper issue-metadata-server.sh archives task35/allocation-metadata-fixed. Initial
run1f8003e7 failed only test compilation and stale copied XML must not count.
Do not alter backend source until run completes; save portable proof afterwards.

NEXT implement discoverable paged issue list under material workflow context via
InventoryIssueApi, then request UI. Reuse WarehouseQuerySql visible_locations and
current IAM/location/area/effective-site scope beforepaging; issue.view+request.view;
source document lines and ALL actual issue movement destinations including receipt
mustbe visible; substituted snapshots need override. Add header currentstate/revision
separately from immutable sliprevision, named sender/receiver and line totals from
actual dispatch plus SUM(material_receipt_line.accepted_base), never infer accepted.
Do not label dispatched-minus-accepted as physical transit: issue exception/loss
can close pending goods. Physical transit already comes stock query. List tests must
cover paging/unpick/repick/dispatch, source/transit revocation beforecount/paging,
substitution override revocation, tenant/read permission and partial/full receipt.
MaterialReceiptFixture provides real100m dispatch/60m ack then40m; no mockedwrites.

Existing WO selector API is paged legacy content/page/size/totalElements. Avoid
searchOpenWorkOrders (truncatesfirst50). Reservation ownerroute is needed forwarehouse
operators: outer workflow reserve uses WO edit-context; plan save/submit requires
WOupdate/assign (or assignedfield). Physical picking uses lockForIssue with WOread.
No issue list or request UI yet,task35OPEN. Continue35–48/F1–F4,goalACTIVE.

## Task35 metadata test compilation correction

First metadata check at1f8003e7 failed compileTestKotlin before tests: Jackson3
JsonNode.map chose its member, not Kotlin Iterable.map, so AssertJ list assertion
had the wrong receiver type. Convert through asSequence().map().toList().
Production Kotlin compiled. Do not treat copied stale XML from first attempt as
proof. Rerun archives allocation-metadata-fixed; runtime issue-metadata-fixed.log.
Frontend materialModels.ts is independent UNVERIFIED preparation, not yet committed.
Next await actual backend tests then implement issue discovery/received totals.

## Task35 started — named allocation metadata; verification pending

Task34 complete0e42889a (final source75f8ba41,2browser45.5s,100web,11backend).
ReservationAllocation now adds nullable skuCode/skuName/serial/lotCode/locationName
from actual scoped reservation dimensions and current master metadata. The existing
serial issue regression asserts10 canonical serials and correct names before pick.
No quantity/revision/permission changes; legacy fields retained; no migrations.
NEXT run `.omo/runtime/issue-metadata-server.sh`, then implement scoped persisted
issue discovery/received totals with proper source/transit/receipt destination scope
and current request+issue permissions, before typed request/picking/slip frontend.
Task35 UI/browser notimplemented. Read detailed task-35.md actual API contracts.
Continue35–48/F1–F4. GoalACTIVE;148nextunused,177/178reserved43.

Finish task34 real stock-explorer-compiled browser and visual review first.
Task35 requires demand/picking/issue workbench: requested/reserved/picked/dispatched/
received, partial reserve/backorder, named serial/lot selection, issue-aware unpick,
dispatch, printable immutable slip, substitution review and named receiver.
Browser issue.spec covers warehouse reserve/pick/dispatch and actual transit only;
receiver acknowledgement UI comes40, extended E2E45. Do not fabricate acknowledgement.

Read docs/warehouse-reservations.md, warehouse-issues.md, work-order-materials.md
alongside actual source (some document statements about future tasks are stale).
MaterialWorkflowController /api/work-orders/{id}/materials: GET summary/history;
PUT plan; POST submit-request/reserve/release/pick/unpick/dispatch; GETissues/{id}/slip.
WarehouseReservationController /api/v1/warehouse/material-requests/{demandId}/reserve
supports exact per-line/partial/manual identity override; /allocations/{workOrderId}
returns List<ReservationAllocation>, no page. No requests list endpoint exists yet.
No issue list/workbench endpoint exists: only slip when issue UUID already known.
Need durable discoverable issues after reload; do not use localStorage as authority.

Existing WO GET /api/work-orders?query=...&status=...&page=0&size=20 returns legacy
PageResponse.content, current workorder.order.view, backend scope. Use typed paged
named selection, do not reuse searchOpenWorkOrders (first50 then slice20). Existing
WorkOrderView has names/assignees but no revision; authoritative current revisions
come MaterialSummary.revisions. GET /mine supports page/status, no search.
Warehouse page may select WO then show demand; actual demand setup needs visible
material plan editor (can implement shared now, integrate existing WO in39).

MaterialPlanningContracts and InventoryMaterialApi public DTOs:
MaterialSummary: workOrderId,materialMode,noMaterialReason,revisions{WO,plan,use,
settlementRevision},demandState,installationState,qaState,provisioningState,
settlementState,lines:MaterialLineTotals[],plan?:MaterialPlanSnapshot,
demandDocumentId?,demandRevision?,template?. Plan maynull; do not infer NONE.
Line totals: planLineId,skuId,baseUnit,requestedBase,reservedUnpickedBase,
reservedPickedBase,issuedBase,physicallyUsedBase,returnedBase,transferredOutBase,
disposedBase,stillAccountableBase,backorderBase; all exact decimal strings.
No accepted/received totals in MaterialSummary; cannot infer from issued (transit).
Need actual receipt totals query for received if display, not zero default.
Plan immutable captures code,type,action,customer?,WOrevision,planRevision,mode,
reason,template?,actor,time and lines{id,lineNumber,sku{id,revision,code,name,
tracking,baseUnit},quantityBase,continuousCut,substitution?,originalSku?}.
Plan PUT {expectedRevision:planRev,workOrderRevision,materialMode,reason,lines}:
manual nonempty max100 no duplicateSKU; NONE reason, no lines; null means template.
Substitution requires originalPlanLineId/originalSkuId/reason, override and compatible
tracking/unit, old obligations must released/unpicked before replacing plan.
Submit/reserve/release body {expectedRevision:planRev,workOrderRevision,reason?}.

InventoryReservationApi: reserve body {expectedRevision:demandRev,workOrderRevision,
planRevision,lines?:[{demandLineId,partialQuantityBase?,stockIdentityId?}],reason?}.
Explicit identity needs request.override+reason, automatic uses FIFO. Partial
continuous cut cannot splice remnants. Qty from user exactEA/MM. Allocation list
includes reservationId/revision,documentId/revision,demandLineId,planLineId,planRev,
WOid,stockIdentityId,lotId?,skuId,locationId,originLineId/rev,stockRevision,
unpicked/picked,unit,state,expiry,actor/customer/category,demandSupply totals.
Repeated demandSupply is per-demand-line snapshot, MUST NOT sum per allocation.
Demand conservation requested=unpicked+picked+issued+backorder. No live issue-bound
mutation through reservation pick/unpick/release/extend; use issue-aware methods.

InventoryIssueApi WarehousePickRequest: {expectedRevision:planRev,workOrderRevision,
demandRevision,lines:[{reservationId,expectedRevision:reservationRev,stockIdentityId,
stockRevision,quantityBase,baseUnit,scan?}]} max100 exact distinctidentity/reservation.
Pick returns actual IssueSnapshot (NOT receipt ack): issueId,code,revision1,statePICKED,
WOid/code/revision,customerId/labelSnapshot,demandDocumentId/revision,planId/revision,
sender{id,name},receiver{id,name},lines,recordedAt,destinations[].
Line{id,demandLineId,planLineId,reservationId/reservationRevision,dimension,
sourceIdentityId,quantityBase,baseUnit,sku,serial?,lotCode?,locationName,
substitution?,originalSku?}. Dimension actual stock identity/lot/location/custodian/
condition/legalowner. SourceIdentityId can be parent vs dimension cut child.
Serial pick only encumbrance; cable pick maysplit100m from1000->CUT100+REMNANT900;
unpick leaves physical cut unchanged. Pick advances demand revision via supply.
Transition body {issueId,expectedRevision:issueRev,workOrderRevision,planRevision,
demandRevision,partial:boolean,reason}; returns IssueSnapshotDISPATCHED/UNPICKED.
Read slip gets latest immutable operation body; response same transition bytewise.
Dispatch requires explicitpartial forshortage and active assigned receiver; receiver
chosen server deterministic UUID order from persisted active WO assignees, no
receiver in HTTP body. Review actual receiver snapshot before dispatch.
WO_TRANSIT ACTIVE TRANSIT setup through existingLocationEditor preset as receipt
sourcepattern; no auto-create backend fallback. ActualdispatchwarehouseOUT/transitIN
custodywarehouse statusIN_TRANSIT; no technician receipt implied.

Issue read/slip current issue.view + request.view + currentWOread; allsource and
actual immutable dispatchdestination scope; substituted snapshot requires override.
Mutation issue.manage +request.manage currentauthority +WOread, notWOedit forpick.
Module fulfillment obtains WorkOrderMaterialContext and calls inventory publicAPI;
no inventory dependency on workorder implementation. Reuse existing authorization
for any new discovery/read APIs; paginate/filter under scope before returning IDs,
never bypass destination scope or construct hidden issue details from local cache.
WarehouseIssueStore snapshot/state/print/dispatchDestinations reads real stored data.
MaterialReceiptStore actual receipts carry MaterialReceiptTotal perissueLine:
dispatchedBase,acceptedBase,inTransitBase. Latest receipt has cumulative totals.
Need inspect actual store/policy before implementing a scoped read query.

Task34 changes are isolated; no backend source modifications for35 yet. Keep applied
migrations immutable;148 next unused,177/178reserved43. Tests meaningful plus real
UI desktop/mobile; save each coherent implementation/verification checkpoint and
push feat/warehouse-workorder with handoff+ledger, continue whole plan.
