## Task41 core customer asset UI + safe portal verified; customer-owned RMA NEXT

All current changes validated: full web304tests/63files PASS69.01s, TypeScript/production
build/new-product oxlint PASS (existing chunk warning and old-page set-state-in-effect
warnings remain). PortalCustomerAssetsIT1 +ModularityTests3 +PortalSelfServiceTest12 =16
PASS / BUILD SUCCESSFUL2m51s. Initial portal fixture failed customer creation because it
omitted required location and code exceeded40chars; fixed fixture, finalall16green.
Read workbench52realtests already PASS at53c646ef. Safe proof task41/customer-asset-core-
verification.json. Owned services cleaned, volumes retained; no migration.

CustomerAssetPanel/Installation/Action in existing customer blade use eligible acknowledged
serials, current WO and assignment/title/episode/signature refs. Two-stage authorize then
consume keeps independent exact command keys/bytes after response loss. Actual canonical
install/replace/remove/handover/ODP relocation. Old free serial/create/delete/attach/detach
controls in OnuManager replaced by observation plus asset actions. Discovered suggestion
opens eligible source review; no direct raw serial provision. Portal /api/portal/me/assets
only uses logged-in principal and whitelisted presentation; no assignment IDs, warehouse
IDs, cost, evidence or actors. Old portal connection schema preserved. Cross-links use
/customers with state.openCustomerId, not nonexistent /customers/:id.39 marked COMPLETE.
40/41 still OPEN for RMA described below; actual mobile/full browser remains45.

NEXT required40/41: warehouse dispatch of repaired CUSTOMER-owned RMA exists in
WarehouseCustomerRma.tsx, but field UI has no own RMA ACK and no reinstall action yet.
Canonical APIs already implemented/proven task26: POST /api/v1/warehouse/rma-handovers/
{id}/acknowledge {expectedRevision,observedSerial,evidenceReference}; authorize on REPAIR
WO with DeploymentIntentRequest {expectedRevision=currentWO,assetId,issueLineId:null,
purpose:RETURN_CUSTOMER_RMA,ownershipMode:SALE,previousAssignmentId:originalAssignment,
repairCaseId}; then normal /api/customers/{id}/assets/install {authorizationId,expectedRevision:
actual permit revision,topology}. Final acceptance uses existing /assets/handover; customer
title stays CUSTOMER, never ISP available. CustomerAssetAction wording already handles
RMA CUSTOMER title without claiming a second transfer.

Suggested implementation next (no RMA edits yet): add bounded self-only RMA reads to
InventoryMyMaterialsApi/MyMaterialsController + direct current reference, expose pending
DISPATCHED and own held RECEIVED. MyMaterialQuery.jobs currently only issues/residuals;
add own RMA jobs so repair-only WO appears. New query joins inventory_rma_handover,
current inventory_document revision + inventory_operation.original_body (typed current
CustomerRmaHandover), filters current actor and all three visible source/transit/field
locations before count, plus actual1EA CUSTOMER SERVICEABLE IN_TRANSIT/ISSUED position.
After reinstall stock no longer ISSUED so source disappears. Reuse WarehouseCustomerRmaService
.details for named work order/people/locations after scoped IDs. It authorizes all three
locations and current WO area via InventoryRmaWorkOrderPort. Receiver ACK requires stored
WO revision still current; reinstallation authorize uses fresh current WO revision.
RmaHandoverStore.create currently leaves document.work_order_code_snapshot null; consider
capturing actual workOrders.read(...).code on new rows and readable legacy fallback rather
than guessing a WO ID. Material Saya context has no customerId except field.plan; RMA
may have no material plan, so add explicit scoped customerId if needed for navigation.
Field own RMA ACK only needs field permission; deployment needs field+customer.onu.assign;
customer read panel also customer.onu.view. Never make CUSTOMER stock selectable as ISP use.

Test fixtures: WarehouseCustomerRmaFixture.prepareRma(),dispatchRma(),receiveRma(),
case.authorization; existing WarehouseCustomerRmaDeploymentIT real replay/conservation;
AcceptanceIT,GuardsIT,HandoverIT cover title/scopes. Workbench query sources currently
only PSB/MIGRATION ISP serials (correct); add separate RMA path/panel, not ownership bypass.
Then finish41 and40, continue42-48/F1-F4. Goal ACTIVE. Migration149 next free,177/178reserved43.
Commit/push often to origin feat/warehouse-workorder; original warehouse-task29 preserved.
No deployment or main merge.

## Task41 customer asset read workbench verified —52 real tests PASS

New CustomerAssetWorkbenchIT4 and existing CustomerAssetReplacementIT48 PASS;
BUILD SUCCESSFUL6m26s, proof task41/customer-asset-read-verification.json. Own bounded
acknowledged SERIAL source picker, actual WO/job/signature/assignment/title/episode
revisions, named origin and old/new replacement chain. Current customer area, field
actor, assigned job and warehouse source location gates. Exact install replay and
sale ownership verified; GET never posts stock. No migration. Owned cleanup complete.

UNCOMMITTED frontend41: customerAssets typed API and two-stage captured authorize/
install-or-replace command; CustomerAssetPanel/Installation/Action/Topology. Existing
customer blade now keeps ONU observation and exposes canonical asset actions; free
serial create/old attach/detach/delete buttons removed there. Discovered inbox opens
eligible source review; no direct suggestion-only provision POST. Links from39/40 use
existing /customers router state openCustomerId (there is no /customers/:id route).
New7webtests PASS3.33s; related20/5 PASS5.20s; TS PASS. Own lint warnings still to fix:
CustomerAssetPanel unnecessary version dependency; Topology exports move to helper.
Existing old-page set-state-in-effect warnings also present. Build not run yet.

Next: fix own lint; add replacement/removal/relocation/readonly/observed-inbox coverage;
portal safe asset presentation and actual principal-only backend proof; build/fullweb;
checkpoint.41 remains OPEN. Browser45 will exercise actual desktop/mobile full customer
chain and portal redaction.43 provides legacy reconcile route currently linked by CTA.
39/40 OPEN pending complete41 verification.42-48/F1-F4 remain, goal ACTIVE.
Migration149 next free,177/178 reserved43. Commit/push often; no deployment or main merge.

