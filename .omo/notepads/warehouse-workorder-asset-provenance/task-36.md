# Task36 — transfer / return / repair UI investigation

## Task36 original assignment references added; verification next

RMA/ownership investigation found source workOrderId is the REMOVAL work order,
while replacement/reacquisition owner uses the ORIGINAL assignment work order.
Added nullable references.assetOrigin {assignmentId,customerId,workOrderId} from
persisted local inventory lineage, separate from source workOrderId. No new name
snapshot or cross-module implementation join. Extended LOAN/SALE and remnant
HTTP tests to distinguish these IDs. Frontend decoder and fixtures updated.
NEXT run return-asset-context-server.sh (6 tests /4 suites expected), then continue
replacement form/list, RMA named customer repair WO/current revision, signed
original-WO evidence and reacquisition continuation. Changes not yet verified.
Previous11backend @d1395e7b and17web @33200083 remain baseline. No migrations.

## Task36 return workbench checkpoint — 11 backend and 17 web tests passed

Return discovery backend @d1395e7b passed 11 tests / 7 suites in 4m56s. Fresh
portable return-discovery-verification.json saved; owned cleanup completed and
volumes retained. Scope, source eligibility, no double receipt, LOAN/SALE,
6-revision repair history and persisted RMA continuation were verified.

New typed return/source/repair/replacement/reacquisition/RMA contracts plus exact
intake/inspection/repair builders. Return page now has scoped named list and
filters (including paired dates), source lookup, intake, measured whole remnant,
serial/reset inspection, same-serial vendor dispatch/receipt, ownership warning,
old inspection vs post-repair reset warning, history and permission reasons.
Actual writes capture one body/key, then reload GET;409 reload discards old form.
Existing handoverId disables duplicate warehouse inspection.17 affected web tests
(9 page /4 API /4 helper) passed3.66s; TypeScript and focused oxlint exit0.
No real return browser claim; task36 transfer browser remains verified @49e8a1d4.

NEXT implement supplier replacement receipt form and persisted continuation list,
customer RMA dispatch/actual handover read and signed-evidence reacquisition link
flow. These APIs exist but page actions are not built yet. RMA needs original
customer context and named eligible repair WO/technician selection, not free UUID.
Check source document customerId metadata and public work-order/evidence reads.
Task37 approval request/decision deep links still to build. Transfer C8 list
SKU/serial/date filters remain to review before marking36complete. Task36 OPEN;
whole36–48/F1–F4 continues, no migrations changed. No active QA processes.

## Task36 return discovery implemented; backend verification next

Added separate named /returns/workbench and /{id}/details, eligible /sources,
and bounded latest-first /{id}/history/page. Raw GET/list/operation/history shapes
stay unchanged; legacy history now defaults25/max100. Strict serial/query/date/
origin/state/location/SKU/identity/owner filters. Sources require return.manage,
actual verified whole Q position, residual acknowledgement or independent asset
removal, and exclude previously intaken documents BEFORE paging/count. Read only.
Named metadata uses current inventory names and public IamApi receiver name.
Minimal rmaHandoverId points to existing handover owner read; no invented current
physical state from the old return snapshot. Historical repair location now gates
ordinary return read/replay/history/list, consistent with repair actions.

New source/scope/names/immutable snapshot integration case plus extended asset
LOAN/SALE independent sources, bounded6revision history, repaired-location scope
and persisted RMA reference checks. NOT VERIFIED YET. NEXT run private
return-discovery-server.sh, await compile/test/cleanup, inspect fresh results.
Transfer browser proof @49e8a1d4 complete174c6545.149 web/7transfer backend baseline
unchanged. Return UI still required; task36 remains open. No migrations.

## Task36 transfer browser verified at49e8a1d4; return implementation next

Final transfer-final passed both projects in 38.005s, no failures,
skips, flaky or global errors. All10 current synthetic PNG reviewed; mobile label
now readable. Saved screenshots and portable transfer-browser-verification.json.
Owned cleanup completed and volumes retained.149 web tests and7 transfer backend
baseline remain green (source-specific workbench proof). Task36 NOT complete:
next source lookup, named return reads, inspection/repair/replacement/RMA UI.
No migrations changed; keep36–48/F1–F4 active and push coherent checkpoints.

