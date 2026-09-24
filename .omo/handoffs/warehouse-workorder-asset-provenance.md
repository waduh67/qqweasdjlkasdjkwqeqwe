# Warehouse Workorder Asset Provenance Checkpoint

## Task36 persisted reacquisition discovery implemented; verification next

Added bounded /returns/{id}/reacquisition-requests with current return/approval
read permissions, original WO area and current/historical location scopes before
pagination/count. Entries bind actual title document, source revision, signed proof,
and separately recorded applied return revision. Raw command response unchanged.
Original assignment references now include historical legalOwner so the list remains
reachable after approved CUSTOMER→ISP change. No cross-module name joins or migrations.
Reacquisition command now also checks historical repair location, matching return read.
Extended actual independent approval test with two persisted source docs, paging,
read-only reader, tenant/permission/revoked scope and actual applied-vs-unapplied refs.
Kotlin JDBC lambda inference warnings and redundant String conversion cleaned up.
NEXT run reacquisition-discovery-server.sh (5 tests/4 suites expected); frontend form
and signature read still to implement. RMA24web/11backend checkpoint ac1c59f2 pushed.
Task36 OPEN; keep remaining36–48/F1–F4 and remote commits active.

## Task36 RMA UI saved; 24 web and 11 backend checks passed

RMA read backend @c7612c9e passed11 tests/4 suites in3m45s; portable
rma-reads-verification.json saved and owned cleanup completed, volumes retained.
UI selects named original-customer REPAIR WO, reads actual current revision and
active assigned receivers, binds technician custody/transit and scanned same serial,
reviews one captured command, and reloads persisted named handover after success
or409. Actual acknowledgement remains technician flow (task40), read UI can refresh
DISPATCHED/RECEIVED. Completed CUSTOMER post-repair inspection hides repeat inspect
that would invalidate the closed repair revision.24 affected web tests/5files passed
5.72s; TS/oxlint exit0. Actual return/RMA browser remains deferred to task45 after40/41.

NEXT signed original-WO evidence reacquisition form plus persisted request discovery,
then transfer C8 filters and docs/final task36 checks. Backend Kotlin inferred Set type
warning in RmaWorkOrderAdapter line50 and redundant test String.toString need small
cleanup with next compile. No migrations. Task36 OPEN; whole36–48/F1–F4 continues.
No QA processes running. Commit and remote checkpoint each coherent change.

## Task36 RMA read contracts implemented; backend verification next

Added /returns/{id}/rma-work-orders/{workOrderId} to read actual current revision,
code/title and active assigned technician names for original customer REPAIR WO.
Uses return.manage, current return/repair locations and physical closed-repair
origin, through public InventoryRmaWorkOrderPort implemented by workorder module.
No unrelated material-request permission needed. Existing named WO list will still
use workorder.order.view for selection. Added /rma-handovers/{id}/details wrapper
with current names and locations, preserving raw get/command bytes and owner gates.
New meaningful manager-only/current-revision/active-tech/type/customer/scope test,
and existing actual RMA HandoverIT extended to named dispatch/ack details.
NOT VERIFIED YET: NEXT run rma-reads-server.sh (new read +3 existing RMA suites).

Replacement UI20tests/TS/lint and original-context6backend proof saved b3064bef.
RMA/reacquisition UI and transferfilters remain. No migrations. Task36 OPEN.

## Task36 replacement form saved; 20 web and 6 backend checks passed

Backend original assignment metadata @62159f68 passed6 tests/4 suites in4m38s.
Proof return-asset-context-verification.json saved; cleanup completed, volumes
retained. Prior11 return discovery tests @d1395e7b remain valid for unchanged areas.
Replacement UI now creates only a new draft receipt, bound sameSKU/newserial and
original title, with optional exact cost (unknown is not zero). Existing drafts
and received replacements are discovered through bounded persisted list and real
receipt GET; actual named receipt link resumes receiving/inspection. No auto
physical receipt or disappearance of original device. Cost fields absent without
cost permission.20 affected web tests/4 files passed6.97s plusTS/oxlint exit0.

NEXT RMA dispatch and read UI, signed-evidence reacquisition and its persisted
continuation, transfer C8 filters. Task36 still OPEN, all36–48/F1–F4 active.
No migrations changed. Current QA processes stopped. Commit/push each checkpoint.

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

## Task35 COMPLETE — final source31b26399; task36 next

Actual issue-release-value browser passed2/2 (desktop1280/mobile375),58.245s;
0 failed/skipped/flaky, no global errors. Packaged backend+web builds passed.
All8 final synthetic screenshots reviewed and saved with SHA256 in task35/
verification.json. Release60m/re-reserve preservedONU allocation; pick physical
cut+serial scan, unpick/repick same cut, reload, named receiver+partial review,
print, dispatch and actual60m+1ONU transit0available verified through real UI.
No simulated technician receipt. Final14 affected web cases/TS/oxlint passed;
134distinct web cases verified across relevant runs. Backend allocation10,
issue-list13,demand-mapping7 cases have separate source-specific portable proof.
Cleanup completed; volumes retained. Task35 checkbox nowcomplete;1–35 complete.

NEXT task36 transfer/return/repair screens; investigation in task-36.md includes
actual contracts, access-before-page query design and named source lookup.
No task36 production implementation yet. Whole36–48/F1–F4 goal remains active.
Applied migrations throughV175_147 unchanged;148unused,177/178reserved43.
Use per-commit handoff+ledger and push feat/warehouse-workorder; no main merge.

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

## Task33 COMPLETE — real receiving desktop/mobile green

receiving-transition-fixed againsta39aaea8:2passed,0failed/skipped/flaky,40.9s.
ActualUI emptytenant setup andsourcepreset; draft1000m+10ONU,duplicate correction,
scanner,editpreservinggroupcost,privateevidenceupload/reload/download,receive,inspect
reject100m+2ONU,putaway800m+8ONU then100m. FinalPUTAWAYrevision6/history7;
stockUI900m+8ONUavailable,1000m+10physical withrejected100m+2inquarantine.
CurrentGET followsactualtransitionack,notfabricatedfullresponse. Both desktop/mobile
receipt/stock screenshots reviewed andportabletask33/verification.json committed.
85baselineunit +57focused afterfix incl1new=86distinct;TS/lint/bootJar/webbuildpassed.
Backendmetadata/costVisible11tests/5suites passedf16352e6;portablefinalproof saved.

AllownedQAprocesses/containers/networkstopped;volumesretained. Task33checkbox complete.
Nexttask34 stock/device/lotexplorer:read task-34.md preparation,actualquerycontracts;
extendsame receiving.spec.ts forserialtimeline/reelconservation. Continue34–48/F1–F4.
GoalACTIVE. No migrationschanged;148nextunused;177/178reservedtask43.

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

## Task32 COMPLETE —76 unit tests and4 real browser cases green

catalog-layout-fixed against99868d60:4passed,0failed/skipped/flaky. Real desktop and
375px touch journeys cover full empty-tenant setup, master edits/archive/duplicate,
user area/scope grants and restricted read-only access. Both screenshots reviewed:
mobile labels match values, action buttons reachable, correct warehouse breadcrumbs,
no horizontal document overflow. Portable task32/verification.json and reviewed
synthetic screenshots committed.76unit (61warehouse+15DataTable), TS/lint/bootJar/web
build passed. Owned processes/containers/network stopped; volumes retained.
Task32 checkbox complete. Continue33–48/F1–F4; task33 receipt contracts in task-33.md.
Goal ACTIVE. No migrations changed;148 unused and177/178 reservedtask43.

## Task32 functional setup green; mobile table and breadcrumb refinement

catalog-area-fixed against beecbc60:4 passed,0 failed/skipped/flaky,59.084s.
All owned cleanup succeeded, volumes retained. Reviewed desktop/mobile screenshots
revealed resource-table mobile header/value alignment and the global catalog crumb
incorrectly saying Paket Internet. Added warehouse-only responsive label/value rows
with accessible column headers retained, full-size row actions, and full-path warehouse
breadcrumbs. Other resource tables retain their existing presentation.
76 targeted tests (61 warehouse +15 DataTable regressions), TypeScript and lint passed.
Browser assertions now cover the actual warehouse breadcrumb and action target size.
NEXT: run catalog-layout-fixed, review both screenshots, then save portable task32
proof and mark32 complete. Task33 contract notes ready; continue33–48/F1–F4.

## Task32 desktop setup passes; mobile area prerequisite layout fixed

catalog-initial against3dc45f23:3passed/1failed59.67s. Both task31 navigation cases
and the full task32 desktop setup journey passed. Mobile setup failed at existing
AreasPage: its unwrapped horizontal create row pushed the Tambah button outside
viewport. Screenshot inspected: code/name inputs extended past375px. Fixed only
that prerequisite form to wrap with flexible12rem/16rem bases and min-width0.
This is a real UI fix, not a forced browser click. All owned cleanup succeeded;
volumes retained.61 unit tests remain green from the preceding checkpoint.

Next .omo/runtime/warehouse-catalog-browser.sh catalog-area-fixed. Need both full
setup journeys green and inspect screenshots before32completion. Continue33–48/F1–F4.
Task33 contract preparation saved in task-33.md; implementation not started.

## Task32 catalog implemented —61 unit tests; browser setup pending

/warehouse/catalog now routes to actual location, SKU, supplier and user-scope tabs.
Setup checklist explains explicit area grants and links existing area/user admin.
Location editor supports kind, same-area warehouse/bin parent, optional named site
and active custodian selection, issue eligibility, read-only/archive and confirmations.
Current reference404 preserves its stored ID with an explicit unavailable-name label;
other failures show error, never fabricated names or a successful empty directory.
No mutation follows merely selecting a parent/user. Named selectors are searchable
and paginated. Master list now uses existing DataTable resource presentation.

Scope panel reads actual grants including revoked revision. It cannot infer zero
when GET fails; only an absent entry in a successfully read list uses expected0.
Grant/revoke review names user/location and explains inherited versus direct access.
409 reloads the current grant without automatic resubmission. Role and area remain
independent authority.61 unit tests passed (57prior+4 location/scope); TS+lint passed.
Portable task32/catalog-verification.json. No task32 browser success claimed yet.

Extended e2e/warehouse/setup.spec.ts to4 real cases total across desktop/mobile:
existing task31 journey + new UI area creation/self-assignment/login; warehouse/bin/
quarantine setup; SKUcable exact82500MM minimum andONU; supplier; edits of all3;
referenced root archive denied; unused SKU archive succeeds/read-only; duplicate code
keeps editable draft; UI role/user/area creation and scoped readonly grant; reader
sees main+child but not standalone quarantine. No SQL seed or API bypass for setup.
New catalog.ts browser helpers use only UI actions and observe actual responses.
helpers.createUser accepts optional area checkbox labels/prefix, original31 unchanged.

NEXT: run .omo/runtime/warehouse-catalog-browser.sh catalog-initial with private log.
Wrapper archives under task32, retains volumes and owns cleanup/locks as before.
Resolve real UI failures, inspect mobile screenshot, capture portable proof and mark32
only after required cases pass. Then33–48/F1–F4. Goal ACTIVE. No migrations added.

## Task32 IN PROGRESS — editor/picker foundation57 unit tests green

Task31 complete and published170d2158 (real browser source55b19223; desktop+mobile2
passed and49unit, portable evidence/screenshots). All owned QA stopped; volumes kept.
Task32 has generic WarehouseMasterPanel (search/page/state, create/edit/read-only,
archive confirmation with real revision), SKU and supplier editors using captured
commands and native form validation; they are NOT yet wired to /warehouse/catalog.
Location editor, user-scope panel, actual catalog/setup checklist and browser setup
extension remain to implement. No task32 browser claim or checkbox completion.

WarehousePicker uses named bounded search/pages and preserves selected references
across searches; no silent first-page truncation. API setup.ts decodes existing IAM
user/site pages, areas and warehouse-scope grants; malformed/missing revision fails.
masters.ts adds typed get-by-id. Error messages preserve archive/business reasons
instead of treating every409 as stale. Pagination moved to shared warehouse control.
Button AppButtonProps now distributes Omit over Fluent's button/anchor union to
preserve native form prop (type-only change; no runtime implementation change).
DESIGN.md picker/scope conventions updated before these controls.

57unit cases passed (prior49 +8new setup/editor/picker cases); fullweb tsc-b and lint
passed. Portable task32/foundation-verification.json. Tests cover actual MM minimum
payload, retained editable supplier draft after409, archived/read-only no save,
actual stale revision+explicit reload, selector preserves chosen name acrosspages,
invalid IAM paging/missing grantrevision, and meaningful archive reason. Extend with
location/scope/archive-page behaviour and actual browser setup before32completion.

Next read task-32.md contract preparation below. New tenant listener really exists:
WarehouseTenantCreatedListener initializes ENFORCED/NEW_EMPTY atomically on tenant
created event (so later receipt UI needs no fake cutover for new tenants).
Continue32–48/F1–F4. No migrations changed;148unused; no active backend/QA sessions.

## Task31 COMPLETE —49 unit tests and both real browser projects green

setup-mobile-fixed against55b19223: desktop1280x900 and touch/mobile375x812 both
PASSED (2tests,12.35s,zero failures/skips/flaky). Real UI signup, role/user creation,
independent approver login, overview+queue, stock API403 and route denial, unknown
route unavailable, no horizontal document overflow. All warehouse requests were
real, no mocked responses or SQL inventory seed. API+Vite proxy readiness proved
warehouse_e2e/warehouse_app/owned marker; role NOSUPERUSER NOBYPASSRLS. All339
migrations through147 booted. Server bootJar and web TypeScript/Vite passed.
49 API/control unit tests and14 prior API/session/nav regression tests are separately
recorded. Portable verification.json +reviewed synthetic-account desktop/mobile
screenshots committed under task31. Raw result JSON/auth-bearing traces stay private.

All owned QA processes/containers/network stopped cleanly; volumes retained.
Task31 checkbox now checked; whole goal stays ACTIVE. Next task32 setup/catalog
CRUD and user warehouse scope UI; read task-32.md preparation and actual contracts.
Current other warehouse routes explicitly unavailable pending32–41; do not claim
full operational UI complete. Continue32–48/F1–F4 with commits/remote checkpoints.
No migration changes;148 nextunused,177/178 reservedtask43. No subagents or deployment.

## Task31 desktop passes; mobile drawer selector fixed

setup-label-fixed against9b794f79: real desktop journey PASSED, including signup,
UI-created approval-only role/user, overview+queue, stock API403, stock route denied,
unknown route unavailable and no horizontal page overflow. Mobile reached the same
approver login then failed opening navigation: translated-offscreen drawer still
satisfies Playwright isVisible(). Helper now reads header aria-expanded at<=820px
and uses actual touch tap to open drawer and section. No application code changed.

Next run .omo/runtime/warehouse-setup-browser.sh setup-mobile-fixed. Need BOTH
projects green in one run before marking31complete. Last run1passed/1failed31.4s,
all owned cleanup passed, volumes retained. No migrations changed. Continue32–48/F1–F4.

## Task31 browser reached real UI; required-field selectors corrected

setup-health-fixed failed compilation only (Any? health detail); now requireNotNull
uses previously validated values. setup-health-compile-fixed built successfully,
API+Vite proxy health passed with the owned marker/database/app role, then BOTH real
browser projects reached signup and failed the same test selector: Fluent adds a
required asterisk to label text. Accessible textbox name is correct; exact getByLabel
was too strict. Helpers now match the field label without requiring exact raw text.
No application signup or permission failure has been observed yet. Both failed runs
cleaned owned processes/containers successfully, volumes retained.

Next .omo/runtime/warehouse-setup-browser.sh setup-label-fixed, verify complete
signup/role/user/approver journey. Task31 remains OPEN;49 unit proof remains valid.
Task32 preparation notes saved separately (contracts, explicit area grants, scopes,
UI prerequisites), implementation not yet started. Whole goal ACTIVE.

## Task31 browser readiness correction (not yet verified)

Published a030927f contains49 green unit tests and the new navigation/controls.
setup-initial browser attempt built server/web and booted all339 migrations through
V175_147 in warehouse_e2e, but readiness failed before any browser test: SMTP health
was DOWN although email is intentionally disabled, and no warehouse health indicator
actually existed (earlier handoff assumption was wrong). Added profile-only
WarehouseQaHealthIndicator querying database/current app role/marker and requiring
NOSUPERUSER/NOBYPASSRLS/no role/db creation; mismatches fail DOWN. Disabled only the
unused mail health probe in warehouse-e2e. No production profile behavior changed.

Next run .omo/runtime/warehouse-setup-browser.sh setup-health-fixed with private log.
Check actual compilation, owned API/proxy readiness, then real signup/approver browser
journey; no green browser evidence yet. Keep task31 OPEN until both projects pass.

