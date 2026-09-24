# Task35 — demand and issue implementation

## Task35 final browser selector correction

issue-final @2dcabb7a failed both projects at the new release assertion: actual
cell text includes its responsive column label (Dicadangkan0,000 m). The release
POST succeeded and actual displayed quantity was correct. Test now targets the
warehouse-cell-value element for exact quantities. Browser exited1 with cleanup
completed, volumes retained. NEXT run issue-release-value, then review final PNG.
No product changes; 14 affected web tests/TS/oxlint remain valid at2dcabb7a.

## Task35 release alignment and visual spacing — final browser next

Real issue-picker-fixed @76c9bc76 passed both desktop/mobile in 54.9s; eight
synthetic screenshots reviewed. Pick/unpick/repick/dispatch, named receiver,
partial confirmation and actual 60 m + 1 ONU transit worked. Cleanup completed.
Final review found release owner only permits the full unpicked allocation, so
UI now locks release quantity and explains release/re-reserve; helper rejects
partial release. Browser adds actual cable-only release/re-reserve while ONU
reservation stays intact. Scoped paragraph margins reduce mobile scroll gaps.
14 affected web tests, TypeScript and focused oxlint passed (0 errors/warnings).
NEXT commit current source, run issue-final via warehouse-issue-browser.sh,
review all final PNG and save portable evidence before checking task35 complete.
Task36 investigation ready; whole plan 35–48/F1–F4 active. No migrations changed.

## Task35 source76c9bc76 — third browser running; 134 distinct web cases verified

NONEplanning10requesttests+TS+focusedlintpassed. Pickerfix11cases+TSpassed;
baseline132cases plusnewpicker andNONE =134distinct cases acrossaffectedruns
(notone134testbatch). Portablepicker-none-verification.json captures exactsources.
Actual thirdbrowser warehouse-issue-browser.sh issue-picker-fixed RUNNING,
session84397, .omo/runtime/issue-picker-fixed.log, source76c9bc76.
No product/testchangeswhilebrowserruns. Await full desktop/mobile results,
review screenshots, fix/rerun ifneeded before35complete. Earlierbrowserfailed
technicianselectorthenrealMultiCombobox selection; bothfixedwithregression.
Next36investigationnotepadready;wholeplan35–48/F1–F4 active. No migrations.


## Task35 technician fix verified; NONE planning permission alignment

7c606fb2:11tests/3files (newMultiCombobox regression,9request,1WO) +TSexit0 passed.
Oxlint0errors, pre-existinginitialLabels effectwarning inMultiCombobox recorded.
Portalref fix isverified beforebrowser. Also removedextraSKUview gatefromopening
planeditor: authorizedplanner withoutSKUview canstilldeclareNONE+reason, asbackend
permits; editoralreadyblocksrequiredmaterialrowswithoutSKUview. NewactualUIflowtest
and9existingrequesttests/TS/oxlint RUNNINGrequest-none-permission.log.
Customerfallback now distinguishes missingname from absentcustomerID.
NEXT awaitchecks then thirdbrowser issue-picker-fixed; no browserrunningcurrently.
Baseline132web@f2fc7fb4 +newpickerregression, newNONEtestpending. Keep whole35–48/F1–F4.


## Task35 real technician-selection bug fixed — checks running

issue-selection-fixed@f2fc7fb4 bothbrowsercases passedvisiblemenu selector but WO
POST hadassignees:[] despiteclick. Actualpayloadfilteredfromprivate trace confirms;
no credentials printed. MultiCombobox outsidepointer handler onlyignoredrolelistbox,
while actualFluentmultiselectportal hasrolemenu. Itclosed onoptionpointerdownbefore
selection inChromium. Addedref toactualListboxslot andusespopupRef.contains(target),
soonlyownportal countsinside, regardlessrole. Browsernowassertsaria-checkedtrue.
New userEventrealFluent regression failsbeforefix (menuclosesafterfirstselection)
andchecks2persistedchoices+outsideclose. Fixedtest+WO+9request/TS/oxlintRUNNING
technician-picker-fixed.log. No browsercurrentlyrunning. Lastbrowsercleanupdone.
NEXT awaitchecks, runissue-picker-fixed viawarehouse-issue-browser.sh,thenvisualreview
andfurtherfixes asneededbeforetask35complete. Baseline132web@f2fc7fb4 remainsvalid.
Task36notesready;wholegoal35–48/F1–F4 active. No migrationschanged.


## Task35 sourcef2fc7fb4 — 132 web tests green, second browser running