## Task36 transfer browser passed; final mobile label check next

Actual transfer-initial browser @5305ce67 passed both desktop/mobile in39.501s,
0 failures/skips/flaky/global errors; owned cleanup completed, volumes retained.
Reviewed all10 synthetic PNG: real100m intake, draft, dispatch100m, receive60m,
40m transit0available and60m destination available. One mobile label truncated;
shortened resolved header to Diselesaikan while existing explanation distinguishes
independent resolution from receipt. NEXT rerun transfer-final, review/save10PNG
and portable proof. Task36 stays open for return/repair UI.149web and7backend
baseline remains valid; no migrations. Current source and return investigation
saved for recovery; keep whole36–48/F1–final active.

## Task36 source10087229 —149 web tests and7 backend tests passed; browser next

Full warehouse/API/pages/components plusDataTable/WO/Payment/MultiCombobox:
149tests/22files passed16.96s. Includes9Transferpage cases and6API/helpercases;
affected15tests+TypeScript+focusedlint passed previously. Server transfer-history
7tests/3suites passed, cleanupcompleted, proof in task36/workbench-verification.json.
NineUIcases cover scoped denied links, namedpagedlist, actualsourceBalanceId draft
and ownrecipient withoutIAMdirectory, bounddispatch, actual60/40partialreceipt,
409reloadnoblindretry, actor/ownership/resolvednotreceipt, discrepancyawaitsapproval,
and serverhistorypaging. New browser returns.spec.ts builds real100mreceipt and
checks draft/dispatch/60received40transit and60available via visibleUI only.
NEXT run warehouse-transfer-browser.sh transfer-initial, await bothdesktop/mobile,
review all10PNG, fixifneeded. No otherQA processes running. Task36 stillopen:
returnsource lookup+inspection/repair/replacement/RMA UI required next;37approvals
links have sourceDocumentId query to implement. Keepwhole36–48/F1–F4 active.
No migrations;149webgreen baseline will support relatedreturnUI checks.

## Task36 Transfer page checkpoint; history verification next

Transfer discovery3newHTTPcases passed @6af0af41,1m46s, alongside18existingcases
passed @496d1d85. Portable task36/discovery-verification.json records per-suite
hashes/source (21distinct, not one21testgreenbatch). Cleanup completed.

Implemented actual Transfer route/list/detail/create, named locations/receiver,
source position+exactqty+lot read, dispatch, partialreceive, fullremainderdiscrepancy,
permission/actor reasons, currentquantity table and saved history. Inputs capture
sourceBalanceId and server-boundreceiver; read fresh GET after command. Customer
ownership warning; nocancel or fakeavailable/receipt. Ownuser can be selected
withoutIAMdirectory permission. Approval deep link needs task37 destination flow.
Build+6helper/APItests+TS passed; newFastRefreshwarnings fixed by separate helper,
finalTS/oxlint passed. Page behavior tests and actual browser NOTRUNYET.

C8 review found oldtransferhistory unbounded. Added bounded legacyarray (default25,
max100, oldascendingorder) and /{id}/history/page typed server count/latest-first;
strict pageparams and currenttargetscope. Added true3operation paging/assertions
and denied newhistory path tointegrationcase. Backend history changes UNVERIFIED:
NEXT run transfer-history (ListIT+Contract+basicTransferIT), then page tests and
actual100/60/40 desktop/mobile. Return source/inspection/repair/replacement still
needed before36complete. Newdocs describe history compatibility explicitly.
No migrations; whole36–48/F1–F4 active. Commit/push everycoherentcheckpoint.

## Task36 typed transfer commands green; scope assertion correction

Server @496d1d85 compiled and ran21tests:19passed,2failed only because new scope
assertions expected403 while existing WarehouseMasterService intentionallyreturns
404 NOT_FOUND for hidden locations/areas. Confirmed source lines133/154, corrected
those assertions only (missing permission still403). All18existingtransfer cases
passed, plusnewinactive-receiver/current-name/immutable-response case. Cleanupdone.
Rerun3newdiscoverycases to reach remaining scope-restoration checks; production
backend unchanged from496d1d85. No stale XML used as proof.