## Task31 navigation and transaction controls — browser harness pending

Warehouse routes now use independent permissions and a separate Gudang & Logistik
sidebar group. /warehouse and /warehouse/approvals use the current paginated API;
approvers do not need inventory.item.view. /warehouse/stock reads typed quantities.
Old WarehouseOperationsPage is a compatibility export; client-generated approval
hashes are removed from the active/legacy web entry. Other planned routes and
unknown paths explicitly report unavailable until their tasks are implemented.

Shared controls under components/organisms/warehouse: exact quantity field/readout,
manual/keyboard serial lookup (lookup only), named lines, status, timestamp/history,
and captured-command dialog. Network/invalid-success ambiguity locks dismissal and
retries the original command; definite 400/402/403/404/409/422 rejections allow return
or explicit document reload. Query state discards obsolete filter results.
49 unit tests passed (44 API +5 controls); TypeScript passed. Lint passed with
existing repository warnings. Isolated jsdom dialog shim is only in unit tests.
No real browser success claimed yet.

New real browser harness: playwright.warehouse.config.ts, e2e/warehouse/helpers.ts
and setup.spec.ts; actual UI signup, role/user create, login independent approver,
API readiness database/user/marker assertion, denied stock API and route, explicit
unknown route, 375px touch/mobile and desktop. No API response mocks or SQL seeds.
application-warehouse-e2e.yml disables scheduling/demo/radius/SMTP fallback/throttle
and automatic provisioning; existing qa.sh owns backend/database/object store.

NEXT: run .omo/runtime/warehouse-setup-browser.sh setup-initial (private log),
resolve actual failures and capture sanitized portable browser evidence before
checking task31 complete. Wrapper owns outer flock fd8; qa uses fd9; cleanup retains
volumes. Then finish32–48/F1–F4. Goal ACTIVE. No migrations changed;148unused.

## Task31 API foundation —44 tests and TypeScript green

Quantity, runtime codecs, master/stock/lookup DTOs and immutable command transport
now implemented under web/src/api/warehouse.44 tests passed with plain npm test;
`npx tsc -b` passed. Portable evidence task31/api-verification.json. Shared
masters.ts exposes typed list/save/archive commands and stock/identity reads.
Responses validate UUIDs, safe numeric revisions/pages, string quantities, unit/
display consistency, tracking/ownership/states and bounded pages; malformed data
throws WarehouseDataError instead of empty success. Optional nullable fields remain
null and unknown additive fields do not leak through typed views.

command() captures serialized JSON/key once, coalesces concurrent submissions and
reuses both after network loss or401 token refresh. It deliberately reauthorizes
via server on later execute(), never caches a prior successful reply as permission.
Uses api.request and original Idempotency-Key; no client approval hash. Tests prove
input-object edits do not mutate captured retries, conflicts retain keys and bad
success bodies reject. All this is unit evidence, NOT browser acceptance.

Next: shared warehouse route/gate/nav and named controls; replace old inventory
warehouse shell; build real setup.spec.ts desktop/mobile harness and isolated
warehouse-e2e profile through existing qa.sh browser. App.tsx also has a misleading
RequireAnyPermission hardwired to canViewHotspot; do not reuse it blindly for
warehouse. Add a warehouse-specific gate or correctly generalize with regressions.
No backend QA active, migrations unchanged,148unused. Task31/whole goal OPEN.

## Task31 quantity foundation saved —34 web tests green

Task30 complete/published11468066 (53 green,main422cf536). Task31 now has design
specs, exact bigint quantity conversion/formatting and20 quantity tests.14 existing
API-client/session and shell-nav tests also pass. Vitest workers disable Node native
webstorage when supported so jsdom owns localStorage; plain npm test works on Node26.
Portable evidence task31/quantity-verification.json. No package/dependency changes.

Task31 remains OPEN: runtime DTO validation, retained mutation retry keys, actual
warehouse routes/navigation/shared controls and real desktop/mobile browser harness
plus isolated warehouse-e2e backend profile are next. Detailed source paths and
acceptance notes in task-31.md. No backend QA active.147 immutable;148unused.
Whole-plan goal ACTIVE;31–48/F1–F4 remain. Keep committing/pushing recovery notes.

## Task30 COMPLETE —53 reports/receipt regression tests green;task31 started

reports-replay-fixed against422cf536 passed53 tests with zero failures/errors/skips
in5m35s. Portable sanitized evidence: task30/verification.json. Owned QA stopped
cleanly and retained volumes. Both complete real1km+10ONU LOAN/SALE journeys,
100m+1 issue,82.5m use+1 actual install,17.5m accepted return reached917.5m/9ONU,
zero field cable and unchanged replay. Costs retain1000006/1000000 IDR and1999995/10
USD source bases, yielding separate82500IDR/200000USD HALF_UP totals. Physical
1002-leg export rejection and narrowed334-row export passed. All print snapshots,
price-draft edits, current cost revocation, foreign scope, explicit unknown legacy
units, stock-card pre-range opening and formula guards passed.

Receipt replay fix also passed all40 receipt cases: current authority/assignee/
warehouse scope still apply, old successful reply survives WO progress, changed
payload or fresh stale command cannot post, and revoked scope still denies. No
migrations were added; allthrough147 immutable,148unused. Plan tasks1–30 complete.
Whole-plan goal ACTIVE:31–48 and F1–F4 remain. No main merge/deploy/reset.

Task31 now has uncommitted web/DESIGN.md warehouse-control specs and
web/src/api/warehouse/quantity.ts +quantity.test.ts.20 precision/input tests pass
with `env NODE_OPTIONS=--no-experimental-webstorage npm test -- src/api/warehouse/quantity.test.ts --maxWorkers=2`.
Plain npm test first failed20 cleanup hooks because Node26 native localStorage
shadows jsdom (undefined without --localstorage-file). Do not fake browser storage;
use the scoped Node flag, or make the test runner handle supported Node versions.
Repo deploy uses Node22. Need commit this small31 checkpoint, then finish shared
runtime DTO parsing, stable retry transport, warehouse routes/navigation/controls,
real desktop/mobile browser harness and external-adapter isolation profile. Existing
qa.sh browser deliberately refuses missing31 config/profile. See task-31 notes.

## Full numeric report passes; delivery replay lifecycle fix under validation

reports-full against aae97d92 executed11 tests/5 suites,2 failures,0 errors/skips,
3m22s. All4 report basics,3 modularity,CSV formula test and actual1002-ledger export
bound passed. BOTH full LOAN/SALE journeys reached correct917500MM/9ONU/zero field
cable, exact82500IDR and200000USD totals, captured original bases and visible serial
chain. They failed only at final acknowledgement REPLAY after WO start: fulfillment
checked the old expected WO revision before inventory could return the stored reply.

Moved that expected-WO-revision check to InventoryMaterialReceiptService AFTER its
existing actor/resource/hash/cutover/location-authorized replay path, together with
the existing issue/current-WO revision check. New postings keep both comparisons.
Current field permission, active assignee, active WO, live plan, same receiver,
authority/cutover fences and scoped receipt checks all remain before any reply.
No SQL or persisted contracts changed. Added WorkOrderMaterialReceiptITReplayLifecycle2:
progress replay returns identical original, changed payload/new stale action denied,
revoked field scope denied, and stale first receipt cannot post.

Added WarehouseReportPrivacyIT2: current cost revocation removes historical print
cost and blocks cost export; explicit migration-owner staged unknown raw quantity
never receives inferred units/available balance. Enhanced historical-cost test with
a new supplier quote draft and a later price edit, which must not reprice old use.
Added docs/warehouse-reports.md with paths, filters, exact cost math, snapshot/age
semantics and bounded exports.

Current .omo/runtime/reports-replay-fixed.sh / .log selects *WarehouseReport*IT,
*WarehouseReportCsvTest,*WorkOrderMaterialReceiptIT* and ModularityTests. Expected
reports13 plus receipt regression cases; do NOT assume final count/result yet.
Archive task30/reports-replay-fixed/xml; private reports-replay-fixed-database.log.
Task28 COMPLETE183 green; task30 OPEN pending this run and final acceptance review.
Allthrough147 immutable;148unused. Whole-plan goal ACTIVE;keep commits/pushes.

## Report custody correction and full11-case validation running

reports-compile-fixed executed7 tests with2 failures,0 errors/skips,2m8s. Unknown
cost, filter guards and3 modularity tests passed. Privacy reached revoked scope but
the test used0 instead of1 as the grant revision; corrected fixture. Physical917500
available/82500 consumed passed, then custody-aging counted the consumed sink under
the former technician. Custody/transit reports now exclude CONSUMED/LOST/DISPOSED.
No ledger or stock quantities were changed to fix the report.

Added strict optional workOrderId filter only for work-order-costs; invalid UUID,
duplicate value and unsupported-filter paths reject. Unknown quantity summary is
bounded by base unit (line pages still carry SKU/WO). Added unknown-stock report
using existing explicit legacy-unverified projection; no inferred units. Historical
print rejects DRAFT versions whose mutable lines are not guaranteed frozen.

Current .omo/runtime/reports-full.sh / .log selects *WarehouseReport*IT plus
*WarehouseReportCsvTest and ModularityTests, expected11. New complete numeric2,
real1002-ledger export1 andCSV1 cases compile; execution result not yet known.
Archive task30/reports-full/xml; private reports-full-database.log. All migration
versions through147 immutable,148 still unused. Task28 COMPLETE with183 green;
task30 OPEN until full acceptance/evidence, later30–48/F1–F4 work remains.

## Report CSV compilation fixed; initial integration running

reports-initial failed compileKotlin before any tests (14s): Jackson JsonNode.map
selected its member overload instead of Kotlin collection mapping. CSV now converts
to a sequence explicitly. reports-compile-fixed is compiled and running the same
WarehouseReportIT4 + ModularityTests3; the3 modularity cases have passed so far.
Do not label initial copied XML green; cleanup after compilation copied stale data.

Added WarehouseReportJourneyIT2 full real1km+10ONU /100m+1ONU /82.5m use /17.5m
accepted return /LOAN or SALE deployment journeys, with actual HTTP-created customer,
mixed IDR/USD HALF_UP source costs and replay checks. Added WarehouseReportExportIT1
real334-device receive/putaway ->1002 visible ledger legs (oversized CSV rejection),
and WarehouseReportCsvTest1 formula/control/quoting cases. These4 new cases are NOT
selected by the running initial suite and have NOT compiled/run yet. Next selector
must include *WarehouseReport*IT and *WarehouseReportCsvTest (expected11 total with
modularity). Still need task30 acceptance review, WO filter/summary bounds, report
scope and any SQL/fixture failures from execution. All148+ migrations unused.

## Task28 COMPLETE —183 affected regression tests green

asset-loss-regression against c09c6da9 completed183 tests/11 suites with zero
failures/errors/skips in13m20s. Replacement48, ownership72, episode revisions30,
returned dispositions17, compensation12, actual returned-device reuse1 and
modularity3 all passed. Owned QA stopped cleanly with volumes retained. Portable
sanitized evidence: task28/existing-flow-regression-verification.json.

Together with30 disposition/compensation guards,6 compensated-asset reuse cases
and15 active-loan-loss/guard cases already verified, task28 acceptance is satisfied:
approved document-bound exact quantities; immutable original costs/evidence;
independent policy approvals; loss of a live ISP loan closes the existing episode
without fake recovery; customer-owned SALE property is denied; exact original
posting linkage for correction; closed/reused/installed downstream state cannot
be casually reversed. Plan task28 is now checked. Compensation remains scoped to
returned LOSS/SCRAP, as documented; arbitrary ledger rewrites are not exposed.
All migrations through147 remain immutable;148 next unused, reserve before use.

Whole plan remains ACTIVE. Task30 initial report foundation4414f9b6 is pushed;
its7-case reports-initial QA is now running, NOT yet green. See task-30 notes.
30–48 and F1–F4 remain. Continue autonomously with commits, remote checkpoints,
sanitized evidence and recovery notes. No main merge/deploy/reset.

## Task30 report foundation checkpoint — NOT yet verified

Task28 existing-flow regression still runs against c09c6da9 in
.omo/runtime/asset-loss-regression.sh / .log (173 PASSED, no failures at last
observation; NOT a final result). Task28 remains unchecked. All migrations through
147 applied/immutable;148 next unused. No new migrations in this checkpoint.

Task30 now has WarehouseReportService/controller/persistence, shared scoped
stock, ledger stock card with pre-range opening, movement/serial chain, continuous
current-dimension custody age/transit, assignment loan/sold status, operational WO
use costs, historical receipt/issue/return print DTOs and bounded CSV. Costs keep
original receipt numerator/basis and exact integer HALF_UP per line; per-currency
totals and unknown quantities remain separate. Handover/removal/loss does not charge
the original installation again. Report.view is independent of item.view; cost.view
is required for WO costs and gates print costs. All outputs omit canonical payloads,
customer labels, evidence/object keys and authority/session data. Scoped locations
precede counts; stock card opening is calculated before dates. CSV max1000, rejects
page/size and oversize rather than silently truncating, neutralizes formulas.

Four WarehouseReportIT tests authored: real82500 use/17500 inspected return with
917500 available and historical print, unknown cost, current scopes/privacy and
filter/export boundaries. NOT executed yet. .omo/runtime/reports-initial.sh / .log
is queued behind the existing QA lock; selects WarehouseReportIT + ModularityTests,
expected7. Archive task30/reports-initial/xml; private reports-initial-database.log.
Do not trust any old XML copied by cleanup after a compile failure.

Task30 still requires actual full1km+10 ONU fixture/1ONU installation, mixed
currencies/rounding, hard oversized-export proof, nonempty loan/sold/aging/transit
coverage, revocation/history review and fixes from initial run. Print names use
actual captured SKU snapshots (receipt/issue/origin); missing old snapshots remain
NOT_CAPTURED, never reconstructed from mutable master. Review bounded response
summary growth and exact historical print scope. Full-plan goal stays active:
30–48 and F1–F4 remain; no main merge/deploy/reset. Continue committing and pushing
coherent tested fixes and portable sanitized evidence. Never commit private runtime.

## Task28 active-loan loss VERIFIED — existing-flow regression running

asset-loss-guards against c09c6da9 passed15 tests /3 suites, zero failures/errors/
skips,3m7s. Both primary LOAN/SALE paths and10 safety scenarios passed.147 applied
22:49:14.237 JKT and is immutable: 5da98c83d788aa398a8b70e43e583b2fa1f694e25dd9bf334c27f8f25cccb31f.
All143–147 migrations are immutable;148 next unused, reserve before creation.
Owned QA stopped cleanly with volumes retained. Portable sanitized evidence:
.omo/evidence/warehouse-workorder-asset-provenance/task28/active-loan-loss-verification.json.

An accepted active ISP loan can now be independently declared lost without fake
removal/return records. Exactly one approved LOSS closes its existing assignment
and customer episode and queues provisioning atomically, retaining original loan,
handover, installation and telemetry history. Actual recovery during pending
approval becomes durable STALE; races produce one closure. Direct SQL pending
effect/assignment closure, requester/delegation, unknown cost and revoked replay
are denied. Scope filtering precedes pagination. Rebuild and pending replacement
permit retirement both passed. Existing returned-disposition/compensation proof
was already30 green; actual compensated-device reuse was6 green separately.

Current .omo/runtime/asset-loss-regression.sh / .log checks CustomerAssetReplacementIT,
CustomerAssetOwnershipIT,CustomerAssetEpisodeRevisionIT,WarehouseDisposition*IT,
WarehouseCompensation*IT,WarehouseReturnITReuse and ModularityTests. Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/asset-loss-regression/xml;
private DB log asset-loss-regression-database.log. Do not assume count/result yet.
Product main remains c09c6da9. Wait for this affected existing-flow regression,
then save evidence and assess task28 acceptance before marking complete.
Task28 and whole-plan still OPEN;30–48/F1–F4 pending. Docs/warehouse-dispositions.md
now describes actual return compensation and active loan loss contracts.
All current checkpoints are pushed with explicit local SSH key; no main merge,
deploy or reset. Goal remains active until the whole plan is actually finished.

## Task28 lost-loan outbox binding fix — 15-case validation running

20bb5ce9 is published. asset-loss-evidence-fixed executed5 tests/1 failure,
0 errors/skips,1m56s.146 applied22:46:28.708 JKT, immutable: d7126fe6d9ab06ef9415d3ebbebf86de03193a142359d7db31c0bd6c8968f837.
LOAN draft/replay/get/list, policy-derived approval request and requester403 passed.
The checker effect reached COMMIT but rolled back because144 compared the outbox
payload with the approval response. PostingDocuments correctly emits the physical
posting/legs snapshot instead. SALE rejection and3 modularity tests passed.

