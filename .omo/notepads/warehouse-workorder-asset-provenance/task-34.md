# Task34 — stock explorer implementation and verification

## Task34 COMPLETE — stock explorer verified on desktop and mobile

Final source75f8ba41, stock-explorer-readable:2passed45.5s, zero failure/skipped/flaky.
BootJar/TypeScript/webbuild passed.100 web tests across12 files passed,8 stock page
cases rerun after test-typing correction.11 backend query/bucket tests across7suites
passed at3046bb05. Portable task34/verification.json and6 reviewed synthetic images
saved. Earlier premature TS claim corrected; initial failure record remains.

Real UI:1000m/10ONU intake,900m/8available and100m/2quarantine; exact lowstockminimum,
serial lookup/5events/origin, lot5segments/3active/2split with1000m conservation,
parent/child navigation, known/unknown costs, scoped quarantine/position drilldown.
No stock explorer mutation. Textwrap now keeps current location names and document
revisions readable on desktop/mobile. All owned QA processes/containers/network
stopped;volumes retained. Task34 checkbox complete; goal remains ACTIVE.

NEXT task35 demand/picking/issue. Read task-35.md actual contract preparation.
Need discoverable persisted issue list after reload (currently only slip byUUID),
actual received totals distinct from dispatch, and named allocation metadata; then
typed APIs/shared plan editor/request workbench, actual browser reserve/pick/unpick/
dispatch with transit. Receiver acknowledgement UI belongs40, E2Eextension45.
Continue35–48/F1–F4. No migrations changed;148 nextunused,177/178reserved43.

## Task34 functional browser green; visual refinement pending recheck

stock-explorer-compiled at3d377e6f: both actual desktop/mobile passed44.8s, zero
failed/skipped/flaky. BootJar + web build passed; owned cleanup stopped processes
and containers/network, kept volumes. All requested stock/serial/lineage/scoped
quarantine assertions passed; no explorer mutations. Reviewed8 synthetic captures.
Found inherited resource-table ellipsis clipping document revision and current
location text. Warehouse cell values now wrap on both widths; stock detail paragraph
spacing reduced; movement labels translated for operators. No business behavior
changed. Task34 staysOPEN until new visual browser check. NEXT wrapper run
stock-explorer-readable. Task35 actual contract preparation saved in task-35.md.

## Task34 compilation correction — real browser still pending

stock-explorer-initial at e9e57a60 stopped BEFORE Playwright: bootJar passed but
web TypeScript found unsupported `exact` in two Testing Library ByRoleOptions.
The preceding TS pass claim was premature; the actual pending compiler failed.
100 unit tests did pass. Corrected two test queries (string name is already exact),
then awaited TypeScript exit0 and reran all8 stock page tests: passed. Initial
ui-verification.json now records the real failure; compile-correction.json records
cause/fix. Owned cleanup passed with volumes retained. No browser acceptance yet.
NEXT `.omo/runtime/warehouse-stock-browser.sh stock-explorer-compiled`, review
both screenshots only after actual success; then finish34 and continue35–48/F1–F4.

## Task34 explorer UI implemented — 100 web tests green; browser pending

Backend operational buckets against 3046bb05: 11 tests / 7 suites passed in 2m54s,
including exact reserved/picked quantities, quarantine/transit and scope/privacy;
owned cleanup passed, volumes retained. Portable bucket-verification.json saved.
Stock page now has summary/positions/assets/lots/unknown, server filters/paging,
named historical master selectors including archived rows, scanner GET lookup,
asset/position details, origin receipt links, exact costs and permission redaction,
lot conservation and paginated parent/child segment navigation, immutable history.
Drilldown retains location/condition/bucket; invalid/blank/duplicate URL rejected;
unknown legacy units remain unknown and inconsistent conservation remains an alert.
100 tests / 12 files passed (full warehouse + DataTable); lint passed. Initial
TypeScript failed in test queries, corrected in the next checkpoint above.
Eight new stock page tests cover quantities/pages, server buckets, archived named
filters, provenance denial, unknown units, hidden/exact costs, scope failure and
inconsistent/truncated lineage. No warnings in changed warehouse files.

