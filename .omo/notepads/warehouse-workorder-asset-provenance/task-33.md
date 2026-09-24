# Task33 preparation — not yet implemented

## Task33 receive acknowledgement corrected; browser rerun pending

receiving-initial againstf16352e6:both desktop/mobile reached real draftcreate/edit,
serialduplicate correction,scanner,private upload/reload/download andsuccessfulreceive.
Both failed at32.85s because the client/test assumedtransitionreturnedReceiptView.
Actual ReceiptTransitionService returns{id,revision,state,operationId}; stockcommitted
but strictclientdecoder rejectedshape and correctlyretaineduncertaincommand.
CorrectedAPI withseparate stricttransitiondecoder;receive/inspect/putaway nowreloadGET
before renderingnewactions. Browser transitionhelper observes thatrealGET,thenchecks
lines/pieces fromdurablesnapshot. Addedunitactualack->GET->inspection;57focusedAPI/
receipt tests passed (85baseline+1new totalcoverage86),TS/lintpassed.

Finalbackendmetadata+costVisible againstf16352e6:11tests/5suites green2m47s,portable
metadata-final-verification.json. AllQAownedcleanup passed,volumesretained. Firstbrowser
failureprivate under task33/receiving-initial; no completionclaim. NEXT run
.omo/runtime/warehouse-receiving-browser.sh receiving-transition-fixed,inspectboth
screenshots aftergreen. Continue34–48/F1–F4; task34 preparation saved. No migration.

## Task33 receipt UI implemented —85 unit tests; real browser pending

Task32 complete atf9d95a9a. Task33 now routes to actual receipt list/draft/detail,
receive, inspection, partial putaway, private evidence upload/download/pages and
history/reference save. Named selectors; explicit source setup preset; bulk serial
and scanner Enter (never stock submission); exact quantities; original grouped
serial cost/conversion preserved on edit. Full draft replacement requires server
costVisible plus cost.view so hidden costs cannot become null. Receipts can still
be received/inspected by operators without cost access. Fresh GET after every command.
Multipart retry captures immutable bytes/file name/revision/key; no public URL.
Current piece IDs/revisions/dispositions from server, accepted remainsQ untilbin;
rejected never bypasses inspection. No stock seed or request/response mocks in E2E.

85unit passed +TypeScript/lint; no warnings in changed warehouse files. Metadata
backend beforecostVisible addition:11real tests passed at052df40a in2m46s. Portable
foundation-verification.json +metadata-verification.json saved. costVisible addition
and receipt UI browser still need verification; task33 checkbox stays OPEN.

NEXT .omo/runtime/receipt-metadata-final-server.sh (same11 aftercostVisible) then
.omo/runtime/warehouse-receiving-browser.sh receiving-initial. E2E real setup,
1000m/10ONU receipt/edit, duplicate serial correction, scanner, evidence reload/
download,100m+2ONU rejection,800m+8ONU putaway thenremaining100m, final900m/8ONU
available, revision6/history7/reference download. Inspect screenshots after green.
Continue34–48/F1–F4. No migrations;148unused; source stable while QA runs.

## Task33 receipt metadata checkpoint — verification pending

Task32 complete atf9d95a9a,4real browser and76unit green. Task33 adds paginated
GETreceipt attachments using current receipt authority/scope, locked intake and
stable createdAt/id order, safe metadata +matchesCurrentIntake flag. Old evidence
remains downloadable but not eligible for replacement intake. Names of source and
inspection now come from immutable receipt intake snapshot. No migration changed.
New WarehouseReceiptITMetadata covers paging, reload, stale binding, permission,
scope/tenant denial and no storage key/URL leak. Existing receipt regression selected.
NEXT run .omo/runtime/receipt-metadata-server.sh; compile/tests not yet claimed.
Then implement typed receipt API, captured upload and receipt UI/browser. Task33
remains OPEN; continue34–48/F1–F4 after actual acceptance.148 nextunused.

Finish task32 real setup browser first. Receipt screens need actual draft/receive/
inspection/putaway, attachments, immutable retry, named quantities and real1000m/
10ONU journey with rejection subset. Task34 extends same receiving.spec.ts with
stock/serial trace. Existing server task08 complete; do not invent alternate writes.

Read docs/warehouse-receipts.md and application/port/inbound/WarehouseReceiptModels.kt,
WarehouseReceiptController.kt, WarehouseReceiptService.kt, ReceiptEvidenceService.kt.
All /api/v1/warehouse/receipts; view/manage permissions respectively; current area/
warehouse/site scope on source, inspection and current goods.