147 was reserved before creation and changes that comparison to the exact expected
posting JSON derived from the two actual ledger legs, including identity, custody,
condition, title, quantity, unit, document line and endpoint; no approval/stock/episode
guard was removed. Actual source validation and all existing ledger rows remain.

Current .omo/runtime/asset-loss-guards.sh / .log selects WarehouseAssetLoss*IT
(2 primary journeys +10 guards) and ModularityTests3, expected15. Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/asset-loss-guards/xml;
private DB log asset-loss-guards-database.log. Do NOT claim147 applied or tests green
until execution confirms.148 next unused after147 applies. All143–146 immutable.
When these pass, run the relevant removal/return/compensation regressions, persist
sanitized verification and assess task28 acceptance before marking complete.
Whole-plan goal remains active;30–48/F1–F4 pending. No main merge/deploy/reset.

## Task28 active-loan loss query fixes — five-case verification running

0200fecf is published. Its asset-loss-effect run executed5 tests/2 failures,2m1s.
144 applied22:42:24.363 JKT and is IMMUTABLE:
41ffc26a006cdc7b55ac1ffd4e07e02bfc1b5abbd52c05d2bdce4bf846089708.
Both cases stopped in the shared deployment validator at an unqualified
`authorization_id` introduced by144.145 was reserved first and qualifies only
those references; no validation was removed.145 applied22:44:24.291 JKT, immutable:
95037d52b3ae6acf430c8a5c2c49d9ffafe7b47ac8931cf30812115c87bdeba4.

The asset-loss-effect-fixed run executed5 tests/1 failure,1m40s: SALE rejection
and3 modularity cases passed. LOAN reached draft source capture then failed on
PostgreSQL precedence in `body->'evidence'-'receivedAt'`.146 was reserved first
and adds only the necessary parentheses in the143 function, forward-only.

Authored10 WarehouseAssetLossGuardsIT scenarios plus shared fixture: real recovery
while approval waits -> durable stale; raw SQL effect/assignment closure denied;
unknown receipt cost; simultaneous approvals; reject/fresh request; requester
through delegation; revoked replay; scoped/redacted pagination; projection rebuild
with dated telemetry preserved; pending replacement authorization retirement.
These10 cases are NOT verified yet. Current .omo/runtime/asset-loss-evidence-fixed.sh
/ .log still selects only WarehouseAssetLossIT2 + ModularityTests3 to verify the
complete effect first. Archive task28/asset-loss-evidence-fixed/xml, private DB log
asset-loss-evidence-fixed-database.log. No146 apply or LOAN green claim yet.

After that run succeeds, run all12 asset-loss cases + relevant existing removal,
return disposition/compensation and modularity regressions. Keep task28 OPEN until
this source path and its guards pass. All143–145 bytes immutable;147 next unused
once146 applies. Whole-plan goal active;30–48/F1–2–3–4 remain pending.
Remote checkpoint command uses explicit local key (see previous note).

## Task28 active-loan loss effect checkpoint — validation pending

143 applied 22:34:18.972 JKT and is immutable:
d613a31245fe294a38113ff109e3fb52b4467d348f2d6d642cae02bc23f57936.
Request run: 5 tests, 2 failures, 1m56s. Both fixtures first stopped before the new
API because signature replacement lacked correctionReason. Fixed fixture signed
assessment revision then ran5 tests/2 failures/1m50s: LOAN reached new document
insert and exposed a source FK error (handover ID differs from its posting document
ID); SALE material-summary helper was not appropriate after accepted sale. The
request now resolves the actual acceptance operation ID;144 fixes the immutable143
assertion forward. Fixture reads WO revision directly and does not manufacture stock.

144 was reserved BEFORE creation and now implements the ASSET_LOSS approved effect:
LOSS policy / one LOSS movement / exact paired CUSTOMER_INSTALLED -> LOST legs,
original title and quantity retained, one DISPOSED event and immutable approval
source. It seals current source and closes the existing assignment. Customer and
fulfillment implement public inventory ports for atomic episode retirement and
provisioning outbox; original deployment/handover/obligation rows stay intact.
New loss-linked ONU event/retirement records preserve customer history. Unconsumed
permits depending on the lost assignment are retired and cannot later be consumed.
Existing removal and return validators remain intact; historical title/deployment
validation accepts only a complete sealed loss effect. RLS, old/new deferred routes,
replay, current source checks and exact ledger projection reconciliation apply.

Current .omo/runtime/asset-loss-effect.sh / .log selects WarehouseAssetLossIT (2)
and ModularityTests (3), expected5. Archive task28/asset-loss-effect/xml; private
DB log asset-loss-effect-database.log. No144 apply or green effect claimed yet.
Check execution before editing SQL. Once applied144 is immutable;145 next unused.
Next add stale/recovery/title, self/delegate, competing approvals, direct-SQL
forgery, scope/replay, unknown cost, old permits and rebuild guards; then task28
completion evidence. Task28 and whole-plan remain OPEN;30–48/F1–F4 pending.

Push f88ba8a5 initially failed public-key authentication. Explicit local key works:
env -u GIT_SSH_COMMAND -u GIT_SSH git -c core.sshCommand='ssh -i /home/fajar/.ssh/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes' push origin HEAD:refs/heads/feat/warehouse-workorder
Confirmed f88ba8a5 published. Do not print private key/env credentials.

## Task28 active-loan loss draft checkpoint — validation pending

Added InventoryAssetLossApi, request/get/scoped-list at /api/v1/warehouse/asset-losses,
WarehouseAssetLossService/Store/models and HTTP error mapping. Request binds an
accepted LOAN handover, active ISP assignment, exact physical identity/position,
assignment/title/WO revisions, current evidence object and original receipt cost.
Replay preserves the original draft after current authority/location/cutover checks.
No customer name, address, evidence object key or cost appears in the public view.

V175.143 was reserved before creation. inventory_asset_loss_request is forced RLS
and append-only, captures actual assignment/asset/segment/balance/handover/customer
installation/ONU revision/evidence/WO/cost rows, and seals one DRAFT0 ASSET_LOSS
header/line with no posting. Do NOT assume143 applied until the run log confirms.
All migrations through142 remain immutable. Next SQL must use144 after143 applies.

Current .omo/runtime/asset-loss-request.sh / .log selects WarehouseAssetLossIT
(LOAN full approval journey, SALE rejection) and ModularityTests (3), expected5.
Archive task28/asset-loss-request/xml, private DB log asset-loss-request-database.log.
The approval owner/effect/episode retirement are not implemented yet, so the LOAN
journey is expected to stop after draft/replay/read at approval. Inspect actual
execution and migration state before editing SQL or claiming test results.

Next: ASSET_LOSS maps LOSS policy and posts exactly one approved LOSS from customer
custody to LOST, closes assignment and retires its customer episode in the SAME
transaction, persists recovery closure and a provisioning outbox. Revalidate all
captured source revisions; stale/rejected decisions cannot move stock. Extend
historical deployment/title validation forward to accept the sealed loss closure,
without fake removal/return records. Add independent/delegated, stale/recovery,
concurrent, SQL-forgery, replay/scope, rebuild and title/customer-property guards.
Task28 and whole-plan remain OPEN. Prior6 actual reuse cases and30 combined cases
are GREEN and portable evidence was published at04ffd8ef (product567a3113).

## Task28 asset compensation VERIFIED; active loan loss remains open

The compensation-asset run against f87b1f21 passed 6 tests / 3 suites, zero
failures/errors/skips, 2m49s. Both LOSS and SCRAP assets were actually recovered,
compensated, reset/inspected, issued and installed to a different customer. An old
correction using the CURRENT return revision was denied; committed replay stayed
nonphysical and all old/new assignment and ONU histories remained intact.
The existing plain asset reuse test and 3 modularity tests also passed.
Portable evidence: .omo/evidence/warehouse-workorder-asset-provenance/task28/asset-compensation-verification.json.
Owned QA stopped; volumes retained. Product main remains 567a3113. All work through
f87b1f21 was pushed to origin/feat/warehouse-workorder.

Scope assessment: task28 needs an approved LOSS path for an unrecovered ISP loan,
not only inspected RETURN dispositions. Current active assignments remain recoverable
and cannot be written off. Implement a separate document-bound ASSET_LOSS request
and LOSS-policy approval, preserving original deployment/handover records and title,
retiring the assignment and customer episode atomically with a single LOSS posting.
Require actual asset/assignment/title/WO/source revisions and evidence; exclude SALE
customer property, independent self/delegate approval, no fake physical removal or
return intake. Recheck changed installation/title/recovery state at decision time.
Expose recovery closure from approved loss without changing the original obligation.
143 is next unused; reserve before creation. All migrations through142 immutable.
Task28 and whole-plan goal remain OPEN; 30–48/F1–F4 still pending.

## Task28 return disposition and compensation VERIFIED — asset reuse test running

The compensation-guards run against567a3113 product source passed30 tests/6 suites,
0 failures/errors/skips,4m54s. All20 earlier disposition/modularity cases plus2
LOSS/SCRAP compensation/reinspection journeys and8 compensation guards passed.
142 applied22:19:08.476 JKT and is IMMUTABLE:
f792dc3f6d64f6190dac9b575e5c74e66022a6fc41ae4922f8a12aeeb4cff83a.
Owned resources stopped with volumes retained. Portable sanitized evidence:
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-verification.json.
It records all30 names/counts, XML digests, source main-tree and138–142 checksums.

Compensation now restores exactly one whole piece to Q, reopens its outstanding
return obligation, requires fresh accepted inspection before availability, preserves
original posting/history, and produces one linked REVERSAL. Closed material
settlement, closure while approval waits, competing/rejected corrections, direct
SQL pending effects, available-bin restoration and revoked replay all behave as
required; current projections rebuild correctly.

New WarehouseCompensationAssetIT has2 actual LOAN LOSS/SCRAP recovery -> approved
compensation -> reset/inspection -> normal issue -> different customer installation
journeys. It then uses CURRENT return revision to attempt another correction of
the old disposition, expects SOURCE_NOT_VERIFIED409, and checks the new installation
and old episode remain intact. Original correction replay must stay nonphysical.
These2 new tests were not in the30-case run and have not passed yet.
Current .omo/runtime/compensation-asset.sh / .log selects those2, the existing
WarehouseReturnITReuse (1), and ModularityTests (3): expected6. Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-asset/xml;
DB log compensation-asset-database.log. Product main is unchanged since567a3113.

Task28 stays OPEN until the remaining acceptance and source-scope assessment are
finished. Assess the plan's loan-obligation approved-loss requirement before
claiming complete: currently disposition accepts inspected RETURNs, and compensation
accepts their exact loss/scrap movements. It cannot yet write off an unrecovered
active customer loan or arbitrary issued/warehouse/vendor stock.143 is next unused
SQL version; reserve before creation. All SQL through142 is applied immutable.
Whole-plan goal remains active;30–48/F1–F4 remain. No main merge/deploy/reset.

## Task28 compensation outbox checkpoint — 30-case regression pending

Published6cca7fd1 compensation-effect ran5 tests/2 suites,2 failures,0 errors/skips,
2m5s.141 applied22:16:00.258 JKT and is IMMUTABLE:
636454e183295ddb3e433cb7a2e1e5056a4881f8dacafc450a12d7ab4e8baac0.

Both real reversal requests and approval requests passed, and requester self-decision
was denied. Checker posting rolled back at inventory_outbox_event_kind_check:
the new DISPOSITION_REVERSED event needed a forward enum-constraint extension.
142 reserved before creation and adds only that event, preserving the existing
outbox check.141/140 and earlier SQL are untouched.

Added8 WarehouseCompensationGuardsIT cases: closed settlement before request;
closure after approval request -> durable STALE; concurrent corrections -> one
reversal; rejected source immutable/fresh request; available-bin destination denied;
revoked scope denies committed replay; raw SQL pending effect denied; rebuild
preserves exactly one restored piece and original posting. These are authored,
not yet verified. Specific installed/reused-asset compensation and broader loss
scope remain outstanding, along with task28 completion evidence.

Current .omo/runtime/compensation-guards.sh / .log selects WarehouseCompensation*IT,
WarehouseDisposition*IT and ModularityTests (expected30 tests/6 suites). Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-guards/xml;
DB log compensation-guards-database.log. Inspect execution before claiming142
applied or any compensation committed. Initial20-green disposition evidence remains
portable at adb4ca79; task28 remains OPEN. Next source change must preserve current
validation identity; no DB reset or applied-migration edits.

## Task28 compensation effect checkpoint — 141 authored, validation pending

Published base27ec2709 compensation-request actually executed5 tests/2 suites,
2 failures,0 errors/skips,1m59s. Both LOSS/SCRAP compensation draft201 and exact
replay succeeded; both stopped at approval/request because owner was missing.
140 applied22:10:14.007 JKT and is IMMUTABLE:
ebbc1297ff12996825eac5601ec7367b282e8f564b25eacd9eaaa35343f0fd4e.

This checkpoint adds WarehouseCompensationAdmission/Owner/EffectStore. It maps
DISPOSITION_REVERSAL to ADJUSTMENT policy, binds the compensation snapshot into
approval source, rechecks original source/current sink/WO/material/asset revisions,
and posts one REVERSAL linked to originalPostingId. A matching single
DISPOSITION_REVERSED outbox event is wired in PostingDocuments and approval event
lookup. The nonphysical warehouse.return.restore step advances only return history
back to RECEIVED_IN_INSPECTION/QUARANTINE. Old posting stays POSTED1; no original
ledger rewrite. Generic rework requires a fresh compensation request.

141 was reserved BEFORE creation. It captures approved live source, enforces one
compensation per original movement, exact paired legs/approval/operation/outbox and
return transition, and routes old/new row changes through deferred guards. It
extends140 DRAFT lifecycle and existing return validators forward. Existing139
settled-return calculation already excludes restored Q until fresh accepted
inspection. Current ledger guard allows later legitimate reinspection/reuse.

Current .omo/runtime/compensation-effect.sh / .log selects WarehouseCompensationIT
(2 full reversal/reinspection journeys) and ModularityTests (3). Archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-effect/xml;
DB log compensation-effect-database.log. No141 successful apply or test result
claimed yet; check logs before modifying SQL. Never edit140 or older applied SQL.
Next add closed-before/after-request, competing/rejected/duplicate corrections,
revoked scopes, raw SQL bypass, rebuild and reused installed asset rejection.
Task28/whole-plan goal remain active; vendor/outstanding loss scope still open.

## Task28 compensation draft implementation checkpoint — validation pending

The published20-green initial return-disposition evidence is adb4ca79 (product
18447663). Overall task28/whole-plan goal remain open. New work in this checkpoint:
InventoryCompensationApi, typed input/view/context/record, WarehouseCompensationService,
WarehouseCompensationStore and controller. POST/GET/list are under
/api/v1/warehouse/dispositions/{dispositionId}/compensations; GET detail adds/{id}.

Request requires original POSTED1 LOSS/SCRAP, its actual APPLIED movement and
linked latest return revision, whole remaining ISP sink position, no prior linked
compensation, no active assignment/reservation, and an open material lifecycle.
WO is locked through the public port before topology/return/stock. The new
source captures WO/asset/material revisions; destination is quarantine only.
Immutable actor/key replay returns original draft after current access checks.
List authorizes original locations before querying and filters new quarantine
destination scope before pagination; DTOs expose no cost/customer fields.
WarehouseReturnStore.position gains explicit status parameter default QUARANTINE,
with compensation passing LOST/DISPOSED. Existing callers retain Q behavior.

140 was reserved before creation. inventory_compensation_request has forced RLS,
append-only snapshots, actual original effect/ledger/return/material source capture,
and exact DRAFT0 header/line/no-posting seal. DISPOSITION_REVERSAL is admitted as
new document kind. New SQL is not yet known applied: inspect current run before
changing it;139 and older remain immutable. No compensation approval owner or
physical effect is implemented yet; 141 or later must extend lifecycle forward.

Current .omo/runtime/compensation-request.sh / .log selects WarehouseCompensationIT
(2 real LOSS/SCRAP reversal journeys) + ModularityTests (3). Archive is
.omo/evidence/warehouse-workorder-asset-provenance/task28/compensation-request/xml,
database log compensation-request-database.log. Expect new request to progress
to approval/request missing owner; do not call these5 tests green without logs.
No changes to main/deploy; checkpoints push HEAD:refs/heads/feat/warehouse-workorder.

Next effect: add ApprovalPostingKind.DISPOSITION_REVERSAL, map policy to ADJUSTMENT
without taking existing ADJUSTMENT owner from transfer discrepancy. Seal approval
source with compensation record; revalidate current sink and material revision
before decision and posting. One new REVERSAL movement compensates original ID,
restores QUARANTINE/QUARANTINE at target, preserves owner/quantity and original
posting. Exactly one explicit matching outbox kind; update both PostingDocuments
default kind and WarehouseApprovalStore.event selection. Add linked nonphysical
warehouse.return.restore step, original return history validators, new effect
capture/exact deferred guards and old/new table routing. Original139 effect already
allows later return revision and uses generic current ledger reconciliation.
Reject closed settlement, duplicate reversal, downstream reused/installed/consumed
state. Reinspection after restoration must be required before availability and
returned residual re-settlement. Task28 also needs broader loss scope assessment.