NEXT run `.omo/runtime/warehouse-stock-browser.sh stock-explorer-initial` (new
wrapper archives task34, owned lock/cleanup retained). Extended receiving.spec.ts
uses only actual UI/API: receipt 1000m/10ONU, rejected 100m/2ONU, staged putaway,
serial lookup and 5-event trace, reel 5 segments / 3 active / 2 split, cost/origin,
quarantine filter and location-preserving position drilldown. No mocked browser
writes/responses. Screenshot top scroll corrected and mobile labels left-aligned.
Do not mark task34 complete until both projects pass and screenshots are reviewed.
Continue 35–48/F1–F4; goal ACTIVE. No migrations changed;148 next unused,177/178
reserved for43. This is a recoverable implementation checkpoint, not completion.

## Task34 typed explorer API and bucket filters checkpoint

Metadataquery e1634743:11tests/7suites green2m46s,portable display-metadata-verification.
Typedstock.ts addspositions/assets/lots/segments/unknown/discriminatedtimeline,cost
KNOWNvsUNKNOWNvsabsent;legacyunitnullpreserved; compositeevent:reservationIDs; exact
quantityunitconsistency; treechildcountbeyond100andconservationfalse visible.
stockRow includesactual nullableminimumQuantityBase.63API/receipttests green,
TS/lintgreen,portableapi-verification. No StockPage yet, task34 staysOPEN.

Backendbucket filter implementedONLYstocksummary/positions:AVAILABLE/RESERVED/PICKED/
TECHNICIAN/TRANSIT/INSTALLED/QUARANTINE. Preserves exactphysicalstatus semantics.
Filtersbeforeserverpaging; reserved usesunpicked+picked notstatusRESERVED; technician
excludesused/lost/disposed. Unsupportedendpoint/bucket rejected400. Existingbalance
IT nowasserts900000physical/600000available/200000unpicked/100000pickedreservedreel,
positivePICKED/TRANSIT/Q andno remainingtech,unchangedwritecounts. NOTYETVERIFIED.

NEXT .omo/runtime/stock-bucket-server.sh. ThenactualWarehouseStockPage+detailpanels
usingtypedAPIs,quantity/status/nameselectors/history/lineage; browserreceivingextension
forassettraceandtreewithsame realsetup. See task-34.md earliercontracts/visualnotes.
Source stable duringserverbuild. GoalACTIVE;continue34–48/F1–F4;nomigrations.

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

## Required bucket-query follow-up discovered after initial metadata checkpoint

Existing WarehouseQueryPredicates.positionDimensions filters request.status EXACTLY
against physical position.status. Reserved/picked goods normally remainAVAILABLE;
therefore usingstatusRESERVED/PICKED foroperator tabs wouldsilentlyomitencumbrances.
Do NOTfilteronlyfirst25client-side. Addoptional strictbucket query filter supported
ONLYstocksummary/positions endpoints (othersrejectparameter): AVAILABLE,RESERVED,
PICKED,TECHNICIAN,TRANSIT,INSTALLED,QUARANTINE. Keep existingstatussemantics unchanged.
Appendbucket toWarehouseQueryFilter defaultnull; parseallowBucket optionalfalse,
service.stock/positions calltrue. Extendprefix/request bind afteruntil soallexisting
queries retainnullbucketdefault. Predicate onscopedposition:
AVAILABLE available>0; RESERVED unpicked+picked>0; PICKED picked>0;
TECHNICIAN custody_owner_kindTECHNICIAN excludingCONSUMED/LOST/DISPOSED;
TRANSIT statusIN_TRANSIT; INSTALLED statusCUSTOMER_INSTALLED;
QUARANTINE conditionQUARANTINE orstatusQUARANTINE.
Otherendpointsfilters mustrejectbucket, notsilentlyignore. Existingbalancefixture
canprove RESERVE200000+PICK100000 onphysicalAVAILABLE900000remainingreel; response
shouldphysical900000/available600000 (returned17500notreserved). Queryunchangedcounts.
TRANSIT1positive andTECHNICIAN0afteralloriginaltechgoodsconsumed/returned; current
actualfieldtechniciancustody cases laterissue/MaterialSaya browsers task45.
Do noteditbackendduringstock-display-server currentbuild/test; pollsession/logfirst.

Visual follow-up in task34: mobilewarehouse table numeric-cell rightalignment also
rightaligns visible fieldlabels. Addtext-align:left towarehouse-mobile-label only;
keepquantitiesrightaligned. Task33receipt screenshotdesktop scrollIntoView puttitle
underfixedappheader; nextreceivingbrowser screenshot shouldwindow.scrollTo(0,0)
beforeviewportcapture (orfullPage), preservingactualproductlayout.