Web typed transfers/read wrapper/captured commands and exact draft/partialreceipt
builders added.6meaningful cases+TypeScript+focusedoxlint passed. CoversDRAFTzero,
60/40 transit vsresolved conservation, referencebinding, response-loss samebytes/key,
sourceBalanceId, allocated/wrongcustodian/overreceipt, distinctlocationsandreceiver.
No Transfer page yet. NEXT backend3case outcome; build actual Transfer page/editor,
then return-source discovery/inspection/repair and desktop/mobile100/60/40 journey.
Task35complete;36–48/F1–F4 active;no migrations.

## Task36 transfer discovery compilation correction

Initial server run @7fa9af4c failed production compile: validatePage was private
to return service, not shared. Replaced with explicit page>=0,size1..100 guard.
No tests executed; copied stale XML is not evidence. Cleanup completed.
NEXT rerun transfer-discovery-fixed with the same new3cases+transfer regressions.

## Task36 transfer discovery implementation — backend verification next

Added InventoryTransferQueryApi list/details wrapper {transfer,references}; old
GET/mutation/history responses untouched. Query scopes source/transit/destination,
resolution target and movement locations before count/page. Active/technician
receiver eligibility comes via public IamApi before pagination; names also via
public IAM. Local SKU/serial/lot/location refs are current, no cost/email.
Strict query keys/repetitions/blank/page/state/location validation. Existing
get/history/replay/approval source access now includes resolution target.
3 new HTTP integration cases cover actual draft/partial60/40, scope revocation,
independent denial, inactive receiver, names/old snapshot compatibility and strict
filters. NOT VERIFIED YET. Next run transfer-discovery server wrapper plus all
WarehouseTransferIT* and contract regressions; no browser running.
Task35 complete75ad8b43/source31b26399. No migrations. Remaining36–48/F1–F4 active.
After backend: typed Transfer UI and actual100/60/40 browser, return source lookup,
inspection/repair/replacement/RMA UI and role/conflict tests before36complete.

Do not start implementation until task35 actual issue browser and proof complete.
Initial read during task35 browser, production code unchanged. Read actual source
alongside docs/warehouse-transfers.md and docs/warehouse-returns.md.

## Transfer

InventoryTransferApi / WarehouseTransferController root /api/v1/warehouse/transfers:
POST create,/{id}/dispatch,/{id}/receive,/{id}/discrepancy;GET/{id},/{id}/history.
NO LIST endpoint yet; add current scoped paged discovery beforebuildinglistUI.
All create/dispatch/receive mutations return actual WarehouseTransferView directly
(original stored response), not generic ack. GETcurrent is record.view(), history
plainList<WarehouseTransferView> (currentlyunpaged).

Draft input sourceLocationId,destinationLocationId,transitLocationId,receiverId,
reason,lines[{stockIdentityId,quantityBase,baseUnit,sourceBalanceId?}]. Use actual
position.id as sourceBalanceId to disambiguate sameidentitymultiplebuckets.
Draft doesnotmove/reservestock. Source/dest supported WAREHOUSE/BIN/VEHICLE/
TECHNICIAN/QUARANTINE;transitdistinctTRANSIT,!issueEligible,notRECEIPT_SOURCE.
receiveractive IAM, destinationTECHNICIAN/VEHICLEcustodian mustreceiver;
TECHNICIANreceiver musttechnician. Existing WarehouseTransferAccess.authorize
checks topology/currentwarehouse/area/effectivesite on all3locationsandactive
receiver (alsoGET/history). No blankettenantadminbypass.
Dispatch body expectedRevision, onlypersistedsender;receive bodyexpectedRevision,
evidenceReference,lines[{lineId,quantityBase,baseUnit}],onlypersistedreceiver.
View rawIDs currently no names: id/code/revision/state,3locationIDs,sender/receiverIDs,
reason,recordedAt,lines{id,skuId,stockIdentityId,unit,quantityBase,receivedBase,
inTransitBase,remainingIdentityId?,condition,legalOwner,resolvedBase},resolutionDoc?.
Need actual scoped namedmetadata withoutrewriting immutable oldsnapshot or guessing
names/serial/lineage; newGET additivecurrentmetadata or properpublicreferences.
CapturepayloadandIDkey, reloadactualGETafterwrites asusual.
Cancel endpoint exists but ALWAYS throwsSOURCE_NOT_VERIFIED (includingdraft).
Do not exposecancel asviableaction or callitundo. No drafteditAPI either.
Discrepancy bodyexpectedRevision,actionLOST|REJECTED,destinationLocationId,reason,
evidenceReference;createsresolutionDocumentId, stillnomovement untilindependent
ADJUSTMENTapproval. Sender/receiver/delegates ineligibleapproval. Task37decisionUI.