## Task28 initial return LOSS/SCRAP VERIFIED — compensation remains open

The disposition-verified run against18447663 product source completed20 tests/4
suites,0 failures/errors/skips,2m42s. All2 residual LOSS/SCRAP settlement journeys,
11 source/permission/concurrency/replay/integrity guards,4 serialized LOAN/SALE
cases, and3 modularity checks passed. Owned QA cleanup completed with volumes
retained. Portable sanitized evidence is committed at
.omo/evidence/warehouse-workorder-asset-provenance/task28/return-disposition-verification.json.
It contains every test name/count, XML digest, product main-tree identity and
138/139 checksums; raw private XML/logs remain excluded from commits.

Verified behavior: independent posting moves the exact quantity once, preserves
original returned quantity and old customer assignment history, and closes the
returned residual obligation without new physical postings on WO settlement.
Customer title, bad quantities, unknown cost, currency mismatch, direct SQL fake
effects, requester delegation, revoked scope and stale sources reject correctly.
Competing approvals produce one effect; both MM and serialized projections rebuild.
All10 old return/approval regressions also passed in preceding28-case mixed run.

Task28 is still OPEN. WarehouseCompensationIT has2 authored but unexecuted cases;
its proposed endpoint is not implemented. Next: a new DISPOSITION_REVERSAL source
kind with ADJUSTMENT policy, actual original movement linkage, independent approval,
current disposed position and closed-material lifecycle checks, and one paired
REVERSAL restoring QUARANTINE plus a nonphysical return-history step. Preserve
original posting; reject duplicate/reused/installed/consumed rollback. Broader
vendor/outstanding loss paths still need assessment.140 is available but NOT yet
reserved/created.138 and139 remain immutable. Overall goal continues beyond28.

## Task28 exact outbox/error contract checkpoint — 20-case rerun pending

The disposition-guards combined run against dac2434b completed28 tests/8 suites,
12 failures,0 errors/skips,6m18s. All10 return/delegation/expiry regressions and
3 modularity checks passed. New disposition source-stale, unknown-cost and scoped
paging checks passed. Failed positive posts raised
DISPOSITION_EXACT_APPROVED_POSTING_REQUIRED because PostingDocuments generated a
default DISPATCHED event in addition to supplied DISPOSED. Its new LOSS/SCRAP
mapping now derives DISPOSED, retaining the existing exactly-one-event DB guard.
No139 SQL changes;139 remains applied immutable.

Customer-owner and invalid-quantity requests were rejected in the service but
escaped as ServletException: the new controller was missing from WarehouseHttpErrors
assignableTypes. Added it, preserving existing error/status contracts. The new
delegation test failed while creating the grant (INDEPENDENT_APPROVER_REQUIRED):
its delegator was not configured in policy. Corrected fixture policy to include
requester + independent checker before requesting approval, then delegates after
request to test actual decision-time requester exclusion.

Added2 source-control guards (USD vs IDR policy and raw SQL pending-approval effect)
and docs/warehouse-dispositions.md with current supported workflow/limits. Current
.omo/runtime/disposition-verified.sh / .log selects only WarehouseDisposition*IT
and ModularityTests: expected20 tests (2 residual,11 guards,4 asset,3 modularity).
Archive task28/disposition-verified/xml, DB log disposition-verified-database.log.
Results pending; do not claim committed disposal until this run passes.

WarehouseCompensationIT is separately authored (2 LOSS/SCRAP cases) and NOT in that
run. It expects POST /dispositions/{id}/compensations, then independent ADJUSTMENT
approval of a new document, one REVERSAL linked to the original movement, restored
QUARANTINE, outstanding17.5m until fresh inspection, original history unchanged,
replay/duplicate protection. No compensation implementation or140 migration exists.
Use140 onward for new SQL; reserve before creation. Closed-settlement and reused
asset reversal guards still needed. Task28 remains OPEN and whole-plan goal active.

## Task28 approval event and asset coverage checkpoint — regression running

Published base0daab7e3 preserves applied139 (SHAa9a1f5567678d9b4841bad38b3f2fd79f4256efeeb65b8563fe442923de2e489).
The disposition-costed run executed5 tests/2 suites,2 failures,0 errors/skips,
2m12s. Costed draft and independent approval request passed; requester self-decide
returned403 as expected. Both actual checker decisions reached posting but the
transaction rolled back before commit: WarehouseApprovalStore.event selected only
older event kinds and threw NoSuchElementException for DISPOSED. Added that exact
event to its lookup; no SQL changes and no fallback success.

Added4 actual serialized-device cases: LOAN LOSS/SCRAP retain closed assignment
history and survive balance rebuild; SALE LOSS/SCRAP must reject customer-title
writeoff without creating a request. Shared serial receipt fixture now also uses
the optional actual-cost hook, default unknown for all prior fixture users.

Current .omo/runtime/disposition-guards.sh / .log runs WarehouseDisposition*IT,
WarehouseReturnSettlementIT, WarehouseReturnITIntegrity, WarehouseApprovalITDelegation,
WarehouseApprovalITExpiry and ModularityTests. Archive task28/disposition-guards/xml;
database log disposition-guards-database.log. Results pending; do not claim a
committed loss/scrap effect until this run proves it.13 new guard/asset cases are
included with the2 full residual settlement cases and affected regressions.

Next complete missing compensation as a NEW linked approved movement restoring
only quarantine, with a source-bound return transition and open/closed obligation
checks. Do not duplicate ADJUSTMENT owner (transfer discrepancy owns that kind).
Active/reused/consumed downstream state must reject implicit rollback. Vendor and
other outstanding loss paths remain to assess before task28 can be marked done.

## Task28 source-cost checkpoint — 139 applied, posting validation pending

139 is now APPLIED and IMMUTABLE. SHA256:
a9a1f5567678d9b4841bad38b3f2fd79f4256efeeb65b8563fe442923de2e489.
The first effect run failed42601 from ERaRCODE at SQL187 (4 tests,1 failure,
1m7s); Flyway rolled back21:46:37.717 JKT. The next run failed the guarded
return terminal-state anchor:117 had inserted DRAFT handling (4 tests,1 failure);
Flyway rolled back21:48:04.973 JKT. Both fixes preceded139's FIRST successful
application at21:49:43.056 JKT. Never edit139 or earlier applied migrations again.

The disposition-ledger run executed5 tests/2 suites,2 failures,0 errors/skips,
1m53s. Both real LOSS and SCRAP request201/replay paths passed. Approval request
correctly returned COST_BASIS_REQUIRED because the shared material fixture had
no receipt cost. No physical disposition has passed yet;3 modularity tests passed.

Added a shared fixture cost hook (default unknown preserves prior behavior),
WarehouseDispositionFixture with declared actual source receipt cost, and9 new
behavioral guards: unknown cost, bad quantities, changed inspection, rejection
and fresh request, competing approvals, requester delegation, revoked scope,
scoped paging and posted MM rewrite/rebuild. Guards are authored, not yet run.

Current validation .omo/runtime/disposition-costed.sh / .log selects the2 full
LOSS/SCRAP settlement cases plus3 modularity checks. Archive task28/disposition-costed/xml.
After it passes run WarehouseDispositionGuardsIT and affected return/approval
regressions; add forward migration140 if runtime invariants need correction.
Compensation, returned asset loss/scrap and remaining task28 acceptance still open.

## Task28 approved physical effect checkpoint — validation running

Supersedes the older draft-only status below. Published base7944bb50 includes
138; disposition-request actually ran4 tests/2 suites,1 failure,0 errors/skips
in1m46s. POST disposition201 and exact replay passed; the failure was missing
approval source owner at test line43. Flyway138 applied21:34:36.248 JKT and is
IMMUTABLE:34a2b183f2a4f1395981b5efdf5e14d3033ca9f7121b767c9d3f2ab52c74ea3b.

This checkpoint adds independent LOSS/SCRAP owners, admission revalidation,
approval-kind wiring, one paired physical posting and a linked nonphysical
warehouse.return.dispose operation.139 was reserved before creation. Its new
immutable effect captures the live source, binds both operations/approval/legs,
validates EA and MM current positions against applied ledger, extends existing
return histories/lifecycle forward, and counts approved disposal as settlement
of the original returned residual without counting another issued disposition.

The initial verification is .omo/runtime/disposition-effect.sh / .log; owned
private archive task28/disposition-effect/xml and disposition-effect-database.log.
It selects WarehouseDispositionIT + ModularityTests. No result or successful139
application is claimed yet; check logs before changing this SQL. Earlier138 and
older migrations must never change. Task28 stays OPEN; loss/compensation and
adversarial/scoping/concurrency/rebuild checks remain. Task26 remains COMPLETE
with its published53-test22-suite portable evidence at7c6eb6e5.

## Task28 draft request implementation checkpoint — validation pending

Task26 completion checkpoint7c6eb6e5 is published with53 green tests/22 suites and
sanitized portable evidence. Overall goal remains active. Task28 is NOT complete.

The real initial disposition-red baseline executed1 test/1 failure/0 errors/skips,
1m32s. All actual receipt/use/residual return/inspection/policy setup passed; the
new POST /api/v1/warehouse/dispositions returned404 at test line37. Archive:
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-red/xml.

Authored InventoryDispositionApi, typed LOSS/SCRAP input/view, controller/service,
WarehouseDispositionStore/Record. Initial request accepts exact ISP-owned RETURN
quantity in RECEIVED_IN_INSPECTION; SCRAP additionally requires DAMAGED. It locks
WO through the public inventory-owned port before warehouse topology/documents
and physical source, captures actual receipt/lot cost, checks custody/return and
approval-request permissions, and stores immutable actor/key/source snapshots.
Read/list expose no cost or customer fields; current location/area/site scope is
applied before pagination. No physical posting or approval owner exists yet.

138 was reserved BEFORE creation in docs/warehouse-migrations.md. It captures the
real return operation, current balances/asset/segment, WO revision and original
cost, and seals a DRAFT0 header/line with NO movements/operations. Draft-only
restriction must be extended forward when implementing real independent effects.

First disposition-draft compile succeeded;4 tests/2 suites/1 failure/0 errors/skips,
59s:3 modularity passed,1 context startup failed. Flyway138 failed42601 near CASE
inside the large IF at SQL line29 (position5663), and logged at21:32:45.351 JKT
"Changes successfully rolled back". No138 apply succeeded. Added parentheses to
that CASE before successful application; old137 and earlier files unchanged.
Minor get/list state handling was also tightened before the corrected build.

Corrected run is .omo/runtime/disposition-request.sh / .log, archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-request/xml,
database log disposition-request-database.log. It selects WarehouseDispositionIT
and ModularityTests. Confirm migration application before treating138 immutable.
The behavioral test should next reach approval/request; that owner and posting
are deliberately unfinished, so do not call4 tests green without actual results.

Next: implement independent LOSS/SCRAP owner + exact effect, nonphysical linked
return state transition, and settled-return calculation for authorized disposal.
Then add loss/vendor custody, compensation, and adversarial/scoping/replay cases.
Detailed design constraints follow below; no resetting DB or changing applied SQL.

## Task26 COMPLETE —53 combined tests passed

return-combined-check passed53 tests/22 suites/0 failures/errors/skips,
6m47s, against product source c050efeb. All return, recovered-loan reuse, original
customer RMA/reacquisition, source/title integrity, independent approval, scope
revocation, vendor repair/replacement, inspection/rebuild and pagination cases
passed together. Separate ordinary receipt inspection regression passed10 tests
in4m44s. Product source has not changed since that successful compilation.

Sanitized portable evidence is committed at
.omo/evidence/warehouse-workorder-asset-provenance/task26/verification.json:
actual suite/case names, nonzero counts, XML digests, main tree identity and131–137
SQL digests. Raw XML/logs remain private because they may contain HTTP tokens.
All applied migrations remain immutable; no QA volumes were reset. Task26's plan
row is now checked. Original-device CUSTOMER RMA is supported; distinct customer
replacement remains quarantined and cannot borrow the old device's permit.

Continue task28 (not complete): real17.5m scrap scenario and design notes are in
task-28.md / WarehouseDispositionIT. Its queued disposition-red run starts after
combined QA cleanup. Record its actual failure, then implement the document,
independent approval, exact posting, return transition and obligation settlement.
No task28 migration is reserved yet. Tasks28,30–48,F1–F4 remain open; the overall
goal is still active, not achieved. Commit and push each coherent checkpoint.

## Task28 behavioral checkpoint; task26 combined checks still running

Task26 compiled product source c050efeb is under return-combined-check. RMA
acceptance/deployment/guards/handover/reacquisition have passed so far; the full
suite is still pending, not a completed gate. No new SQL has been reserved.

Task28 adds WarehouseDispositionIT and task-28.md. The exact17.5m damaged residual
scenario is authored; .omo/runtime/disposition-red.sh is queued under the same
QA lock after task26. It selects only WarehouseDispositionIT, archive
.omo/evidence/warehouse-workorder-asset-provenance/task28/disposition-red/xml.
No disposition endpoint exists yet. Expected initial failure is POST404 after
real return+inspection setup. Do not call this task28 implementation or success.
Continue with its owner/approval/effect/settlement implementation after recording
that actual baseline; retain all task26 and137 immutability checks.

## Combined return regression restarted after pagination compile correction

Published f35e8621 contains the inspected replacement flow and scoped-list change.
First combined run stopped at main compilation (13s, no executed tests): Jackson3
JsonNode.map resolved to its member instead of Kotlin collection map. Corrected
with asSequence().map(...).toList(). No SQL changed;137 remains immutable. The
failed return-combined-regression/xml may contain copied stale receipt results;
DO NOT use them as combined evidence.

Corrected run: .omo/runtime/return-combined-check.sh / .log,
archive task26/return-combined-check/xml and return-combined-check-database.log.
Main compilation has passed; test compilation/execution remains pending.

Task28 preparation only: WarehouseDispositionIT now describes a real17500MM
damaged residual -> independent SCRAP approval -> exact disposed sink and WO
settlement. No disposition endpoint exists yet, so that test has not passed.
It is not selected by the combined task26 run. Do not infer task28 complete.

## Supplier inspection verified; scoped pagination under combined regression

Published parent checkpoint11c3b518.
return-replacement-inspection-green passed10 tests/4 suites/0 failures/errors/skips,
4m44s: replacement LOAN/SALE inspection and putaway/rebuild2, ordinary receipt
11-line disposition1, receipt inspection guards4, modularity3. Evidence archive:
task26/return-replacement-inspection-green/xml. Real private MinIO attachment used.
Receipt inspection derives expected owner from the immutable vendor replacement
request; ISP putaway remains mandatory. CUSTOMER inspection succeeds but stays
QUARANTINE, ISP inspection+putaway becomes AVAILABLE/SERVICEABLE. Both preserve
origin/old vendor custody and rebuild without extra movement.

Additional review found replacement list filtering after LIMIT. It now reuses
WarehouseQuerySql visible_locations (location/area/site ancestry) before pagination,
returning only public link views. One new guard puts a hidden draft before a
visible draft at size1. This pagination change is NOT yet verified.

Combined run queued/starting: .omo/runtime/return-combined-regression.sh and .log,
archive task26/return-combined-regression/xml, database log return-combined-database.log.
It selects WarehouseReturn*, WarehouseSupplierRepair*, WarehouseCustomerRma*,
WarehouseSupplierReplacement* and ModularityTests under the host QA lock. Confirm
nonzero full counts and zero failures/skips before marking26 complete.137 remains
immutable; this checkpoint contains no SQL edits. Full new-device CUSTOMER RMA is
not inferred from same-asset RMA permits. Task28 explicitly handles old-device
loss/scrap; receipt of a replacement does not dispose the old physical device.

##137 verified supplier replacement policy and current position

Published checkpoint a0d5f17b contains136. It applied at21:05:46.921 JKT;
20 tests/5 suites/7 failures/0 errors/skips,4m35s. All7 failures were filled-cost
requests rejected before admission: JSON operator precedence interpreted `cost`
as JSON. Null-cost requests and existing receipt/approval/modularity passed.
136 is IMMUTABLE, SHA2569c1eb477eca82db04b769074e0f60d400637ea724953593515478594b0b5cb50.