POST draft(noexpectedRevision)201/0; PUTid draft with expectedRevision200/+1.
GETlist/detail/history; POSTid/receive{expectedRevision}, inspect{expectedRevision,
lines:[lineId,stockIdentityId,baseUnit,acceptedBase,rejectedBase,evidenceId,reason,
rejectedDisposition QUARANTINE|SUPPLIER_RETURN]}, putaway{expectedRevision,
destinationLocationId,lines:[lineId,stockIdentityId,quantityBase,baseUnit]}.
All immutable-key/revision rules; no client hash or invented piece identity.

Draft fields supplierId,externalReference,sourceLocationId,inspectionLocationId,
lines[{skuId,quantityBase,serials:[{serial,mac?}],lotCode?,conversion?,cost?}].
Source must ACTIVE codeRECEIPT_SOURCE kindTRANSIT; inspection ACTIVE QUARANTINE not
issueEligible. Task32 location UI can create these but real task32 setup currently
createsMAIN,BIN-A,QA only; add actual source location via UI in receiving fixture.
Use a meaningful setup affordance instead of asking operators to guess requiredcode.
SERIAL EA count equals unique serial count; lot/bulk needslotCode; MM measured reel.
Conversion optional{numerator,denominator,packageQuantity} exactpositive integer
strings; costoptional{totalMinor,currency}, originalgroupbasisnotcurrentSKUprice.
Cost omitted/redacted unlesscost.view onGET; mutations do not expose cost. Preserve
hidden costs when editing drafts; inspect actual server semantics, do not silently
replace an omitted/redacted cost with inventedzero/null.

Receive atomically creates verified origin and sourceOUT/quarantineIN. Inspection
percurrentpiece exact positiveaccepted+rejected<=uninspected piece. Partial split
makeschildren,parentSPLITnotspendable. Accepted remainsQ untilactualputaway. Destination
must ACTIVEissueEligibleBIN. RejectedneverusesSKUinspectionbypass. Entirelyrejected
receiptCLOSED; otherstateRECEIVED_IN_INSPECTIONuntilallnonrejectedputaway=>PUTAWAY.

ReceiptView id/revision/state/createdAt/supplierId/supplierName/externalReference/
sourceLocationId/inspectionLocationId/lines/inspections. Line id,inputLineNumber,
skuId,skuCode,skuName,tracking,baseUnit,quantityBase,serial?,mac?,lotCode?,
inspectionRequired,conversion?,cost?,pieces,acceptedBase,rejectedBase,putawayBase.
Piece stockIdentityId,lotId?,quantityBase,revision,disposition?,locationId,condition,
legalOwner,status,custodianId,custodianKind. History operationId,revision,action,
recordedAt; use exactUTC+localdisplay. Runtime decode, no coercion or empty fallback.

Attachments: POSTid/attachments multipart exactfile+expectedRevision, Idempotency-Key.
PNG/JPEG/PDF validated server,1..15MiB.201body ReceiptEvidenceView{id,documentId,
contentType,sizeBytes,sha256}; document revision advances separately—reload current
receipt, don't invent revision from stale state. Download GETid/attachments/evidenceId
privateviaapi.blob. Controller currently hasNOGETattachmentlist andReceiptView has
onlyinspections evidenceIDs: investigate safe list/read path for uploadedbutunused
attachments acrossreload. Add a scoped safe metadata endpoint if needed, no objectkeys
or public URLs. Stored content binding makes evidence afterdraftreplace stale.

api/client.ts already handles FormData without JSONContent-Type and reuses init
across401refresh. Shared command() currently capturesJSONonly; add immutable upload
command preserving File/blob+revision+key acrossretry. File object bytes immutable;
multipartboundary is not business authority. Pending command dialog already handles
ambiguousnetwork outcome; no silent duplicate uploads or confirmedstockoffline.

Existing namedWarehousePicker supports serverpaging; quantitycodec BigInt MM metres.
Catalog.ts E2E helpers create area+selfgrant/login,location,SKU,supplier throughactualUI;
helpers.ts signup/roles/users; no stock seed. Newtenants ENFORCED/NEW_EMPTY via actual
WarehouseTenantCreatedListener. Browser wrapper for receiving can copycatalog wrapper,
change spec and archive task33; stillownedhostflock8/qa9,retainvolumes. Noactiveexternal
adapters/hardware. No migration anticipated;148nextunusedifrealgapdemandsone.