WarehouseTransferStore keeps latestTransferRecord in inventory_command_identity
canonical_payload, latest original_body is view. inventory_document has3location
columns+receiver+actor andkindTRANSFER;filteractualwarehouse.transfer namespace
becauseothermaterialflowsalsouseTRANSFER. Access-beforecount/page neededforlist.
Queries shouldalsoconsideractualresolutiondestinationsscope (reviewserviceexisting
getauthorizesoriginal3only, verifyfinalscopecontract beforeaddinglist).

## Return / repair

InventoryReturnApi root /returns alreadyGETpagedlist andGETdetail/history.
Listfilters page,size(1..100 default25),originMATERIAL_RESIDUAL|ASSET_REMOVAL,
state,locationId,skuId,stockIdentityId,owner. Scopedbeforepaging (initialquarantine
andcurrentlocationACTIVE). Latestviewfrominventory_operation.original_body.
Historyquerypage/size default100, returnplainList; inspectactualcontrollerforcount
or addproperpage contract ifneeded. Currentview no names:
id/revision/state/origin/sourceDocumentId/stockIdentityId/skuId/lotId?,baseUnit,
quantityBase,locationId,condition,legalOwner,receivedBy,recordedAt,inspection?,repair?.
Requiresreturn.view;mutationsreturn.manage, currentIAM/initial/currentlocations.

Intake{origin,sourceDocumentId,quarantineLocationId,evidenceReference}.
Need namedsource lookup (nonefoundyet), notfreeUUIDonly.
MATERIAL_RESIDUAL requirespurposeRETURN+acknowledged+targetLocation==quarantine;
physicalstockalreadyreceivedatack, intake MUSTNOTdebit/receiveagain.
ASSET_REMOVAL requiresvalidoutcome, receivingactordifferentremover, actual1EA
stockidentitymatchesasset; sourcecurrentlocscope andoriginlegalOwnerpreserved.

Inspect{expectedRevision,measuredQuantityBase,condition,destinationLocationId,
evidenceReference,resetConfirmed,observedSerial?,resetEvidenceReference?}.
Cannotinventquantity/joinpieces; exactMM/EA. Serialreset+proof requiredforserviceable,
CUSTOMERtitle staysQ/unavailable evenserviceable; ISPmaygotoissueeligibleBIN.
DAMAGED/Q staysQ, scrap usesdispositionapproval. Sameoriginhistoricalepisodekept.

Repairdispatch{expectedRevision,vendorId,repairLocationIdTRANSIT,vendorReference,
evidenceReference,observedSerial};receive{expectedRevision,observedSerial,
quarantineLocationId,resultREPAIRED|UNREPAIRED,vendorReference,evidenceReference}.
Sameactualserialonly;receive returnsQ requiresnewinspect/reset. Onecaseperreturn.
repairprogress{id,vendorId,vendorReference,repairLocationId,dispatchRevision,
returnedRevision?,result?,receiptReference?}.

Replacement-receipts and reacquisition andcustomerRMA handover APIs alreadybackend,
see docs forinputandpermissions. Need UI continuationlinks/dialogs appropriate36,
not all delegatedtocatchallfuture;task40/41actualtechnicianandassetUI required45.
Replacementdifferentserial=NEWreceipt provenance, nooldassetdestruction;CUSTOMERtitle
followsreplacement andneverISPavailable. Alihtitleapprovalindependentlater37.
Task36 actualbrowser minimumwarehouse-to-warehouse100m/60mpartial40mtransit;
fulltechreturn/soldRMA deferredactual45after40/41 perplan. No fakeacknowledgement.

Next after35complete: implementdiscovery/namedmetadata andmeaningfulscopebackend
regressions (keepmigrationsimmutable148nextunused177/178reserved43), thenactual
Transfer/Return/RepairUI typedcontracts +unit+realbrowserdesktop/mobile. Commit/push
handoff frequently, goalwholeplan35–48/F1–F4 remainsactive.