Forward137 fixes only that cost expression and connects replacement current
asset/balance/movement dimensions to the existing APPLIED-ledger validator.
return-replacement-policy-green passed10 tests/2 suites/0 failures/errors/skips,
2m53s: supplier guards8 plus LOAN/SALE admission2. Actual approval+replay, unknown
cost, stale source/direct and approval, rejection/fresh request, concurrent drafts,
revoked-scope replay and raw CUSTOMER->ISP asset/balance rewrite all pass.
137 is now APPLIED AND IMMUTABLE, SHA256847e1c48684fba0f9d59bb987d3b682875c2e2023e78e10d45b46caf2a1502c3.
Evidence archive: task26/return-replacement-policy-green/xml; private matching
runtime .log and return-replacement-policy-database.log. No DB reset.

Next validation queued under the same host lock: return-replacement-inspection-green,
new2 LOAN/SALE inspection/putaway/rebuild cases, ordinary receipt disposition and
inspection guards, modularity. Its two source files remain a separate uncommitted
change: receipt inspection derives CUSTOMER title only from verified replacement;
normal putaway still requires ISP. Do not claim these tests have passed yet.
Task26 remains open pending this and combined return regressions. Task28 remains
open for loss/scrap/compensation, including explicit old-vendor disposition; do not
silently close old repair when a distinct replacement arrives.

##136 declared cost/rejection and8 supplier guards — first validation running

135 is APPLIED AND IMMUTABLE, SHA256: `ab998db9effece220beed9b979164b54aee915531ec81a3c9bc4f722ba1b2abd`.
The corrected run passed12 tests/4 suites/0 failures/errors/skips,1m45s:
LOAN/SALE vendor replacement2, ordinary receipt1, approval guards6, modularity3.
New replacement asset is distinct, real same-vendor RECEIPT, owner ISP/CUSTOMER
as captured, QUARANTINE; old asset remains vendor custody. Archive
return-replacement-posting-green/xml. Published25ebf655 includes the pre-apply
SQL delimiter correction; failed135 attempt fully rolled back, then correct apply.

136 reserved BEFORE creation and now authored. Optional SupplierReplacementCost
(totalMinor,currency) feeds normal receipt cost input. Null cost is omitted from
new input serialization so old134 canonical replay is preserved; no zero invented.
136 captures exact vendor-declared cost/denominator1 and freezes document-line
cost against intake. It supports DRAFT1/REWORK_REQUIRED only with matching rejected
approval; generic rework returns controlled409 directing a new immutable request.

New WarehouseSupplierReplacementFixture/GuardsIT adds8 cases: actual customer
replacement approval+cost+replay; unknown cost policy rejection; competing drafts
one new asset; direct stale source; durable approval stale+replay; rejection then
fresh request; current scope on receive replay; and SQL unposted CUSTOMER->ISP
asset/balance rewrite. That last case deliberately tests possible missing current
physical-ledger binding for a replacement with no installation history. If it
exposes a gap, use existing warehouse_assert_recovered_position (120) and final
asset/balance routing in a NEW forward version after136 applies; never weaken it.

Current `.omo/runtime/return-replacement-guards-green.sh` / matching log and
return-replacement-guards-database.log, archive return-replacement-guards-green/xml,
session96350 runs the8 new cases + prior12. Read log for actual compile and136
apply status; after successful apply136 is immutable. No137 declared/created.
Then finish further tenant/source/immutability checks as justified, replacement
inspection/reset and CUSTOMER original-customer handover/installation, old-vendor
custody disposition, packaged HTTP and remaining plan. Task26 still OPEN.

##135 first SQL attempt rolled back; delimiter corrected before any successful apply

return-replacement-admission-green compiled but failed Spring/Flyway startup:
SQLSTATE42601 syntax error near patch$, at135 line126. Log20:57:27.517 JKT explicitly
says changes successfully rolled back (also repeated in later test contexts).
The adjacent dollar tags at END $function$$patch$ accidentally contain the outer
DO $$ terminator. Inserted a newline between the tags.135 had NOT applied, so this
is a correction to an unapplied failed version;134 and all older bytes unchanged.

Current `.omo/runtime/return-replacement-posting-green.sh` / matching log, DB log
return-replacement-posting-database.log, archive return-replacement-posting-green/xml,
session74078 re-runs the12 selected cases. Check log for actual135 success before
any further SQL edits. Last70536553 published; correction follows in new commit.

Further functional gap found during source audit: replacement input currently has
no optional declared cost, while configured RECEIPT policy correctly rejects
unknown cost (COST_BASIS_REQUIRED). Add explicit optional public replacement cost
only from actual vendor evidence, never synthesize zero. Omit null new input field
from serialization to preserve canonical replay of existing134 requests; update
capture validator via a later declared version after135 applies. Then test the
actual replacement approval path, not merely ordinary receipt approval regression.
Rejection/new immutable request lifecycle and receipt source/physical graph guards
also remain pending, followed by inspection and original-customer handover.

##135 replacement admission/effect — first database validation pending

134 is APPLIED AND IMMUTABLE. Corrected initial creation (internal
ReceiptDraftContext/replacementDraft, normal HTTP input unchanged) now passes
LOAN/SALE request201 and replay. That run finished6 tests/3 suites/2 failures/
0 errors/skips,2m40s:3 modularity and normal WarehouseReceiptITReceive pass;
both supplier cases reach receive and are blocked by134's intentional DRAFT-only
REPLACEMENT_REQUEST_RECEIPT_BINDING. Archive return-replacement-draft-green/xml.

135 was reserved before creation. Source now adds one immutable replacement receipt
mapping per repair/receipt/asset/operation, capturing current old-asset/vendor/
return/WO source and checking actual RECEIPT/APPLIED legs/owner/outbox at commit.
Direct receipt and approval receipt use the same typed binding; owner defaults ISP
only for ordinary receipts. The new asset has real vendor RECEIPT provenance;
old physical asset/assignment are preserved. Receipt operation ID is generated
before admission to bind the deferred mapping to its actual operation. Approval
source includes replacement snapshot. SupplierReplacementAdmission locks original
WO before warehouse locks; current source is checked with receipt/return/asset
held before any admission or final approval decision. Authority/view replay still
checks current WO/locations.

First return-replacement-receipt-green attempt failed production compilation in14s
because AssetHandoverWorkOrderPort import pointed at port/outbound instead of the
public inventory package. Import fixed; no tests or135 application occurred in
that attempt (copied XML is stale). Corrected current run:
`.omo/runtime/return-replacement-admission-green.sh`, matching log/DB log,
archive task26/return-replacement-admission-green/xml, session28075. It compiled
both source sets and is running2 supplier,3 modularity,1 normal receipt and6
ordinary approval tests. Read log for135 apply/result; after successful apply
135 bytes are immutable. No136 reserved or created.

Still needed: real replacement approval path, competing distinct drafts/receives,
source mutation/scope/tenant/replay/SQL graph tests, controlled rejection/new-request
flow (135 presently supports only DRAFT0 and received receipt; generic replacement
rework is not implemented), replacement inspection/reset, CUSTOMER original-
customer custody/reinstallation, and explicit old-vendor disposition/history.
Then packaged HTTP proof and the rest of the plan. Task26 remains OPEN.

##134 supplier replacement request/source draft — first validation running

The supplier baseline compiled and ran2 tests/1 suite,2 failures/0 errors/skips,
1m33s. Both LOAN and SALE reached missing POST replacement-receipts404 after real
receipt/install/handover/removal/intake/inspection/vendor dispatch. Archive
`task26/return-replacement-red/xml`; no fake physical seed or invented provenance.

Source now implements InventorySupplierReplacementApi POST/GET
returns/{id}/replacement-receipts, strict request, original WO/return/asset locks,
current locations, tenant/actor/hash replay, a real nested RECEIPT draft with an
internal key based on new request UUID, and original customer/WO/owner binding.
New134 was reserved BEFORE creation and captures/seals actual vendor custody,
closed original assignment, repair case, prior return and normal receipt intake.
134 deliberately allows only DRAFT0 with no physical receipt effect; admission,
one-replacement consumption, subsequent inspection and handover remain pending.
CUSTOMER draft line retains CUSTOMER; no change to old asset or old assignment.

Current `.omo/runtime/return-replacement-request-green.sh`, matching log and
`task26/return-replacement-request-green/xml` (session25146), compiles/runs both
supplier cases plus3 modularity cases. Read log for compile/apply status. After
any successful134 apply its bytes are immutable. No135 declared or created.
Expected next missing behavior is physical receipt admission; first fix request
capture/binding if exposed. Last published e5d2cef1 is the fully verified direct
quarantine implementation:13/3/0/0/0,3m41s (6 title,6 approval,1 full history/reset/
replay/rebuild);133 and all earlier migrations immutable.

Next implementation must lock replacement source before topology/receipt locks
for BOTH normal receive and approval receive, validate current source before
approval final decision, choose admission owner only from captured replacement
binding, and seal one receipt/new identity per repair with an immutable effect.
Include replacement binding in approval source snapshot. Normal receipt payloads
and historical snapshot shapes remain unchanged. CUSTOMER inspection/return-to-
original-customer and old vendor custody disposition must remain explicit.
Then finish task26 packaged proof and remaining plan; task26 is still OPEN.

## Direct quarantine reacquisition verified —13 tests green

return-title-final-green passed13 tests/3 suites/0 failures/errors/skips,3m41s:
6 return title guards (changed source, competing approvals, SQL forgery/append-only,
rejection/new request, vendor repair after reacquisition, current scope/replay),
6 existing approval guards and the full direct quarantine -> ISP -> reset release
case. The latter also proves request/decision replay, changed payload conflict,
historical CUSTOMER/CUSTOMER/CUSTOMER/ISP/ISP views and projection rebuild without
new movements. Archive `task26/return-title-final-green/xml`.

133 is APPLIED AND IMMUTABLE, SHA256
`c874e69adf2098a8caf956ebf5918f6154f1b2188979601e45c40d40c0700410`.
132 is immutable (`e38b4f88081afc6c625ec60cb5e5da6fdc4e333f7bf2703e04c4541a19a57f47`).
Previous shared run:15 tests/6 suites/2 failures/0 errors/skips,4m4s. Its2 failures
were test expectations of200 for STALE; existing durable approval contract is409.
Those expectations now pass. Shared supplier repair2, return integrity3,
installed RMA reacquisition1 and modularity3 all passed that run.

Rejected immutable RETURN_TITLE requests now return controlled409 on generic
approval rework; callers create a new request with current evidence. Documented
API and updated test include this behavior. No historical SQL was edited.

Next: actual vendor replacement. New WarehouseSupplierReplacementIT specifies
LOAN/SALE genuine recovered repair -> replacement receipt request -> receive new
physical identity with normal RECEIPT provenance, same vendor/owner and quarantine.
Endpoint POST/GET returns/{id}/replacement-receipts is NOT IMPLEMENTED yet.
The baseline `.omo/runtime/return-replacement-red.sh` / matching log (session45699)
is queued/running after the final green run under the fixed host QA lock.
Read the log; no baseline outcome or new migration134 is claimed. It will use the
retained owned test DB/volumes and expects the new route to expose missing support.

Implement replacement through a real vendor RECEIPT plus immutable repair linkage;
CUSTOMER replacement must not become ISP available stock or acquire a fabricated
old issue/source ID. Preserve original asset/history/vendor custody explicitly.
Then inspection/original-customer return or ISP issue, packaged HTTP proof and
remaining plan. Task26 stays OPEN;28/30–48/F1–F4 remain. No134 declared/created.
Canonical remote is `git@github.com:waduh67/qqweasdjlkasdjkwqeqwe.git`; push only
`git push origin HEAD:refs/heads/feat/warehouse-workorder`.

##132 immutable;133 physical leg revision correction under validation

Checkpoint includes RETURN_TITLE independent approval owner, policy exclusions,
canonical approval source, one CUSTOMER->ISP quarantine posting and immutable
return-title effect/return revision. Current return/repair reads now use the
latest proven owner; original customer assignment remains unchanged.

132 applied and immutable, SHA256 `e38b4f88081afc6c625ec60cb5e5da6fdc4e333f7bf2703e04c4541a19a57f47`.
First run compiled production/tests, passed3 ModularityTests and failed the one
full reacquisition case at approval commit: RETURN_TITLE_EXACT_QUARANTINE_POSTING.
4 tests/2 suites/1 failure/0 errors/skips,1m49s. Evidence
`task26/return-title-effect-green/xml`; private DB log captures13:32:39.697 UTC.
The comparison incorrectly equated revisions of distinct CUSTOMER/ISP balances.
133 is a forward correction excluding only that per-dimension revision.

Current `.omo/runtime/return-title-guards-green.sh` / matching log (session91757)
runs direct reacquisition,5 new stale/race/SQL-integrity/rejection/repair cases,
ModularityTests, return integrity, supplier repair and installed RMA reacquisition.
Read current log before editing133: after any successful apply it is immutable.
No result claimed yet. New5 cases were not previously compiled. Owned QA cleanup
retains volumes; fixed host QA lock remains required. Prior131 request/replay201
and3 modularity cases passed; its only failure was the then-missing approval owner.

Next resolve this run, add current-scope/evidence/exact-history checks as needed,
finish supplier replacement with actual new-asset/vendor-receipt provenance,
packaged proof, then remaining plan. Task26 remains OPEN. Push only
`git push origin HEAD:refs/heads/feat/warehouse-workorder`; origin must remain
`git@github.com:waduh67/qqweasdjlkasdjkwqeqwe.git`.

##130 ten green;131 direct quarantine request is in its first validation run

130 is APPLIED AND IMMUTABLE, SHA256 `77379d190d8291dd7303f06fb4c19693e863b28a9653ba0faa6b8a25f079311b`.
The renewal run passed10 tests in3 suites,0 failures/errors/skips,2m47s:
6 RMA guards including scope restoration/new permit and competing distinct permits,
3 signed acceptance/key/history cases and full independently approved RMA ->
removal/inspection/new-customer normal reissue. Archive
`task26/rma-authorization-renewal-green/xml`.129 earlier passed4/2/0/0/0,2m30s.

Source now includes InventoryReturnReacquisitionApi POST returns/{id}/reacquisition,
strict input, current WO/return/asset/scope locks, canonical replay and a separate
RETURN_TITLE draft request.131 captures real CUSTOMER quarantine position,
original closed assignment/accepted handover and signature evidence, then seals
source/request/document binding. No stock/title posting is enabled in131. The
full direct-return test previously proved404 after a genuine recovered SALE.

Current `.omo/runtime/return-title-request-green.sh`, matching log and archive
`task26/return-title-request-green/xml`, runs that full case plus ModularityTests.
Compilation and first131 apply/status are pending. It should next expose missing
approval owner/policy dispatch; those and the approved posting/return revision
transition are NOT IMPLEMENTED. Read current logs before editing131; after any
successful apply it is immutable. No132 declared or created.

Needed next: implement RETURN_TITLE approval owner and policy exclusions from
requester/receiver/original handover/removal actors; approved CUSTOMER->ISP posting
must remain in QUARANTINE at the same custody/condition, append linked return
revision/operation without editing closed assignment, and require reset inspection
before availability. Adapt return inspection to view.legalOwner and strict return
history validator through explicit approved-title step. Keep one physical posting
and preserve current source/approval/cutover/replay fences. Then vendor replacement,
packaged proof and remaining plan.26 stays OPEN.

##128/129 title continuity; focused runs and missing direct-return route

rma-reacquisition-green completed17 tests/5 suites/2 failures/0 errors/skips,4m29s.
The4 previous shared regressions are repaired:2 acceptance races,2 allowed draft
DELETEs (all5 scope variants passed). Ordinary title approval, all3 RMA acceptance
and4/5 RMA guards passed, including simultaneous RMA installs. Failures:
1. RMA independent approval hits historical ASSET_REMOVAL_HISTORY_POSITION_BINDING.
  129 now uses the existing continued recovery/APPLIED ledger owner proof while
  keeping physical identity and original episode title immutable. See task-26.md.
2. Revoked pending install returns409 STALE_AUTHORITY, matching the existing WO
  epoch contract; the probe incorrectly expected404. Corrected to assert exact
  stale code and zero writes, then require a fresh permit after restoration.
  A unique RMA handover execution constraint may block that legitimate renewal;
  prove with the queued focused test before any source/schema correction.

127/128 are APPLIED AND IMMUTABLE.129 may already apply in the running direct-
quarantine red probe; check current status before edits. Migration bytes:
- V175_127__warehouse_draft_delete_return_row.sql: `1210635a11525370c6e35efcedc919239225a97b776d147907ee632f496eb024`
- V175_128__warehouse_rma_reacquisition_title.sql: `5c57b1faec84685ac29ecd7b09fb070f7645bcfe227aed3c178fc41fbf5c2da8`
- V175_129__warehouse_recovered_title_continuity.sql: `9fc3857eb00db6e7dbd4a237fa2e86e9d9b6b13387beae9999f6969ccb01965d`

