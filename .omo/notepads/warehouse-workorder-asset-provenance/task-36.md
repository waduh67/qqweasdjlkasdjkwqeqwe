# Task36 — transfer / return / repair UI investigation

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