132tests/18files passed aftersharedModalfix, TSawaitedexit0, focusedoxlint0errors.
Portableworkbench-dialog-verification.json saved. Task35 actual browser RUNNING
warehouse-issue-browser.sh issue-selection-fixed, session4450/runtime
issue-selection-fixed.log; sourcef2fc7fb4. Initialsource4e6d4b3d bothbrowserfailures
werewrongselectorrole(option vsactualmenuitemcheckbox), corrected2f101b2d.

Added docs/warehouse-workbench.md operatorsteps matchingcurrentUI andcorrected
warehouse-issues.md staleintro/unpick-cancelclaim againstactualcontext. Docs only
whilebrowserruns; product/testsourcekeptstable. NEXT awaitdesktop/mobileoutcome,
visualreviewPNG, fixandrerunbefore35complete. Continue36–48/F1–F4.


## Task35 confirmation dialog accessibility correction — verification running

New remaining-demand review test found actual Modal had two nested unnamed dialog
roles. Added useId/aria-labelledby to native dialog, keptone native role andmoved
headingid; removedredundantinnerrole. This enables namedconfirmation access for
screenreaders andtest. Requestreview initial1of13failed;12passed. Fullwarehouse+
DataTable+WO+PaymentGateway tests/TS/focusedlint RUNNING request-review-accessibility-web.log
because sharedModal changed. NEXT await then browser issue-selection-fixed. Browser
notrunning, lastinitialbothfailedtesttechnicianselector whichisfixed2f101b2d.
No completed35claim. Continue35–48/F1–F4.


## Task35 first browser failed on test selector — correction prepared

issue-initial@4e6d4b3d builtbackend/web, bothbrowserprojects failed beforeWOcreate:
Fluent MultiCombobox exposes menuitemcheckbox; testaskedroleoption andmatchedhidden
native WOtablefilteroption. Screenshot/accessibilitytree confirmsvisibletechnician
menucheckbox. Changed onlybrowserselector to actualmenuitemcheckbox. No forcingclick
or simulatedassignment. Allreceiving/putaway/usercreation hadcompleted. Cleanupdone.

Also fixedautoFIFOreview toshow remainingbackorder quantities ratherthan fullplan;
NONEreview includesreason. NewmeaningfulUIregression makes9pagecases;9page+4action
andTS/oxlint RUNNINGrequest-review-web.log. Previous127webproofstillvalidbaseline,
newchangeawaitingchecks. NEXT run warehouse-issue-browser.sh issue-selection-fixed
withnewsource; currentbrowsernone. task36.md containsread-onlytransfer/returnAPI
investigationfornexttask. Task35remainsopen;continuewholeplan35–48/F1–F4.


## Task35 source4e6d4b3d — full web verification passed; browser running

127tests/17files passed (warehouseAPI/pages/components,DataTable,existingWOtest),
TypeScript awaitedexit0. Oxlint exit0/noerrors,1warning WOarea fetchloadingstate set
insideeffect; recordedinportableworkbench-web-verification.json. Initial focused
three failureswerejsdomdialogpolyfill, fixed; no producterrors hidden.
Actual warehouse-issue-browser.sh issue-initial RUNNING (.omo/runtime/issue-initial.log),
source4e6d4b3d. Keep product/tests stable untilfinish; wrapperarchives35/issue-initial.
Await actualdesktop/mobile results, visuallyreviewsyntheticPNGs, correctissues and
rerunbefore35complete. Ownedcleanuptrap retainsvolumes. Continuewholeplan35–48/F1–F4.
Reviewconcernfornextedit: autoreserve dialog currently lists fullplan quantities;
label remainingbackorderperline to make exact reviewed remainingneed clear.


## Task35 real browser scenario prepared — full web checks running

Request workbench source2bd54f5d initially had3of8 UItests fail because jsdom lacks
HTMLDialogElement.showModal, not product actions. Added dialog stub matching other
warehouse tests;22tests/3files +awaitedTS+oxlint nowpassed. No swallowed assertions.
Added4meaningful action tests (historic allocations, exactrelease, bounds/mapping,
stale/issuebound/serial mismatch). Fullwarehouse+DataTable+WO tests/TS/oxlint RUNNING
request-workbench-full-web.log. Must await trueexit before claim.

Actual UI browser issue.spec.ts prepared with fulfillment.ts helper: signup/area/
masters/1000m10ONUreceipt/putaway/technician/WOcreation/100m2ONUplan/60m1ONUreserve/
pick/unpick/repick/namedreceiverpartialdispatch/current immutable slip print/transit.
No browser API writes or seededbusinessdata, nofakeack. Actual browser NOTYETRUN.
Found old WO create form omitted areaId entirely; added scoped namedarea selector
and includesareaId inPOST, neededfor real restricted-area operator flow. Backend
alreadyrequiresarea; existingauthorizationunchanged. Addedoptional inspectionRequired
flag to catalogbrowserhelper (defaultsunchanged) for receiptwithoutmandatoryinspection.