Active/queued wrappers under the host QA lock (each same-name log/archive):
- return-reacquisition-red: real SALE recovery/inspection, new direct quarantine
  reacquisition route missing, NOT YET VERIFIED. Request test requires independent
  title approval, preserved old assignment, Q until inspection and replay no effects.
- rma-title-continuity-green: full RMA approval/removal/reissue plus3 physical
  ledger integrity cases (including unposted CUSTOMER->ISP rewrite). Pending.
- rma-authorization-renewal-red: one corrected scope/renewal/replay case. Pending.
Do not infer successful outcomes from stale copied XML on compile failures.

No130 declared. Direct quarantine API and supplier replacement not implemented.
26 and remaining28/30–48/F1–F4 stay open. Current work branch is
work/warehouse-completion; ordinary push to origin feat/warehouse-workorder only.

##126 regression recorded;127 draft deletion correction and RMA reacquisition probe

The completed rma-acceptance-green run has166 tests/5 suites/4 failures/0 errors/
skips,8m28s. RMA3, forward-fix3, provenance31 all passed; ownership72 had2 races
409 vs200, final-state57 had2 allowed draft deletions leave count1. Raw XML is in
task26/rma-acceptance-green/xml. New custody preview routes before locks and runs
DB validation after owner locks; focused simultaneous duplicate acceptance has
now PASSED in rma-reacquisition-red, whose other new RMA reacquisition test is
still running. Read actual result before title corrections; no128 declared.

127 is declared/created, NOT YET APPLIED by current runner (processResources ran
before creation). It preserves warehouse_transfer_binding_guard scope/bound-
transfer rejection but returns OLD for permitted DELETE. Previously RETURN NEW
silently suppressed every non-transfer draft deletion. Do not edit applied126.

The exact retained-pre125 handover now validates as warehouse_app. Its stored
origin still lacks both extension fields, same hash before/after validation;
archive task26/rma-origin-upgrade-green/probe.log.175.126|t confirmed. No claim of
a pre-migration hash (not captured by the original red probe).

Two extra RMA guard tests are UNVERIFIED: unacknowledged custody, wrong customer,
revoked scope before install/replay, and simultaneous duplicate installs. New
RMA reacquisition test is real signed title0 -> independent approval -> removal/
inspection -> normal reissue to a new customer. Checker fixture corrected to the
actual CUSTOMER_INSTALLED location (current compiled red still has earlier field
location, but source-title request precedes any approval decision). Next run should
include127, all4 failed shared cases, RMA3 acceptance and5 custody/install guards.
26 and whole plan remain OPEN; vendor replacement/direct-quarantine reacquisition
and packaged evidence still need completion. See task-26.md newest entries.

##126 applied — RMA acceptance3 and ordinary regression still running

V175.126 has applied in the running integration test and is IMMUTABLE. SHA256
`cd894209c09945e323c4cc80f397f6857bde25d7f066aad5071e28e57bda0e30`. Main/test compilation passed. All3
WarehouseCustomerRmaAcceptanceIT cases passed: signed non-posting acceptance with
CUSTOMER titleRevision0 (including public ownership read), cross-purpose mint-key
conflict409 and history decoding both normal/RMA sources.

Shared `.omo/runtime/rma-acceptance-green.sh` is still running, same-name log and
archive `task26/rma-acceptance-green/xml`. CustomerAssetOwnershipIT simultaneous
acceptance race has FAILED; most other observed cases passed. Read final XML and
response details before fixing. Source audit: new custodyView invokes a multi-read
DB validator BEFORE work-order/assignment serialization; previous preview did not.
Do not remove validation; investigate moving validation after the normal locks.
The read-only retained-pre125 origin validation is queued at script end and will
not run if Gradle fails, so run it separately after the lock if needed. The prior
red probe and fixed exact SQL are under .omo/runtime/rma-origin-upgrade-* and
rma-origin-upgrade-probe.sql.126 changes comparison only, no stored seal writes.

No127 declared. Next real probe: independently approved reacquisition after signed
RMA (source title revision0), then removal/inspection/reissue. Source audit finds
old title-request revision formula and current RMA-owner assumptions may reject it;
prove before forward correction. Also remaining actual vendor replacement and
packaged proof.26 and whole remaining plan stay OPEN. Save/push ordinary checkpoints
on work/warehouse-completion -> origin feat/warehouse-workorder, no main deployment.

##125 shared52 green — signed handover/key/history probes pending

Source567dc234 passed52 tests in3 suites, zero failures/errors/skips,4m47s:
real RMA reinstall, all48 CustomerAssetReplacementIT cases (including races and
SQL integrity) and3 RMA custody guards. Archive task26/rma-deployment-green/xml.
The unmatched CustomerDeploymentIT selector supplied no generic deployment cases;
those actual named suites still need the later shared regression.

Current checkpoint adds3 unverified WarehouseCustomerRmaAcceptanceIT probes:
non-posting signed customer handover, normal-vs-RMA mint-key conflict, and the
public inventory assignment history containing both execution source kinds.
The shared fixture first completes a real RMA installation for each probe.
Command `.omo/runtime/rma-acceptance-red.sh`, matching log, archive
`task26/rma-acceptance-red/xml`, queued after52 green. Read results before editing
production. No126 declared/created. Applied125 and earlier immutable.

API guide docs/warehouse-returns.md now covers actual material settlement and RMA
custody/authorize/install; signed acceptance and remaining vendor replacement/
reacquisition/packaged proof stay explicitly unfinished.26 stays OPEN.

##125 applied — original-customer RMA installation passed, shared run pending

V175.125 is APPLIED AND IMMUTABLE, SHA256 `61ce87ff20a7fcf0fc5eafc85c6618223a781074a853f6d0939b51d98eb5c00a`.
Read-only owner query during this run returned175.124|t and175.125|t. Normal
main/test compilation passed. Core WarehouseCustomerRmaDeploymentIT PASSED:
real acknowledged repair reinstalls the same physical asset for original customer,
CUSTOMER title retained, null issue, two historical assignments/ONU episodes,
one active episode, zero ISP availability and exact install replay.

RmaDeploymentSource is explicit; no fake material receipt/plan/issue IDs. Common
execution stores nullable receipt/plan ONLY for sealed RMA handover source, with
captured CUSTOMER asset/original assignment/SKU. Shared deployment result/document,
physical posting and episode validators remain in force. Inventory routes RMA
intent/consume to a dedicated service; WO owner validates REPAIR and current
assigned technician. Common mint/consumption/document writers are reused.

Current `.omo/runtime/rma-deployment-green.sh` and matching log/archive
`task26/rma-deployment-green/xml` still running custody guards and replacement
regressions. NOTE selector '*CustomerDeploymentIT*' matches no current class;
do not claim generic deployment coverage from that selector. Correct class names
are CustomerDeploymentFinalStateIT/ForwardFixIT/StrictInputIT/RootInputIT/
TwoCustomerIT/UpgradeIT, plus CustomerWarehouseProvenanceIT. Run appropriate
shared source/graph cases after the remaining RMA compatibility changes.

Next probes/fixes: signed customer handover for already-CUSTOMER RMA must be
non-posting (existing handover source loader assumes normal issue and PSB);
normal/RMA mint-key collisions and public assignment-history source decoding.
These are source-audit follow-ups, not yet reproduced tests. Then vendor
replacement, approved reacquisition and full packaged proof.26 remains open.

## Applied124 verified; RMA installation baseline and custody guards running

V175.124 is APPLIED AND IMMUTABLE, SHA256 `2c246b66a003534f27816277606f95447fb5d16de3f4efa0db5831bba246b2d2`.
RMA handover, supplier LOAN/SALE and ModularityTests passed6 tests in3 suites,
zero failures/errors/skips,2m23s. Archive task26/rma-handover-green/xml. Source
8ccd89d2 was pushed to feat/warehouse-workorder.123 remains immutable/79 green.

This checkpoint adds a shared real repair/REPAIR-WO RMA fixture, three custody
guard cases (wrong serial/work type, revoked receive/read replay, app-role source
rewrite/fabricated non-posting receipt) and an original-customer reinstall probe.
The latter expects purpose RETURN_CUSTOMER_RMA with null issue, then one actual
CUSTOMER installation/new ONU episode, old history retained and exact replay.
All4 tests are unverified: `.omo/runtime/rma-deployment-red.sh`, matching log,
archive `task26/rma-deployment-red/xml`. Check actual outcomes before production
changes. No125 declared or created yet. Current production remains8ccd89d2.

Next: bind RMA authorization/install to this acknowledged handover without a
fabricated ISSUE, preserve original sale title and episodes; then actual vendor
replacement, approved reacquisition and packaged proof.26/whole plan still OPEN.

## Applied123 verified; RMA handover implementation pending validation

V175.123 is APPLIED AND IMMUTABLE, SHA256 `fb47bf2fd2c6bf6c13fb1bac13addccacf5ad20628c609f8bbd619dbad6f1dde`.
The corrected run passed79 tests in16 suites, zero failures/errors/skips,3m56s:
serial LOAN/SALE once-only usage, omitted-close-history rejection and full material
lifecycle/rework/return closure. Archive task26/material-obligation-green-second/xml.

RMA custody baseline failed1/1 at missing rma-handover route404 AFTER a real SALE,
removal, warehouse recovery, supplier round trip and reset inspection. Archive
task26/rma-handover-red/xml,1m18s, zero errors/skips. Its main/test compilation
completed before new sources were copied from private staging; baseline ran123.

Current source adds dedicated CUSTOMER RMA handover: closed inspected repair,
original customer/assignment, assigned REPAIR WO, independent warehouse sender,
physical dispatch to transit and technician acknowledgement, unchanged title.
Inventory calls a workorder-owned validation port. Reads retain access to closed
WO history; commands/replays require current WO revision/assignment and scopes.
Declared124 captures origin, seals exact requests/legs/outbox and immutable
custody history. No regular ISSUE or ISP availability is created. Installation
permit/reinstall, vendor replacement and approved reacquisition remain unfinished.

Run `.omo/runtime/rma-handover-green.sh`, matching log, archive
`task26/rma-handover-green/xml`: custody case, supplier LOAN/SALE and ModularityTests.
Compilation/result/first124 application are pending; inspect before editing SQL.
Do not edit any successfully applied migration. Task26 and remaining plan open.

## Material obligation correction — verification pending

V175.122 passed76 tests in14 suites (zero failures/errors/skips). New serial and
omitted-history probes then failed3/3 on real valid fixtures. Declared V175.123
counts bound APPLIED serial deployment once and seals complete lifecycle source
keys. Its first bootstrap failed42601 reserved alias and explicitly rolled back;
only never-applied123 was corrected. Second run is
`.omo/runtime/material-obligation-green-second.sh`, matching log and
`task26/material-obligation-green-second/xml`;79 selected tests, outcomes pending.
Check actual applied status before any SQL edit. Ceiling122 is immutable.
Continue task26 original-customer sold RMA, vendor replacement, independent
reacquisition, then remaining plan. No whole-task closure yet.

## Active task:26 —2026-09-24

- Latest:175.122 applied and is IMMUTABLE. Core17.5m accepted inspection/close
  test passed; full lifecycle/rework run still active in return-settlement-green.
  Two new unverified serial-quantity and missing-close-history probes are saved
  here and queued in material-obligation-red.sh under the same QA lock. Read the
  newest task-26.md and final logs before claiming outcomes or editing SQL.
- Supplier/return12 tests, repair guard/module/contracts24, scoped reads/access4
  all passed with zero failures/errors/skips in their respective runs.
- New material closure test reproduced outstanding17500 after accepted17.5m
  inspection (expected0). This checkpoint adds declared V175.122: separate
  settledReturnBase from sealed accepted return, preserves historical returned
  quantity, binds snapshots/header and retains other close fences. No new stock
  posting on close; material summary now reports actual CLOSED lifecycle.
- Main/test compilation passed. `.omo/runtime/return-settlement-green.sh` and
  matching log, archive task26/return-settlement-green/xml; new closure plus
  lifecycle/rework suites. Check full outcomes and122 applied status before edits.
- Earlier migrations through175.121 are immutable.122 is in its first run.
  Latest task-26.md has receipts and source-audit follow-ups (serial used totals,
  omitted lifecycle snapshot lines) requiring real tests before correction.
- Continue actual vendor replacement, original-customer sold RMA handover/ack/
  authorization/install, approved title reacquisition and packaged proof.26 OPEN.
  Whole remaining plan28/30–48/F1–F4 remains active;25/27/29 closed.
- Work on work/warehouse-completion, ordinary push to feat/warehouse-workorder;
  no main deployment, database reset or agent delegation.

## Current integration checkpoint: 2026-09-24

- Transfer position correction passed37 tests in10 suites, zero failures/errors/
  skips. Optional source balance selection and document-owned transit dimensions
  fix mixed AVAILABLE/LOST stock and concurrent transfers of the same identity.
  All three real-DB regressions failed first. Legacy payload hashing is preserved.
- Packaged `qa.sh wave5` passed real public signup, transfer/count/independent
  approvals, stale recount and both JVM phases:14 response replays and16 stock/
  document/history snapshots. Artifact and reproduction are in `continuation.md`.
- Current follow-up is shared `qa.sh replenishment`, then closure25/27/29 and
  implementation26. No migration changed. Newest section overrides historical
  running/pending claims below; final whole-plan checks remain outstanding.
- Earlier combined regression117/1 failure/0 errors/0 skips had only an obsolete
  2-migration expectation versus9 combined migrations; corrected focused upgrade
  passed1/0/0/0 with preservation assertions. Chromium tooling launches.

- Resume from remote `feat/warehouse-workorder`, local continuation branch
  `work/warehouse-completion` in `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe`.
  Task29's separately verified checkpoint remains `work/warehouse-task29` at
  `9ccd5bb0`. No merge/deployment to main is part of this continuation.
- Cherry-picked all task25 source through `f4297aef`, task27 through `57bc533a`,
  and task29 through `9ccd5bb0`, in25→27→29 order, recording original SHAs.
  Shared error registration includes all three controllers. Approval dispatch,
  posting kinds and effect-event lookup retain both transfer and count paths.
  All nine imported migration files are byte-identical to their child branches.
- Tasks1–24 remain checked pending shared replenishment confirmation. Continue
  tasks26/28/30 and the remaining plan after closing the integrated child tasks.
  Read `continuation.md` in the plan's notepad directory for current commands
  and test receipts.
- Existing task29 QA environment/volumes are retained separately; generate a new
  owned environment to test the full migration order. Never apply the earlier
  child migrations out of order to task29's already migrated database.

## Current continuation: 2026-09-24

- User requests completion of the whole remaining plan with regular committed,
  pushed recovery checkpoints. Current workspace is on `work/warehouse-task29`;
  this checkpoint closes task29's outstanding local regression and HTTP proof.
- Task29:191 selected regression tests and1 clean-build live fixture passed with
  zero failures/errors/skips. Two packaged HTTP JVMs passed numeric replenishment,
  authorization, stale receipt rejection and persisted replay checks; eleven
  physical posting counts remained identical. Details and artifact SHA256 are in
  `.omo/notepads/warehouse-workorder-asset-provenance/task-29.md`.
- Global plan still has tasks1–24 checked. Inspect remote tasks25/27, integrate
  25→27→29 and verify the combined source before marking those tasks complete.
  Next implementation dependencies are26,28,30 followed by31–48 and final gates.
- Recovery: fetch all remote branches, read the newest entry here and the ledger,
  and prefer the newest integration checkpoint once published. Recreate the
  isolated QA environment; ignored runtime credentials/evidence are host-local.
  Continue normal explicit branch pushes. Do not merge or deploy main.
- Everything below is historical context; its former current-state labels do
  not override this dated continuation entry.

## Wave 5 Parallel Setup Published

- Task24 remains the latest completed task: 24 complete, 0 blocked, 28 pending. Ready lanes are task25, task27 and task29; no downstream implementation or checkbox change has started.
- Integration worktree remains `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume` on `work/warehouse-resume-20260916`, delivering checkpoints to `feat/warehouse-workorder`.
- Published setup-content base is `1f0564110a6a8a1d5361c8ab58c13dc7d71d08d8`. All three clean child worktrees and queried live remote child refs were created at that exact commit.
- Child recovery branches are `work/warehouse-task25`, `work/warehouse-task27`, and `work/warehouse-task29`, each with an isolated worktree under `/home/fajar/ftth/warehouse-wave5-taskNN` and its same-named remote child branch. Workers push only to those child refs, never the integration branch.
- Migration namespaces are reserved, not created/applied: task25=`V175.113`, task27=`V175.114`, task29=`V175.115`, with children allowed only before a higher migration is applied. Exact rules are in `docs/warehouse-migrations.md`.
- All host QA uses one bounded outer lock at `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume/.omo/runtime/wave5-host-qa.lock` across the entire up/check/Gradle/HTTP/stop/down lifecycle. Ports25432/29000/17880/14188 never overlap. Each child generates its own private env, marker and volumes.
- Shared warehouse contracts/errors/posting/documents/approval dispatch and the migration manifest have one integration owner. Task27 primarily owns approval dispatch; task29 must use existing generic documents/projections and not depend on a new task25 API.
- Reviewed child commits are cherry-picked by the integration owner in migration order, shared files reconciled once, then combined QA runs under the host lock. No main merge, rebase, force push or PR is authorized here.
- Portable execution map: `.omo/notepads/warehouse-workorder-asset-provenance/wave5-execution.md`. The child branches intentionally remain at the setup-content base; later integration-only receipts do not require resets or rebases.