## Read review during final task35 browser (no task36 implementation yet)

Transfer discovery design: preserve raw mutation/GET/history immutable view contract.
Add paged list and /{id}/details with wrapper {transfer,references}; reference names
are explicitly current, not invented historical snapshots or new TransferStock fields.
Query local inventory metadata after access, resolve people through public IamApi.
Stock source equality includes TransferStock so never add cosmetic labels there.

List must scope all source/transit/destination and discrepancy target before count
and page, use WarehouseQuerySql visible_locations (area/effective site/active).
Also match existing Access topology and active receiver; technician destinations
require active technician and bound custodian. Candidate receiver IDs from scoped
records -> IamApi.usersByIds -> active/technician map -> SQL filter BEFORE paging.
Do not join IAM implementation tables or filter unauthorized results after LIMIT.
Stable recordedAt/id ordering, strict unknown/repeated/blank query rejection.
Existing get/history only authorize original3, so extend record access to actual
resolution destination as well; reuse same gate for details/replays. Resolution
store reads its requested target. Keep cost/secret data out of discovery DTOs.
Meaningful IT: two drafts different destinations, page count/filter, revoked
source/destination/transit/target and permission, othertenant, inactive receiver,
partial60/40 exact ledger, names from current refs and unchanged old response.

Return names/source lookup should likewise use separate read wrapper, not rewrite
old WarehouseReturnView JSON. Existing return list may be reused with detail wrapper;
if list names are needed add distinct /workbench list or enhance documented read.
Returns owner source validation: MATERIAL_RESIDUAL purpose RETURN and persisted
acknowledged, current whole quantity in targetQ with WAREHOUSE custody. ASSET_REMOVAL
actual validated removal, different receiving actor, current 1EA TRANSIT recovery
position matching asset ID and legal owner. Exclude already-intaken source episodes.
Use actual named document/SKU/serial/location lookup, never free UUID-only command.
Stock not received twice for acknowledged residual. New source query must not claim
eligibility if current whole stock/source/title is no longer valid. Mutation remains
last authority and conflicts force re-read/review. No migration needed for queries.

## Return implementation decisions after transfer proof

Use separate InventoryReturnQueryApi /workbench and /{id}/details wrappers;
raw GET/mutation bodies stay immutable. /sources offers eligible named document
sources and measured whole stock (not free UUID). /{id}/history/page supplies
bounded latest-first history/count, keep legacy array ascending default25/max100.
Add serial/query/date filters with strict unknown/repeated/blank rejection.
Source candidates: acknowledged MATERIAL_RESIDUAL purpose RETURN at its bound Q,
whole exact quantity and actual verified Q/WAREHOUSE position. Never receive twice.
ASSET_REMOVAL: different receiver actor, actual verified 1EA SERIAL recovery at
remover TRANSIT custody, owner preserved. Exclude already-intaken source episodes.
Owner still validates source functions on mutation; do not turn void assertions
into boolean read filters. Later migrations patch these functions; initial SQL
is not their final definition. No migration needed for these queries.

Named read refs are current names, not operation snapshots. Keep receivedBy names
via public IamApi. Repair location must be authorized on list/detail/history even
after it returns to Q, matching existing repair action authorization. Return state
and location describe the return operation: customer RMA creates a separate doc
without updating return view. Include a minimal persisted handoverId continuation
reference; do not claim old Q view is current physical stock or offer duplicate
handover. Full RMA read continues through existing authority/WO checks.

Repair: ASSET_REMOVAL only, one case, inspected DAMAGED/Q device; dispatch same
serial to supplier TRANSIT, custody REPAIR/status QUARANTINE. Receive same serial
back to Q; old inspection remains in view but new post-repair inspection/reset
is mandatory. CUSTOMER stays Q even serviceable; ISP can go to issue-eligible BIN.
Replacement is separate new receipt of same SKU/new serial and preserved title;
old physical asset remains at vendor. Use actual receipt link and persisted list.
Reacquisition needs actual signed evidence and independent approval (task37),
never fake evidence or infer ownership change. RMA dispatch binds originalcustomer
repair WO/current revision/assigned technician; actual acknowledgement task40.
C8 transfer list currently lacks SKU/serial/date UI filters; revisit before36done.
