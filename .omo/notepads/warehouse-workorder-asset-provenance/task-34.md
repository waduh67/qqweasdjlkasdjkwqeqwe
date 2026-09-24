# Task34 preparation — implementation not started

## Task34 started — scoped stock display metadata; verification pending

Task33 complete andpushed54c7fd16 (2browser40.9s,86distinctunit,11backend;sourcea39aaea8).
Task34 beginsadditivequerymetadata:stock.minimumQuantityBase fromactualSKU,
asset.locationName fromvisiblelocations,movement.currentLocationName explicitly
currentnot historicalsnapshot. ExistingQueryITIdentityextendedfornames/minimum;
no migrationsorlegacyDTOchanges. Frontendstockexplorer notimplemented yet.

NEXT .omo/runtime/stock-display-server.sh tests querybalances/identity/privacy/
compatibility/serialfilters/lotfilters/staged. Then typedstockassetlottimeline APIs,
WarehouseStockPage+tests and receivingbrowserextension as task-34.md describes.
Do notmark34completebeforebothrealbrowserprojects/visualreview. Current task33
receipt contracts: draft->ReceiptView,receive/inspect/putaway->{id,revision,state,
operationId}thenGETdetail. Avoid previous assumedfullmutationresponse mistake.
Continue34–48/F1–F4;148nextunused;177/178reserved43.

Finish task33 real receiving browser acceptance first. Extend the SAME receiving.spec.ts
with stock explorer/serial timeline and reel conservation. Current task33 expected final
900000MM+8EA available,100000MM+2EA quarantined, after partial800m then100m placement.
The final task45 917500MM/9ONU/1installed scenario remains separately required.

Read docs/warehouse-queries.md and WarehouseQueryController/Service/Filter, then
WarehouseQueryPersistence, WarehouseAssetQueries, WarehouseLotQueries,
WarehouseQuerySql and WarehouseTimelineSql. All actual GET/api/v1/warehouse.
All require item.view, unknown adds provenance.view. Current IAM/area/site/location
scope; no grants = zero data. Cost absent entirely without cost.view. No rebuild/read
mutation. Lot detail/tree hidden if ANY piece/movement is outside current locations.

Endpoints: stock summary,stock/positions,page+id+id/history,stock/unknown;
assets page/id/id/history (same physical asset IDs as legacy),assets/lookup?value;
lots page/id/id/segments/id/segments/{segmentId}/id/history.
Filters: page0,size25<=100,sortname|createdAt|id,direction; exactskuId,canonicalserial,
locationId,status,condition,owner,from/until. Both dates required,max366days.
History sortscreatedAt|id. SegmentsstatusACTIVE|SPLIT|RETIRED. Unknown/repeated/blank
query params rejected400, so serialize only actual supported filters. No search param;
use named searchable master pickers forSKU/location, manual/scanner exactserial.

Summary codec exists inapi/warehouse/models.ts: id/skuId/skuCode/name/tracking,
physical/reservedUnpicked/reservedPicked/available quantityobjects andstatus/condition/
owner buckets. Physical includes sink CONSUMED forconservation; label clearly, don't
mislabelaswarehouseonhand. Available excludesQ/transit/customer/unknown and reservations
once. Current summary hasNOminimumQuantityBase; useactual authorizedminimum/policy
read oraddscopedprojection forlow-stock, never guesszero/default threshold.

Position adds stockIdentityId,lotId?,serial?,locationId,locationName,custodianId/
Kind,condition,legalOwner,status +samefourquantityobjects. PositionID isprojectionID,
NOTstockIdentityID orassetID; properendpointlinks mustpreserve distinction.
Asset: id,assetId,skuId?,skuCode?,name?,serial,mac?,status,condition,legalOwner,
locationId,custodianId/Kind,quantity,admission,installedOnuId?,origin?,optionalcost.
Currently assetprojectiondoesNOTincludelocationName; resolveauthorizednameoraddsafe
scopedprojection, notmadeupname. Legacy/unresolvedmayhavesku/unitnull; codec must
preserveunknown, notcoerceEA. listassetsverifiedonly; detailallowsLEGACY_UNRESOLVED
onlywithprovenancepermission. LOOKUP alreadydecodes assetId/skuId?/serial/mac/location/
legacyUnresolved and rejects ambiguousserialtenantwide. Lookupmustneverpoststock.

Lot: id,skuId,code,name,received(quantity),receivedAt,admission,origin?,optionalcost.
Detail adds conservation{consistent,physicalQuantityBase,rootQuantityBase,
activeQuantityBase,terminalQuantityBase,rootCount,splitCount}. Consistencyfalse must
bevisible, norepairorfalsegreen. Segment id=stockIdentityId,lotId,parentSegmentId?,
kind,state,quantity,createdAt,origin?,childrenUUIDarray<=100,childCount,conserved.
ParentSPLITnotcountedagain; entiretree via paginatedsegments,parentbacklinks, don't
assumechildren100isall. No arbitrary recursion fetching entiretenant.

Origin: documentId,documentCode,kind,lineId,customerLabelSnapshot?,workOrderCodeSnapshot?.
Receiptlink uses /warehouse/receipts?id=UUID (receiptFiles.ts receiptLink). Otherdocument
routes followlater tasks; do notclaim unavailable detailexists. Cost whenallowed:
{stateKNOWN|UNKNOWN,totalMinor?,costBasisQuantityBase?,currency?}. UNKNOWNnullnotzero;
fieldabsent meansnotaccessible/currentoriginscope, notUNKNOWNcost. NeverNumberqty/cost.

Timeline page DTO is discriminatedunion:
MOVEMENT_LEG id(uuid),postingId,movementKind,documentId/code/revision,lineId?,operationId,
compensatesPostingId?,recordedAt,directionIN|OUT,stockIdentityId,locationId,custodianId/
Kind,condition,legalOwner,status,quantity,customerLabelSnapshot?,workOrderCodeSnapshot?.
INSPECTION id,inspectionId,operationId,documentId,lineId,recordedAt,stockIdentityId,
sourceStockIdentityId,dispositionACCEPTED|QUARANTINE|SUPPLIER_RETURN,quantity.
RESERVATION id = eventUUID:reservationUUID (NOTuuid!),eventId,eventKind,reservationId,
documentId/documentRevision,operationId,recordedAt,stockIdentityId,
reservedUnpickedBase,reservedPickedBase,baseUnit,state.
MATERIAL_FACT id,postingId,recordedAt,stockIdentityId,installed(boolean),returned(boolean),
useRevision,compensationId?,quantity. NotalltypescarrydocRevision/docID/location;
don'trequireundefinedfieldsor inventthem. Current location labels aren'thistorical
snapshot names; ifresolvedmakethatdistinctionclear. TimestampUTC+localreadout.

UI task34: replacebasicStockSummaryfullroute withrealWarehouseStockPage (overviewmay
keepembeddedsummary),tabsavailable/reserved/technician/transit/installed/quarantine/
unknown,assets/lots/positiondetailwithhistory,paging,originlinks,namedquantity/status,
lowstock,permissionawareemptyreceive/setupactions. Do85webregressionsplusnewmeaningful
unitpage/filter/cost/scope tests,real receivingbrowserbothprojects,screenshotsreview.
No migrations anticipated;148nextunused;177/178reservedtask43.