## Current Status: Task24 Confirmed Complete

- Authoritative state is 24 completed, 0 blocked and 28 pending. Tasks25, 27 and 29 are now dependency-ready; none has started through this checkpoint. Task26 depends on25, task28 depends on26/27, and task30 depends on26.
- Corrected source is `d5344163b5bef69b2c483ced1bc41c90c1653183`. Source reviewer `ses_f4fee4e43ffefMaI3Uq4c5eotK` returned `CONFIRMED` with `safe_to_mark_task24_source=true`. Runtime reviewer `ses_f4fee4d34ffemYo7phG6cKJ5lR` returned final `CONFIRMED` with `safe_to_mark_task24_runtime=true` at `f3e73959fc43766ef38996b52eccb30687307a31`.
- Fresh non-overlapping evidence is exact compatibility 20 and focused 40, both with zero failures/errors/skips. Actual IAM/HTTP proves own-area 200; different-area, restricted-empty, null-area, foreign and missing 404; original JWT after revocation 404 and after restoration 200; legacy 37 plus V2 `82500 MM`/`82.500 M`, paging and portal privacy remain intact.
- Clean 19-task JAR SHA256 is `ccf100eb75a7838aaa026dba5dde74ade765d7be8fe757877e32233d9e219c35`. No SQL/migration changed and live ceiling remains `V175.112`. Cleanup left zero owned containers, JVMs, listeners, PID/private directories or temporary schemas; two volumes/images/data remain.
- Earlier task24 14+78 compatibility/import/source-gate evidence is carried only by unchanged identity; it is not represented as fresh or full-suite evidence. Historical catalog and NetworkEndToEnd caveats remain task46 work.
- Strict clean-code and task23's owner-approved documentation-only GPON boundary remain binding; no hardware certification is claimed.

## Historical Task24 Correction And Review Timeline

- Executor correction now has genuine red5/4, green targeted14/exact20/regression95, all green runs0 failures/errors/skips. Current step is checkpoint then clean build, real area HTTP/DB proof and cleanup; independent re-review remains pending. New customer-owner check uses existing CurrentAuthority scope semantics before facets; unscoped API/DTOs/inventory queries unchanged. See newest task-24.md.

- Source reviewer ses_f4fee4e43ffefMaI3Uq4c5eotK rejected checkpointa709b65a: Subscriber360's unscoped customer lookup permits cross-area legacy/V2 material exposure. Current step is failing-first IAM/HTTP reproduction and a customer-owned scoped lookup before any facets. Preserve the unscoped API for portal/background use.
- Runtime reviewer ses_f4fee4d34ffemYo7phG6cKJ5lR passed old14/78 cases without this area case; no aggregate approval. The historical executor claim below is superseded for this finding. Task24 unchecked; task23 and other reviewed behavior unchanged. No migration expected,175.112 immutable. Read newest task-24.md/EOF ledger.

- Task24 sourcec7e3647acb984cd48666ac49ff3a27e71b73691c is pushed. Exact13 twice, final14 including connection-loss rollback, and selected91 pass with0 failures/errors/skips. Clean19-task JAR SHA2568fc6327a7a37a3c834d685fb6bd7a3e65873fdf8e91edee3de9fbeabd1790f11 passed two actual isolated HTTP/DB journeys. No migration; live175.112 unchanged. Read newest task-24.md and local task-24/DoneClaim.md for complete scope, classified failures and source/artifact binding.

- Current step: independent task24 source/runtime verification, not more implementation. Task24 remains unchecked. Verify server-tenant background import behavior, explicit V2 deployment/legacy count distinction, additive owner provenance and portal privacy. Live multipart promotion was intentionally disabled; actual full promotion is DB-integration proof, not a claim from PROCESSING.
- Cleanup is complete: zero owned containers/JVMs/API/task listeners/PID files; local live manifests and dirty sentinel removed. Original environment/lock and two volumes/images/data retained. No temporary schemas created. Protected checkout, task23 status and GPON scope unchanged; no task25/later/PR/merge.

## Confirmed Task23 Snapshot (Unchanged)

- Authoritative top-level state is 23 completed, 0 blocked and 29 pending: tasks 1-23 are `[x]`; tasks24-48 and F1-F4 remain `[ ]`. Task24 is next. This is task23 completion only, not full-plan, task46, release or final-wave approval.
- Owner decision dated 2026-09-17: "yaudh skip aja dulu yg GPON ZTE/Huawei/FiberHome mah, buat sesuai yg ada di dokumentasi mereka aja". Physical ZTE/Huawei/FiberHome GPON capture, model/firmware certification and hardware validation are deferred and no longer block this plan.
- Traceability is now recorded in docs/gpon-profile-evidence.md: exact Huawei XPON mirror objects support bounded corrections; ZTE constants remain documentation-unverified compatibility assumptions; FiberHome's contradicted description/speed OIDs are explicitly unavailable. None is hardware-validated. No index formula or MAC-to-GPON identity was invented.
- Undocumented or unknown raw-index formats remain `UNVERIFIED` and quarantined. No decoder guess or raw-index relabelling is allowed.
- This decision does not remove existing GPON code or waive warehouse provenance, CPE ownership/freshness, temporal attribution, database/RLS, privacy, source-gate or strict clean-code requirements. All unrelated real DB/browser/stock gates remain intact.
- The HSGQ-E04I read-only field evidence is EPON-only. It is not ZTE/Huawei/FiberHome GPON firmware, mapping or certification evidence.
- Current tested/built source is `a028c6a934b191e2fabdc596000f4b40d8fd33eb`. Bounded GPON profile/parser corrections and direct fixtures are pushed. V3 ingestion/source/time logic and confirmed CPE/DB production blobs remain byte-identical; all SQL through175.112 is untouched.
- CPE-R2 reviewer `ses_f54d52690ffefwS6LHWH9xpiJt` and DB-R2 reviewer `ses_f54d5267bffeH6M87YAIQbBrrv` confirmed their `39295078930507d75b58213420c4bb63025d011d` scope. Their corresponding implementation and SQL blobs are unchanged by verify-03.
- Temporal/source reviewer `ses_f54d527eaffeQMhYzFuG21jaGJ` confirmed DISCOVERY-3 timestamp safety and T3 unique active legacy/no-ODP handling at assigned checkpoint `5e49bc4e4377d484629df18dd1692643c461ac04`. The review specifically accepted the named `appendUntrustedTime` helper, explicit typed/raw timestamp boundary, existing shared time policy and narrow legacy eligibility checks as clean, cohesive code.
- Source reviewer `ses_f54d527eaffeQMhYzFuG21jaGJ` returned `CONFIRMED` with `safe_to_mark_task23_source=true` at `d5e9a4fb09ebde79289d08a98e0596e28af94303`. Runtime reviewer `ses_f54d52609ffe9BiEMrmv2lwpMu` independently returned `CONFIRMED` with `safe_to_mark_task23_runtime=true` on the same artifact.
- Fresh current runs: exact WarehouseDiscoveryIT 142, SNMP 25 and collector 22, all zero failures/errors/skips. The default 30-minute exact attempt timed out before XML finalization and is not counted; the same validated environment, lock, filter and flags completed under a 45-minute bound with 142/0/0/0. No tracked harness timeout was changed.
- Clean JAR SHA256 `cc17447cd9ad0a5e383cef9734fcd7a185ee82b649163defef2e98f5bdb052cd` passed the real source-gate, delayed-A, privacy, mixed-clock and replay journeys. Cleanup independently verified zero owned containers, JVMs, listeners and temporary files/schemas; two volumes/data were retained.
- Prior CPE-R2/DB-R2 confirmations at `39295078` and V3 confirmation at `86419931` remain identity-bound receipts for unchanged blobs, not fresh 545/95 reruns. Historical catalog and `NetworkEndToEndIT` caveats remain for task46.
- Physical GPON validation is deferred by explicit owner decision. Huawei behavior is documentation-backed/not hardware-validated; ZTE remains documentation-unverified compatibility; FiberHome is unsupported where the prior profile contradicted available evidence; unknown connected raw indexes remain quarantined. No hardware or vendor-wide certification is claimed.
- Task24 is the exact next action. It depends on task23; tasks25/27/29 depend on task24 and no downstream task has independently started or become ready through this checkpoint.
- Strict clean-code requirement remains binding: cohesive small code, explicit types and errors, public ownership boundaries, no duplicate policy or silent success, and only narrow purposeful abstractions with focused tests.

## 2026-09-17 Owner Decision Superseding The Physical GPON Blocker

- This dated owner decision supersedes the current status of the historical blocked records below without deleting or rewriting them. Those records remain evidence of what was previously required and attempted.
- Physical ZTE/Huawei/FiberHome validation is deferred. Documentation/MIB traceability and offline fixtures now define the GPON evidence boundary, with explicit documentation-backed/not-hardware-validated labelling and fail-closed `UNVERIFIED` quarantine for unknown formats.
- Task23 returns from `[~]` to `[ ]` for revised-scope closure verification. It is not `[x]`, and task24 has not started.

## 2026-09-16 — User-requested remote recovery checkpoint

- User instruction: commit and push immediately, and keep the work position documented so another agent can resume if this VPS is lost. This supplements the existing immediate-push/no-merge policy; it does not authorize force-push, main deployment, or skipping verification.
- Latest recorded completion: tasks 1–22 of 48; wave 4 of 8. Tasks 23–48 and final gates F1–F4 remain unchecked. Source: the active plan and the 2026-09-15 task22 completion/checkpoint entries in `.omo/start-work/ledger.jsonl`.
- Exact next task: 23, discovery/auto-provision/CPE integration. Enforce the same warehouse-origin authorization for non-UI callers; observation must not create stock. Follow the full task23 references, acceptance criteria and QA in the plan.
- Historical evidence is not a fresh full-suite pass. This setup verified Git topology, note structure and the live remote only; it did not rerun product tests, complete a final gate, or start task23.
- Verified setup base: live remote `feat/warehouse-workorder` and both local branches started at `2105273f1de7783fdc2454de6bc9e4a3a86be38a`. The task-owned worktree is `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume` on local-only continuation branch `work/warehouse-resume-20260916`; pushes target `HEAD:refs/heads/feat/warehouse-workorder` explicitly.
- The protected initial checkout remains `/home/fajar/ftth/qqweasdjlkasdjkwqeqwe` on local `feat/warehouse-workorder`, with its two pre-existing dirty note copies preserved and no branch displacement, stash, reset, rebase, or file edit by the checkpoint worker.
- The prior ledger path `/home/fajar/ftth/worktrees/warehouse-workorder-asset-provenance` does not exist on this host and is historical only.

### Checkpoint procedure

1. Use the separate local continuation branch for checkpoint and later task work; never force checkout the feature branch out of the protected initial checkout.
2. Push each checkpoint commit normally with explicit refspec `HEAD:refs/heads/feat/warehouse-workorder`; stop on divergence and verify the live remote SHA rather than relying on tracking refs.
3. The repository had no effective configured Git identity during setup. Plan line 486 authorizes `fajarxfce <fajaralamsyah000@gmail.com>` through commit-scoped author/committer environment variables only; do not modify Git config or carry those variables into other commands.
4. At every later completed task or interruption boundary, update this handoff and append a sanitized ledger receipt in the same checkpoint. Record completed tasks, current substep, implementation commit, migration versions, tests actually run, verifier status, failures and exact next action. Leave incomplete or unverified tasks unchecked.
5. Preserve concise, sanitized verification summaries in tracked notes. Raw evidence, local session IDs and absolute evidence paths are not portable proof; rerun unavailable checks after recovery before claiming new verification.

### Recovery on a replacement VPS

1. Obtain this repository from its configured remote and check out remote branch `feat/warehouse-workorder`; the local continuation branch and current absolute worktree path are host-local setup details, not required branch names on a replacement host.
2. Reconcile the newest remote checkpoint with plan checkboxes and ledger receipts. Historical append-only notes can say "unchecked" after later confirmation; use the latest matching receipt. The planning draft describes earlier plan approval, not current implementation progress.
3. Recreate the isolated QA environment using the task1 scripts/runbook. Treat local databases, retained volumes, binary evidence and agent sessions as unavailable unless separately restored; this Git checkpoint is not a database/object-store backup.
4. Known unresolved regression from task22: `NetworkEndToEndIT` had 10 legacy-fixture failures expecting serial-only ONU creation (201), while the task20 provenance guard returns `409 USE_WORKORDER_ASSET_WORKFLOW`. Do not report the full suite green.
5. Resume `/start-work warehouse-workorder-asset-provenance --make-pr` at task23 only after confirming no newer remote WIP/checkpoint. PR delivery waits for all plan work and review; merging remains forbidden.

## Resume

- Remote target branch: `feat/warehouse-workorder`
- Local continuation branch: `work/warehouse-resume-20260916`
- Task-owned worktree on this host: `/home/fajar/ftth/warehouse-workorder-asset-provenance-resume`
- Verified checkpoint base: `2105273f1de7783fdc2454de6bc9e4a3a86be38a`
- Current product source SHA: `d5344163b5bef69b2c483ced1bc41c90c1653183`
- Current migrations: through `V175.112`; verify-03 added no migration and all SQL is unchanged from the confirmed `39295078` scope.
- Current task24 JAR SHA256: `ccf100eb75a7838aaa026dba5dde74ade765d7be8fe757877e32233d9e219c35`.
- Tasks 1-24: checked; tasks 25-48 and F1-F4: pending
- Active plan: `.omo/plans/warehouse-workorder-asset-provenance.md`
- Delivery mode: `--make-pr` after plan completion; immediate checkpoint pushes; no merge
- Resume command: `/start-work warehouse-workorder-asset-provenance --make-pr`
- Executor: `ses_f5b0136f1ffeJmjJSpMn6LnyGT`
- Task 22 verifier: `ses_f59e67eacffeVf28hlgEdebi2F` (confirmed/high)
- Historical task22 identity: migrations `V175.80` through `V175.89`; JAR SHA256 `b062548e6601935f073e7b12d468cb100497ff7ef1d88def78af99f37c84ac1c`. This is retained history, not the current task23 artifact identity.
- Next dependency-ready tasks: 25 transfer/discrepancy, 27 blind counts and 29 replenishment. No implementation has started through this checkpoint.
- Immediate normal fast-forward push is required; no merge.

## Verified State

Tasks 1-24 are checked with independent task24 source/runtime confirmation. Tasks25-48 and F1-F4 remain pending.

- Task 1: PASS after correction; isolated environment and fail-closed runner verified by `ses_f86494639ffe1UztWotWQf1lcy`.
- Task 2: PASS contract-only; strict public contracts, modularity, and packaged build verified by `ses_f86079a15ffeG2Er4fVXbaZqiC`.
- Task 3: PASS; exact quantity/identity tests, packaged probe, and regressions verified by `ses_f85d359e7ffeSj01vhkOZqkw17`.
- Task 4: PASS after forward-only corrections; schema, Flyway, RLS/GUC, tenant, and concurrency evidence confirmed by `ses_f8526de97ffeAXdgJag71WChmx`.
- Task 5: PASS after AV5 corrections; posting, custody, conservation, rollback, restart, and concurrency evidence confirmed by `ses_f8358f7b9ffezDBYdyrjPqXgXG`.
- Task 6: PASS after AV6 corrections; durable outcomes, current authority, outbox/inbox, replay, and concurrency evidence confirmed by `ses_f80ea045effes33EHCfzoajd7a`.
- Task 7: PASS after AV7 corrections; master APIs, strict decoding, topology, scope, restart, and privacy evidence confirmed by `ses_f8087024affeelaOdIEnVR6c8b`.
- Task 8: PASS after AV8 corrections; receiving, inspection, putaway, file validation, restart, and race evidence confirmed by `ses_f7bde4ef4ffepGrCcS6ZQv0d5K`.
- Task 9: PASS after AV9 corrections; bounded projections, filters, privacy, restart, and compatibility evidence confirmed by `ses_f7aee14dfffe6NP997sLvIMWMR`.