NEXT await fullwebchecks thenrun new warehouse-issue-browser.sh issue-initial wrapper
(basedonstockwrapper archive35 specissue.spec.ts); actual desktop/mobilevisualreview,
fixandrerunaffectedchecks before35complete. Continue36–48/F1–F4. No migrations.


## Task35 workbench UI wired — focused tests running; browser pending

Mapping/backend sourcef7bbd0d5 passed7tests/4suites in3m59s. Old posting fixture
now uses actual owner APIs; preserved60missued/40mused/20maccountable/40mbackorder.
Portable demand-mapping-verification.json saved; cleanup complete, volumesretained.
Plan editor5tests +9API tests and TS passed. Initial lint command used ESLint
incorrectly (repo usesoxlint); actual oxlint rerun passed, no repo toolchanges.

WarehouseRequestsPage now wired: paged namedWOselection, current summary/allocation
reads, plan/NONE/template/substitution editor, submit, FIFO or exactpartial/manual
identity reserve, release, named allocation pick, stored pagedissue list/actual
accepted quantities, namedreceiver and partialack dispatch, issue-awareunpick,
freshly authorized immutable slip printing, history and visible WO_TRANSIT setup.
Staleallocations disable actions;409reload discards edits; uncertainretry captures
samekey/body. No inferred received or physicaltransit from unaccepted amount.
Work-in-progress UI TypeScript initially passed;8new pagecases +5draft+9API/TS/oxlint
RUNNING request-workbench-web.log. No actual issue.spec browser yet. NEXT await/fix
focusedtests, add meaningful actionhelper cases asneeded, actual issue.spec using
existing real receiving/setup helpers desktop/mobile, visualreview, requiredchecks.
Task35 remainsOPEN. Continue36–48/F1–F4 after35.148nextunused;177/178reserved43.


## Task35 editor and posting fixture checkpoint — verification running

Demand mapping sourcea8a1cd41: 6/7 tests passed (new mapping,2demand,3supply);
old WorkOrderMaterialsITPostedFacts failed on obligation FK because direct issue
fixture omitted inventory_issue_line. Rewritten to actual owner pick/dispatch/
acknowledge/report-use APIs, preserving60missued/40mused/20maccountable/40mbackorder.
No production/schema change. Rerun all7 via material-mapping-server.sh aftercommit;
first failure archived demand-mapping, do not mark whole suite green.

Shared MaterialPlanEditor now implements explicit reviewed template copy, exact
MM/EA, NONEreason, current-plan-line substitution permission+reason+compatibility;
never carries older substitution into next revision. Five meaningful draft tests
plus9APItests/TS/lint RUNNING material-editor-web.log. New typed allocation reader
retains actual metadata/revisions and complete unpaged historical list.
UI is not wired yet; no browser proof. NEXT finish request workbench/list/reserve/
pick/unpick/dispatch/slip, focused tests and actualissue.spec desktop/mobile.
Continue35–48/F1–F4. No migrations;148nextunused,177/178reserved43.


## Task35 demand line mapping checkpoint — backend verification pending

Issue discovery sourcee90857a3 passed13 tests/5suites in3m39s, including3Modularity,
actual partial60m/full100m receipt, paging/unpick/repick, source/transit scope,
substitution revocation and permissions/tenant. Portable issue-list-verification
saved; all owned cleanup completed, volumes retained. Typed discovery source045611af
9tests+TS/lintpassed. Actual request/issue UI still notimplemented.

Added MaterialLineTotals.demandLineId nullable default at end (legacy-compatible),
populated only from actual persisted demandLine?.id; planLineId remains distinct.
One new real HTTP test: draftnull -> submit returns actualdistinctID -> first explicit
partial reserve60m of100m ->60mreserved/40mbackorder, unchanged1000mphysical.
Frontend codec preservesnull oractualID;9webtests+awaitedTS passed. Backend mapping
case NOTYETRUN. NEXT `.omo/runtime/material-mapping-server.sh` (new mapping plus
existing demand/postedfacts/supplyprojection). Keep backend stable during run.

Then implement shared MaterialPlanEditor + WarehouseRequestsPage workbench, named
WO search/pages, request quantities, exact partial/manual identity reserve, named
allocation pick/unpick, stored issue discovery/current receiver, dispatch and print.
Plan editor copies template into explicit reviewed lines (do not send lines:null
and review a potentially changed template); NONE explicitreason, substitutions
original planline/SKU+reason+override. Actual issue ack remains40/45.
Continue35–48/F1–F4; goalACTIVE; no migrations (148nextunused,177/178reserved43).

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