- Task 10: PASS after corrections; independent re-verification confirmed by `ses_f79532a97ffe5s56PUPGBJ6YKA`.
- Task 11: PASS after AV11 corrections; independent re-verification confirmed by `ses_f7719948affe0wmQEHImgzxMYe`.
- Task 12: PASS after AV12 corrections; approval, immutable effects, restart, and concurrency evidence confirmed by `ses_f73a315e5ffeibNLVDMKoYmrnu`.

## Exact Next Action

Resume one dependency-ready Wave5 lane: task25, task27 or task29. Coordinate shared environment and file ownership; task26 waits for25, task28 waits for26/27, and task30 waits for26. No later completion is implied.

## Continuation Policy

The active plan, draft, notepads, ledger, and this handoff are the portable state. Push immediately after every checkpoint commit; use regular fast-forward push only, stop on divergence, and never force-push. Do not merge or create a PR from this checkpoint.

`.omo/boulder.json` is intentionally omitted: it contains absolute main-checkout paths and ephemeral/stale running-session metadata, so it is not portable. Resume derives from the repository-relative plan checkboxes and the tracked ledger/handoff. Runtime state is not required for this checkpoint. Push immediately after every checkpoint commit using regular fast-forward push only; stop on divergence and never force-push.

## Excluded Data

No runtime env files, credentials, API keys, private keys, JWTs, connection strings, generated logs, screenshots, archives, binary evidence, database data, object-store content, build output, session caches, `.omo/runtime/**`, `.omo/evidence/**`, `.omo/run-continuation/**`, or `.omo/boulder.json` is tracked.

## 2026-09-16 Task23 Blocked Before Product Edits

- This entry supersedes the earlier next-action wording. Task23 preflight started from clean local/live remote0735b81c3fbc8ae8dd2c4b4f826b480d596db6a5, but stopped before baseline/red proof as required by the infrastructure gate. Tasks1-22 remain checked; task23 and all later tasks remain unchecked.
- Docker29.8.0/JDK21.0.12.1 are available. Isolated up failed pulling pinned minio/minio:RELEASE.2025-09-07T16-13-09Z with pull access denied; timescale/timescaledb-ha:pg17 pull was interrupted. Neither required image is cached. No registry credential or replacement image was guessed.
- Isolated check and exact WarehouseDiscoveryIT QA runner refused64 before Gradle because owned PostgreSQL is absent. Zero product tests, no real baseline/feature-red, no bootJar or manual HTTP/DB/privacy proof. Existing NetworkEndToEndIT10 caveat is unchanged and not rerun.
- No product/test/migration/harness/checkbox changes. Packaged SQL ends at V175.89; manifest reservations V175.90-.93 exist without SQL. Historical manifest applied-version text must not be treated as current DB proof.
- Stop/down exit0; no containers/volumes, task PID records or task-port listeners remain. Only default Docker networks exist. Private generated env/lock retained locally, ignored; no preexisting data removed. See tracked task-23.md for probe details.
- Needed external action: provide approved registry access or trusted restoration for the pinned images; if unavailable, approve and verify a compatible isolated-harness replacement separately. Do not weaken QA isolation or fall back to production.
- Exact current substep: infrastructure capability BLOCKED. Next: verify newest remote checkpoint, rerun up/check, then establish real legacy telemetry/source-gate baseline and genuine task23 red tests before implementation. Independent verifier remains pending; this is an incomplete notes-only checkpoint, not a DoneClaim.

## 2026-09-16 Isolated Infrastructure Recovered, Verification Pending

- Supersedes the infrastructure-blocked state above, not the task23 feature status. Only warehouse Compose MinIO pin and one direct WarehouseEnvironmentIT assertion changed. Task23 remains unchecked; no business logic or new migrations.
- Official same-release source: https://raw.githubusercontent.com/minio/minio/RELEASE.2025-09-07T16-13-09Z/README.md. Independently verified manifest-list digest sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e. Pin is quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e. Not a claim of byte equivalence with unavailable DockerHub content. Timescale and all harness safety settings unchanged.
- Standalone configuration assertion failed before the edit and passed afterward; original startup denial retained. Isolated up/check exit0; exact WarehouseEnvironmentIT executed7 tests twice, each0 failures/errors/skips, with real storage roundtrip/nonowner/RLS checks and actual compilation. Raw XML archived before rerun. Running release/digest and loopback bindings verified.
- Cleanup stop/down exit0, zero containers/owned JVMs/task PID records/task-port listeners; task volumes/images retained. LSP rejected external-worktree paths, compiler/tests used instead. No task23 baseline/feature-red, bootJar or full-suite claim; known NetworkEndToEndIT10 caveat retained.
- Exact current substep: infrastructure-only DoneClaim awaiting independent verification. Next after confirmation: run up/check, establish observable legacy active telemetry/source-gate baseline, then genuine task23 red tests and implementation. Do not start task24 or mark task23 complete. See tracked task-23.md and the latest ledger entry for portable evidence.
- Infrastructure repair implementation SHA: a61fdf5942063de1f994e1122744bfce6bcff9c7, immediately pushed and matched by live remote. Subsequent commits are safe notes only; task23 product implementation remains absent.

## 2026-09-16 Task23 Feature WIP

- Infrastructure was independently confirmed by ses_f56815f33ffe2OwxmAqXcjMN5o (fresh7/0/0/0); feature work resumed. Current implementation76abef67f9dac1837be63096838c4fb444334f78 is a pushed WIP, not a completion or approval.
- Baseline3 passed before production edits; genuine behavioral red4 failed before changes. Latest focused58 passed: direct task23 cases18, baseline3, modularity3, CPE regressions34. Broad final regressions, clean bootJar and built live HTTP/ACS/restart proof remain outstanding. Original failures/archives retained; NetworkEndToEndIT10 caveat unchanged.
- Forward SQL V175.90-.95 adds temporal live-state/path evidence, durable unassigned observations, episode-bound CPE snapshots and original discovery receipts. No applied predecessors changed and no task24/26/UI/mobile implementation.
- Exact current substep: complete task23 adversarial and live surface verification after this checkpoint. Isolated stack stopped, owned compiler daemons terminated, both volumes retained. Read task-23.md for scope/proof details; never mark task23 checked before independent feature verification.

## 2026-09-16 Final Artifact Verification Next

- Product head d67215edbc966216cd8622bead980d35cd601df6 is pushed. Current exact feature31, monitoring/CPE/provenance148, and customer/task22 batch235 pass with zero failures/errors/skips. SQL V175.90-.100 is forward-only; original source/hash histories remain intact.
- Final corrections cover parameter-level ACS freshness, actual assignment revisions, global ACS owner ambiguity (including suspended tenants), precise episode boundaries, recovered-device rediscovery, current-authority replay and deferred conflict mapping.
- The first built HTTP/DB/ACS and crash/restart proof passed; repeat it on the final clean JAR with timestamped ACS simulator fields before DoneClaim. Current substep is final artifact QA/cleanup, not another implementation task. Task23 remains unchecked and independent feature verification pending.
- Wider schema129 had one unchanged task22 catalog-string mismatch, characterized against175.89; do not claim it or the full server suite green. See task-23.md for retained failure evidence and precise next commands.

## 2026-09-16 Task23 DoneClaim Ready For Independent Verification

- Product head a3e9be5331bd02b8e2e8459f8686790fe127a281 is pushed; task23 is implemented and executor-verified, not independently approved. No checkbox changed. Tasks1-22 remain checked; task24 and later remain pending.
- Exact WarehouseDiscoveryIT33 passed twice with0 failures/errors/skips. Selected bounded gates148,235,64 passed;374 distinct passing cases across overlapping selected archives. Baseline3 and genuine red/green proofs are preserved. No full-suite-green claim.
- Final clean JAR SHA2568c298ec0d91544ec21bc4a20deebf6070a539e9762b971f876f262e377dae5e5,19 tasks executed. Actual curl/DB/owned ACS proof passed on17880/17881, then SIGKILL/restart retained original replay, exact stock/history counts and B privacy. Global SMTP health remained intentionally unconfigured; readiness/DB/MinIO succeeded separately.
- Forward migrations V175.90-.101, all predecessors unchanged. V175.101 fixes authenticated tenant/collector batch scope and retention, exposed by final live QA. No source/custody/WO/RLS validator bypass; no task24/26/UI/mobile work.
- Cleanup verified zero owned containers/JVMs/schemas/PID records/task listeners; two task volumes and images retained. Only runtime env/lock retained privately. Sanitized raw evidence is ignored; portable details are in task-23.md and ledger.
- Known caveats: unchanged schema catalog assertion in WarehouseSchemaIT129/1, plus historical NetworkEndToEndIT10 fixture failures. Exact next action is independent task23 AdversarialVerify using feature/DoneClaim.md, changed-files.txt, source-hashes.txt and archived XML. Do not self-approve or advance task23 until that verifier confirms.

## 2026-09-17 Rejected Task23 Recovery WIP

- Supersedes earlier DoneClaim: source review lanes NEEDS_FIX. Forty correction paths after2453c5db are being secured in focused commits before further edits. Focused38/0/0 passed; topology regression UNRUN.
- Applied ceiling175.105 confirmed live; immutable hashes/checksums and detailed remaining work are in newest task-23.md. Reserve new forward versions, never rewrite applied SQL.
- T1-T8, malformed-time behavior, global-fence lock order/selective GUC, exact repeats, regressions, fresh JAR and live/restart proof remain mandatory. No approval from old runtime scenarios.
- Owned stack/compiler daemons retained only during active use. No active test/API/simulator at inspection. Cleanup and normal push/live-SHA proof required before return; preserve volumes/data/images.
- Resume newest remote feat/warehouse-workorder checkpoint, then run WarehouseDiscoveryITReviewTopology. Task23 unchecked; no task24.
- Code/test checkpoint5ca646532be26a0a9dda47e10eaf07d8ab88043a is pushed/live-SHA verified with clean worktree;19 focused commits after2453c5db. Exact40-path inventory is appended in task-23.md. Still WIP, not corrected DoneClaim.
- Temporal WIP now has84 combined passing cases after genuine T1/T2/T3/T5/T7 and corrected T4/T8/fence reproductions; see newest task23 note for caveats and immutable106-109 hashes. T6, expanded DB/timing/retention qualification and final artifact QA remain open. This update does not mark task23 complete.
- Retry/DB qualification now passes14 scoped cases, producer17+22, and first exact task23 run81. T6 is stable transactional409/retry, not removal of every lock conflict. Populated marker upgrade and chunk retention now pass. See latest task23 note and observation-contract.md; final repeat/regression/build/live/restart/cleanup remain outstanding.
- Broader regressions now545 customer/provenance and95 monitoring/CPE pass after documented fixture and real bulk/legacy-bound corrections. Two final exact repeats and live artifact/restart/cleanup still required. Manual conflict seeder is unrun WIP until recorded otherwise. Do not infer completion from these counts.

## 2026-09-17 Corrected Artifact Ready For Independent Repeat Review

- Supersedes pending executor QA above, not the independent rejection. Source tree11b0e18f9eb380988a1148deaa5f370e789e3076 is pushed; final notes-only checkpoint follows. Task23 remains unchecked/unapproved; no task24.
- Final exact83 twice; bounded545 and95; producer17+22; manual seeds2+1 all passed with zero failures/errors/skips.742 distinct final selected cases across overlapping archives. Clean executable JAR SHA25692f4bb760b2f55523a9c8169f2b172f4a9bee8412658b7298001b435c4ec8ded,19 executed build tasks.
- Actual built HTTP/DB/ACS202/future/conflict and SIGKILL/restart passed. Original replay/counts persist; delayed A cannot alter B, future stays unassigned, and new competing owner blocks B reads/actions. Current SMTP health caveat is explicitly retained; readiness/DB/MinIO passed separately.
- Cleanup verified no owned containers/JVMs/temp schemas/PID records/task listeners. Original two volumes/images/data and private env/lock preserved; owned manifests removed.28 complete sanitized XML archives plus source inventory/hashes and corrected DoneClaim stay ignored under task23/verify-01.
- Applied SQL through175.109 immutable. All per-finding evidence, conservative GPON limitation, stable retry behavior and old catalog/NetworkEndToEnd caveats are in newest task-23.md and DoneClaim. All four source lanes and runtime must rerun independently on this artifact; prior runtime pass is not aggregate approval.
- Resume only independent task23 verification from newest remote feat/warehouse-workorder. Never treat this executor DoneClaim as permission to check task23 or start task24.

## Verify-02: Source Review Still NEEDS_FIX

- Second CPE/DB source review rejects67dc9513185ca907e0704125781fbf89d636ab76 despite scoped runtime success. Latest task23 note lists six CPE-R2/DB-R2 findings and additional timing/isolation qualification. Reviewers: ses_f54d52690ffefwS6LHWH9xpiJt and ses_f54d5267bffeH6M87YAIQbBrrv.
- Resume correction in the task-owned worktree only. First publish this notes-only checkpoint, then genuine failing-first proofs. Applied migrations through175.109 stay immutable; reserve new versions before creation. Temporal/discovery reviewers read immutable67dc9513 concurrently and do not own mutable QA state.
- Task23 remains unchecked/NEEDS_FIX. No task24/PR/merge. Previous DoneClaim and83-case runtime pass are historical scope evidence, not aggregate approval. New evidence and corrected claim belong under task23/verify-02, with normal checkpoint pushes and full owned-resource cleanup.
- Verify-02 corrections and qualification are now checkpointed with125 exact tests twice,545 customer and90 monitoring/CPE regressions passing. Source hash/chain/interval guards110-112 are applied and immutable. Manual seeds3+1 passed; clean artifact/live success/stale/pre-POST/restart/cleanup still pending. See newest task23 note, not the old83-case claim.

## Verify-02 Artifact And Cleanup Complete, Independent Review Pending

- Built/tested source6b06b14298111dc7f12346541e83f898efbdea6b is pushed; final notes-only handoff follows. Final exact126 twice, customer545, final monitoring/CPE95, gateway17 and seeds3+1 pass with zero failures/errors/skips. Classified earlier failures remain evidence.747 distinct final selected cases across21 full sanitized XML archives.
- Clean executable JAR SHA256cd0d1aeb566e185088086b74ad8637c362afc64f6c5268430e5acac1d78c1a68,19 executed tasks. Real HTTP/DB/NBI valid diagnostic success, static/late stale withholding, pre-POST owner loss, hash/chain/interval denial and SIGKILL/restart passed on the same artifact.
- Applied SQL through175.112 immutable; no predecessor rewrites. Cleanup verified no owned containers/JVMs/temp schemas/PID records/task listeners; seven generated manifests removed, original env/lock and two volumes/images/data retained.
- Read task23/verify-02/DoneClaim.md and latest task-23.md for exact findings, protocol assumptions, counts, hashes and preserved caveats. All affected source/runtime reviewers must repeat on the final artifact. Task23 remains unchecked/unapproved and task24 must not start.

## Verify-03 Narrow Ingestion Correction

- CPE-R2 and DB-R2 reviews at39295078 are CONFIRMED by ses_f54d52690ffefwS6LHWH9xpiJt and ses_f54d5267bffeH6M87YAIQbBrrv; preserve that scope. Temporal/discovery still require DISCOVERY-3 safe extreme timestamp quarantine and T3 known-unattached GPON telemetry compatibility. No aggregate task approval.
- User explicitly requires strict clean code: small cohesive functions/classes, explicit types/errors, public boundaries, no duplicated policy/silent success, only purposeful abstraction and focused tests. No unrelated refactors or CPE redesign.
- Entry39295078930507d75b58213420c4bb63025d011d is clean; prior verify-03 attempt was only rate-limited. Use verify-03 evidence, targeted tests and real HTTP proof; defer full suites. Connected mapping must remain blocked if authoritative vendor/firmware evidence is unavailable. Task23 stays unchecked; task24 must not start.
- Targeted source checkpoint864199313485a88521ae5b767b0f45ccf9ee62c3 now fixes extreme clock quarantine and unique active legacy/no-ODP GPON telemetry. Live18, scoped46, SNMP17 and serialization6 pass; standalone built HTTP/DB confirms sibling preservation, inert replay, episode-only data and mismatch denial. CPE/DB guard blobs and SQL remain unchanged. Strict clean-code requirement remains binding.
- Connected GPON positive mapping remains BLOCKED: obtain paired raw SNMP index/serial and authoritative vendor/firmware CLI/API port identity across multiple ports/ONUs plus MIB semantics. No decoder guessed or raw index relabelled. This is not full task23 compatibility or approval.
- Cleanup complete: owned API/listeners/compilers/containers/PID and private live files removed; original env/lock and two volumes/images/data retained. Read verify-03/DoneClaim.md and newest task23 note. Next is targeted temporal/discovery review and external mapping resolution; full suites wait for source scope settlement. No task23 checkbox/task24 advancement.
