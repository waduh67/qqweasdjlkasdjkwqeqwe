## Task43 unvalued opening policy evaluation verified; actual approval/posting NEXT

15tests PASS: Opening4,PolicyEvaluation6,DurableReceiptApproval2,Modularity3.
Safe proof task43/opening-policy-verification.json; final runtime log
migration-opening-policy-final.log. No new SQL migration in this phase.
V177.6 remains immutable; next free177.7;178reserved forM06. Goal ACTIVE.
Owned QA stopped, volumes retained. Task43 still OPEN;44–48/F1–F4 outstanding.

MigrationOpeningPolicyContext looks up the actual sealed opening, takes batch advisory
before owner scope locks, requires exact current review/no issues, and reads batch
requester. PolicySource has explicit OpeningPolicyContext for review location,
participants and extra current customer/WO areas. Empty physical lines are allowed
only with this actual source context. No artificial PolicySourceLine is inserted.
WarehousePolicyEvaluationService evaluates every configured tier for an opening;
value numerator/denominator/currency remain null. Source costs of ordinary receipts
still require a known basis under their existing policy rules. Public evaluation
keeps cost fields omitted and explains unknown historical cost/all-tier review.

Tests place FOUR distinct actors in policy candidates and prove exclusion of batch
creator, resolution reviewer, file uploader and opening requester. Policy candidates
and both sides of delegation must cover all current customer/WO areas in addition to
warehouse scopes. Current customer area changes deny old-area callers/candidates;
updated scopes permit evaluation. New resolution makes old opening evaluation stale.
An approval-view actor can inspect evaluation with full source scopes; no provenance
management permission is silently required of independent approvers.

NEXT implement real WarehouseApprovalOwner for OPENING_BALANCE and narrow batch-bound
DB admission/stock posting. DurableApprovalService request and non-ENFORCED decisions
are STILL CLOSED for this source; no approval record/stock created by the new preview.
Existing durable receipt flow and unknown receipt-cost rejection passed regression.
MigrationOpeningPolicyContext currently uses VALIDATING-only batch lock. Actual
approval get/replay after finalization must instead support immutable batch read locks.
WarehouseApprovalAuthority must recheck extra current source areas for delegates too,
not just rely on initial evaluation. Seal/source hash must bind current source-owner
scope snapshots as well as latest review so a changed customer/WO area invalidates
an existing approval. Use owner public reference contracts/views, never module internals.

Existing stock-origin constraints also need precise approved-opening support:
warehouse_assert_opening_approval is a stub inV174.2; warehouse_origin_guard rejects
all UPDATE origin changes, including legacy null->approved origin. Do not bypass these
broadly. Migration owner admission must be narrow, tenant/fence/approved batch-bound,
fixed search_path, original selected asset ID/raw identity preserved. Single posting
owner must admit and create the approval effect atomically; zero baseline needs a
real control posting with zero physical legs. Pending cancellation receipts,
reservation of postboot legacy identity candidates, final exclusive epoch flip,
provenance UI, mixed-tenant API+restart proof/M06 and remaining plan still required.

## Task43 opening review sealed and verified; independent approval/admission NEXT

V177.6 is APPLIED and IMMUTABLE, SHA-256
702c8a58dfdbecba78faa274bf1a7422ae9193e8fa97b58790714816a242f566.
Next free V177.7; V178 remains reserved for M06. Product gate10PASS2m1s
(Opening3,Resolution4,Modularity3); final strengthened Opening3PASS1m2s.
Safe proof task43/opening-review-verification.json. Runtime wrappers/logs
migration-opening-gate and migration-opening-final. Owned QA down, volumes retained.
Goal ACTIVE:1–42 DONE;43–48/F1–F4 OPEN. Continue to completion and push checkpoints.

Actual endpoints on WarehouseProvenanceController:
GET /batches/{batch}/review, POST /batches/{batch}/opening,
GET /batches/{batch}/opening/{id}. Prefix /api/v1/warehouse/provenance.
WarehouseOpeningBalanceService now implements the real batch-bound overload; the
old freeform multipart endpoint remains closed until its UI is replaced.
Request fields expectedEpoch,expectedReviewHash,reviewLocationId,
expectedReviewLocationRevision,migrationReference,reason plus Idempotency-Key.

warehouse_migration_review_manifest derives full captured sources + latest resolution
original JSON, ordered table/sourceId. Hash is DB jsonb text SHA256. Active AVAILABLE
asset/balance and pending movement/checkpoint/outbox require review; history may keep
null resolution, never auto-approved provenance. warehouse_migration_review_issues
rederives exact baseline/master snapshot and current source hash; requires matching
duplicate winner revision/identity. Selected asset/balance double counting is blocked.
Current implementation loops selected asset peers; optimize with set-based identity
groups if large-tenant profiling shows a bottleneck. Do not edit applied177.6.

Store creates an actual OPENING_BALANCE DRAFT + only selected BASELINE_STOCK lines,
stock identities still null until approved admission. No supplier, cost, purchase,
claim promotion or stock posting. Empty tenant has zero physical lines; real active
review warehouse/bin identifies policy scope without a fictitious SKU/quantity.
Opening response freezes manifest, review location, current owner-source refs,
requester/auth/cutover epochs and request metadata. Immutable DB request binds exact
command/response, manifest, original document and expected derived physical lines.
All sealed draft/line mutations currently fail closed; forward approval migration
must allow only its real approved admission/terminal transitions. Same actor/key/body
returns exact original even after later resolution, with current source scopes and
original files rechecked. Missing actual object fails replay. SQL999-unit forgery
fails seal and rolls back the cloned draft; direct edit/delete fails.

NEXT actual independent opening approval using existing DurableApprovalService,
WarehousePolicyEvaluationService and single WarehousePostingService. Do not introduce
a second approval or posting owner. All configured tiers must review unknown value;
valueNumerator/valueDenominator/currency remain NULL, never zero valuation. Existing
DB approval columns already allow null pairs; Kotlin store currently requireNotNull.
Include request actor + batch requester + every resolver/evidence uploader in excluded
participants. Require candidates/delegators/delegates to cover all current source
WO/customer areas, not only stock/review warehouse. Existing policy source rejects
empty lines; explicitly represent review metadata separately without fake line.

Current integration restrictions to change narrowly with real opening proof:
DurableApprovalService.request takes ORDINARY_STOCK, decision invalidates non-ENFORCED;
WarehousePostingService and assertReceiptApproval likewise requireENFORCED.
ApprovalPostingKind needs OPENING_BALANCE and an actual owner. Approval source content
must include sealed manifest AND current review/current source scope validation so
later resolutions make it stale. WarehouseApprovalSourceLock should take batch advisory
before owner WO/topology/customer locks; get/replay afterENFORCED must support frozen
batch reads without warehouse_lock_migration_batch's VALIDATING-only restriction.
Opening SQL seal currently blocks ALL document/line mutations, including rejection
approval_disposition. Request drafts may repeat review hash under new keys; actual
posting must enforce unique business identity per batch across all proposals.
The old freeform opening form needs replaced by provenance workbench.

Physical admission: reuse original selected asset IDs, preserve raw serial/MAC/legacy
SKU fields; set warehouse SKU/unit/verified fields only through narrowly checked
owner function. Existing warehouse_admission_guard and warehouse_claim_guard allow
migration owner, not application promotion; use approved batch-bound guard/function,
fixed search_path and tenant assertions, never a general bypass. Bulk/lot baseline
may create provenance OPENING origin lot with no supplier/cost. Historical duplicate
rows stay staged. Same transaction must admit, create single conserved posting and
approval effect, then future finalization validates and advances epoch ENFORCED.
Zero baseline uses approved control posting with zero legs, never fake physical leg.

Still required: approved pending legacy cancellation receipt and sealed terminal
checkpoint behavior; current MANUAL/APPLIED guards are preserved. Reserve every
current unresolved canonical identity before finalization, including postboot/new
or changed legacy rows; do not allow missing boot claims to be stolen by new receipt.
Keep old claims/candidates/history. C10 active selected conflict and units validation,
mixed tenant full packaged boot+HTTP reconciliation+restart, actual provenance UI,
M06 then remaining44–48/F1–F4 all still outstanding.

## Task43 legacy fulfillment cutoff verified; opening approval NEXT

V177.5 is APPLIED and IMMUTABLE. Next free version:177.6;178reserved. Final gate:
16tests PASS, BUILD SUCCESSFUL2m8s (FulfillmentMigration2,Coordinator5,Capture2,
Resolution4,Modularity3). Safe proof task43/legacy-fulfillment-verification.json.
Owned QA containers/network stopped; volumes retained. Goal ACTIVE:1–42 DONE;
43–48/F1–F4 OPEN. Continue the whole scope with commit/push recovery checkpoints.

Legacy WORK_ORDER checkpoints without fulfillment_approval_snapshot and their outbox
records now join current/frozen provenance source views (ten kinds). Fulfillment owns
capture through InventoryMigrationEffectsPort; no raw payload, error/outcome message
or worker identity is exposed. Source snapshots contain actual IDs/hashes/state/effect
progress. Pending checkpoint and outbox counts are separate. Pending sources require
CANCEL_PENDING proposals; APPLIED/manual history stays provenance-only. No cancellation
is executed yet: independently approved permanent cancellation receipts remain required.

InventoryProvenanceWorkOrderPort is implemented in workorder, checks current area and
returns safe code/customer references. Source access order is cutover/current authority,
optional batch advisory, WO shared locks, topology/location, customer shared locks.
Customer coverage includes the WO's current customer. Tenant-wide summary/cases take an
exclusive read fence: READ_COMMITTED live sources cannot appear between scope checks
and counts. GET still does not persist cases or mutate stock. Evidence/resolution reads
use the already frozen batch membership.

FulfillmentCoordinator now treats MANUAL_RESOLVED as terminal in accept/process and
preserves terminal outcomes during failed retries. Worker reconciliation cannot reopen
APPLIED/FAILED_PERMANENT/MANUAL_RESOLVED. Added explicit cutover fences to save, enqueue,
ACK and effect-progress persistence methods (claim methods already had them). Tests
prove real HTTP report waits on a writer changing WO area, then denies old-area access;
worker ACK waits on exclusive cutover; exact manual/APPLIED replay from fresh transactions;
old pending payload twice returns FULFILLMENT_SNAPSHOT_REQUIRED reconciliation with zero
new physical effects.17 current sources captured, including6 fulfillment sources.

NEXT: actual independent OPENING_BALANCE workflow using existing durable approval and
single posting owner, not a parallel approval/ledger. Seal actual resolution/file/master
manifest under batch lock; require proposals for active stock and pending effects, keep
historical/unknown-installed sources staged and excluded. Derive quantities from source;
no invented price, receipt or zero valuation. Explicit unvalued review must use every
configured tier; keep real costs null. Empty validated tenant needs independent control
approval without fake physical lines. Exclude batch/request/resolution/evidence actors.
Approvers need all current source areas, including WO/customer areas, not only baseline
warehouse areas; evaluate candidate eligibility accordingly. Opening evidence must be
available via existing approval source/evidence reads under current scope.

Existing integration points: WarehouseOpeningBalanceService is still a fail-closed stub;
DurableApprovalService.request currently requires ORDINARY_STOCK, decide marks non-ENFORCED
stale, and rework needs opening-specific new-request handling. WarehousePolicySource maps
OPENING but requires lines; Evaluation rejects unknown cost except title correction, and
WarehouseApprovalStore.insert requires nonnull value numerator/denominator. Add explicit
unvalued all-tier semantics, never reuse title's internal0/1 as an opening cost. Approval-
PostingKind lacks OPENING; SQL permit validation and posting stage guards need forward
changes. WarehousePostingService is ENFORCED-only. Empty baseline requires a narrow typed
control posting, not a fake stock leg. Finalization remains closed in app and DB.

After approval: atomic original-asset admission/claim promotion/opening posting and
approved permanent legacy-effect cancellation; exclusive count/unit/collision/effect
checks -> epoch+ENFORCED. Unknown installed history remains staged with IDs intact.
Then /warehouse/provenance UI (still absent), mixed-tenant realHTTP+restart and browser QA.
Branch work/warehouse-completion -> origin/feat/warehouse-workorder. No merge/deploy.

## Task43 current cutoff snapshots verified; legacy fulfillment effects NEXT

V177.4 APPLIED and IMMUTABLE; next free177.5,178reserved.13testsPASS/BUILD SUCCESSFUL
2m54s: Capture2/Boot2/Query2/Resolution4/Modularity3. Safeproof task43/current-cutoff-
verification.json. OwnedQA containers/network stopped;volumesretained. Goal ACTIVE;
1–42DONE;43–48/F1–F4OPEN. Continue whole scope, commit/push coherent checkpoints.

The stale Flyway snapshot gap is fixed. Read-only live owner views provide current
source data beforebatch; GET doesnotpersist. Begin exclusivecutover waitsoldwriters
then checks actualreviewhash. Changedsources get immutable newcaseversions; identical
sources reuse originalcaseID. Deterministic SHA-derived candidateIDs make previewID
matchcapture. inventory_provenance_case uniqueness nowtenant/table/sourceID/hash;
no oldrows deleted. InsertguardrequiresinitialVALIDATINGexclusivefence+actualowner
snapshot+stableID and refusesafterbatchseal. Inventorycapturesown7sourcekinds;
customer rootport captureSources obtainsactualONUwhitelist+currentareaauthority.
Databasechecks refer to RLS-invoking publicowner sourceviews. No directinventory
Kotlinqueryonprivatecustomertables. BatchtriggerrequiresALLactualcurrentsources
captured, derivesmanifest/hash. warehouse_report_provenance_case useslive data before
batch andexactfrozenmanifestafterwards; eligibility ignoresobsoletehistoryversions.

CaptureITrealHTTP holdsactualappDBsharedcutoverlock, changeslegacyquantity, starts
BEGIN, observespg_blocking_pids (no sleeprace), commitswriter: BEGIN409STALE_REVISION.
GETshows91000but old82500snapshotunchanged; refreshBEGIN201capturesnewversion and
exactreplay. Addedsourceincluded, removedprojectionexcludedfromcurrentmanifestbut
oldcase preserved. All previous query/resolution/boot regressionspass. No newdocs/
verifiedstock/admission. Currentreportcases are the activegeneration; rawhistorical
versions remain inbase table, notsilentlydeleted.

NEXT177.5: capture pendinglegacyfulfillmenteffects throughinventoryrootport implemented
infulfillment. Existing InventoryTenantPolicyPersistence.beginValidation onlyrecords
inventory_movementstate!=APPLIED; FulfillmentCheckpointPersistenceAdapter claim/claim-
Pending/claimOrCreate useCONTROL_PLANEfence. Actualownerwork istransactional, but old
pendingoutbox/checkpointIDs also need cutoffmanifest and terminalreconciliation so
oldreplay neverpostsafterbaseline. Use actualcheckpoint/outbox/effectprogress, not
fakeinventoryfacts or rawpayload disclosure. FulfillmentINVENTORY executor currently
approvals.verify (no newstockeffect); retain history and distinguish alreadycompleted
frompending. Cancel proposals still proposals, notexecutedmutations.

Then: seal actualresolutions/evidence/master revisions; reuseexistingdurableapproval
owner andsinglepostingauthority forOPENING_BALANCE with explicitunvaluedfulltiers,
no fake0cost or receipt. Emptyvalidated tenant needs independent controlapproval
withoutfakephysicallines. Admitwinner actualassetID+claims atomically; oldduplicate
rows remain staged. FinalizeexclusiveepochENFORCED aftercounts/units/conflicts/effects.
Unmatched/unknowninstalled provenanceonly remainsstaged,neverISPavailability. Add
/warehouse/provenance UI (stillabsent), mixedtenantrealHTTP+restart andbrowserproof.
Branchwork/warehouse-completion ->origin/feat/warehouse-workorder; no merge/deploy.

## Task43 resolution proposals verified; current cutoff capture NEXT

V177.3 APPLIED and IMMUTABLE; next free177.4,178reserved. Nine tests PASS1m59s
(Modularity3/Query2/Resolution4), then Resolution4 PASS1m with added directSQL
quantity-forgery/strictinput assertions; product unchanged. Safe proof task43/
migration-resolution-verification.json distinguishes runs. Owned QA stopped;
volumes retained. Goal ACTIVE:1–42DONE;43–48/F1–F4OPEN. Continue until full scope.

POST/GET /provenance/batches/{batch}/cases/{case}/resolutions now stores immutable
revision history: BASELINE_STOCK,PROVENANCE_ONLY,DUPLICATE,CANCEL_PENDING. Exact
batch/case/source-hash,1–10 real existing file IDs/readback, expectedrevision,
actor/key/body replay. Database independently derives stock from original data:
serial1EA, balance explicitEA/MM/M exactintegerconversion; original active tenant
location +activeSKU/revisions +ISP title required. Cannot inventquantity/price,
reinterpret knownunits, admit installed/customer-owned or count serialbalance
again. Duplicate binds actualsamephysicalasset's currentproposalrevision; old
rawrows remain. CANCEL_PENDING is a proposal only; actualmovementstillpending.
No verifiedstock/claims/docs/posting/finalization. Database derives/fences metadata;
resolutionhistoryUPDATE/DELETErevoked+appendonly. Allordinarywritesstillclosed.

Platform orphan report fix: currenttenant missinglocation reference is shown only
as preserveddata withoutforeignname; ordinaryoperator stillneedsfullsourcecoverage.
Orphan cannotbeBASELINE_STOCK. Realtests coverforeignlocation, competingrevisions,
revokedoldJWT/replay, unchangedrawIDs/status anddirectSQLquantityforgery rejection.

CRITICAL NEXT beforeapproval/admission:177 snapshots are currently captured at
Flywaytime, butLEGACYwriters maychange/add/delete sources beforeBEGIN. Implement
current source capture/versioning under exclusivecutover, retaining old snapshots,
withoutadmitting staleboot quantities. Report should showactualcurrentrawsource;
batchmustfreezeexactwatermarkmanifest afteroldwritersdrain. Customer snapshot work
stayswithcustomerowner via inventoryrootport, no directinventoryprivatecustomerSQL.
Capture pendinglegacyfulfillmentoutbox through ownerport aswell. Then independent
batch approval through existingdurableowner, explicitunvalued/allconfiguredtiers,
openingposting/claimpromotion/finalization (samepostingauthority), UIandHTTPmixed
stages/restart. Duplicatewinner/master revisions needrevalidation atseal.
/warehouse/provenance UI stillabsent; read web/DESIGN.md9 before work.

Branch work/warehouse-completion -> origin/feat/warehouse-workorder. Commit/push
coherentchunks withhandoff. No merge/deploy; originalwarehouse-task29preserved.

## Task43 private evidence checkpoint verified; case resolution NEXT

V177.2 is APPLIED and IMMUTABLE; next free177.3,178reserved. Real HTTP+MinIO
EvidenceIT3 PASS / BUILD SUCCESSFUL1m3s after final object-key/label/epoch guards.
Supporting Modularity3/Query2/Policy11 PASS in preceding19-test green run2m12s.
Safe proof task43/migration-evidence-verification.json distinguishes both runs.
Owned QA containers/network stopped; volumes retained. Goal ACTIVE,1–42DONE;
43–48/F1–F4 OPEN. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

POST/list/GET /provenance/batches/{batch}/cases/{case}/evidence now uses real private
ObjectStorage, exact multipart request+file, PDF/PNG/JPEG<=15MiB, SHA/readback checks,
immutable batch/case/source-hash metadata and actor/key/body-bound original replay.
Download/replay fail closed when object is missing/corrupt. Fresh-transaction cleanup
waits the same batch advisory lock, retains committed/unsettled objects, and rejects
keys outside the exact supplied tenant/batch/case/evidence ID. Tests lose actualHTTP
response, terminate only owned app DB backend after write, and verify rollback cleanup.
No receipt/intake/stock is fabricated. PROVENANCE_RESOLUTION enabled only VALIDATING;
MIGRATION_APPROVAL/BASELINE/FINALIZATION still hardclosed pending their actual owners.

Shared WarehouseProvenanceAccess factors current provenance.manage plus current full
source warehouse/customer-area scope. Batch command lock order: cutover -> current
authority -> batch advisory -> topology/customer -> case/document. Immutable batch/case/
evidence tables have UPDATE revoked: use warehouse_lock_migration_batch/advisory locks,
not SELECT FOR UPDATE on them. V177.2 function validates actual batch and epoch.

NEXT: append-only evidence-bound case resolutions with exact source-unit conversion,
no guessed price/title, preserved loser raw IDs, duplicate balance/asset checks; then
reuse actual durable approval/posting owner for independently approved batch-bound
OPENING_BALANCE. Explicit unvalued all-tier review must keep actual costs null, not0.
Audit pending legacy fulfillment effects through owner port, reconcile under exclusive
cutover fence, and implement finalization/mixedtenant HTTP+restart proof. Orphan source
locations currently block reports even for platform: add safe preserved-only handling.
/warehouse/provenance UI still absent; read web/DESIGN.md9 before implementing it.
No full43 acceptance yet. Continue; commit/push coherent phases, no merge/deploy.

## Task43 report + begin-batch checkpoint verified; resolution/approval NEXT

V177.1 is APPLIED and IMMUTABLE (see docs/warehouse-migrations.md for hash).
Authenticated Spring Boot/MockMvc API2 PASS / BUILD SUCCESSFUL51s after fixing
error-advice registration and a fixture permission typo (actual inventory.item.view).
Support15 PASS on same product source: Modularity3/full-packaged Boot2/inventory10.
Expanded17 run had only the fixture failure, then query2 alone reran green. Safe
proof task43/provenance-query-batch-verification.json. All ownedQAservices cleaned,
volumes retained. Branch work/warehouse-completion -> origin/feat/warehouse-workorder.

GET /api/v1/warehouse/provenance, /cases bounded page/size/sourceTable, /cases/{id}.
Require provenance.manage + every referenced current location/customer area before
counts. Root InventoryProvenanceCustomerPort implemented in customer checks current
area under authority fence; no inventory SQL on customer tables. Tests move customer
area while warehouse scope stays valid, revoke old-token access, foreigntenant,
malformed filters, original raw82500 unknown units and deterministic snapshot hash.

POST /provenance/batches uses Idempotency-Key and strict {expectedEpoch,
expectedPreservationHash}. Exclusive cutover fence before authority/topology/customer
locks; verifies actual immutable manifest hash. LEGACY -> existing policy.beginValidation
captures watermark +pending movementIDs and epoch++. Existing VALIDATING withoutbatch
captures its original batchid/epoch/watermark. DB derives manifest and binds immutable
inventory_migration_command actor/hash/expected+resultingepochs/originalresponse tobatch.
Replay rechecks current authority/actor/exacthash/currentepoch; same stored response201.
Onebatch/onecommand,0newdocuments/verifiedstock; ordinary writes still denied.

NEXT: 177.2 next unused,178reserved. Implement evidence-bound case resolution and
independent OPENING_BALANCE through existing durable approval/posting owners; full-tier
unvalued review must not fake0price. No admission/finalization guards opened yet. Audit
legacy fulfillment outbox effects through owner port, in addition to pending movement
IDs; current report covers pending legacy movements only. Finalization must reconcile
active units/conflicts/effects and exclusive tenant fence. Customer installed/unknown
sources remain staged unless proven, never ISP availability. See appended design notes
in task-43.md. Missing source locations currently master lookup fails even for platform;
add preserved-only orphan reporting safely as part of full reconciliation.

No frontend43 edits yet. Read web/DESIGN.md9 before UI. Route /warehouse/provenance
is still absent (customerlegacyCTA alreadypoints there); add typed API/read/begin workbench
then resolution/approval forms using existing Fluent command patterns. Current newdocs
warehouse-provenance.md accurately describes partialphase; update as workflows finish.
No full network-HTTP/restart/admission/browser proof yet;43 OPEN. 1–42complete,43–48/F1–F4
remain. Goal ACTIVE; continue. Originalbranchpreserved; no merge/deploy. Commit+push often.

## Task43 M05 preservation checkpoint verified; interactive API NEXT

V177 applied successfully and now IMMUTABLE. Fifteen tests PASS (full-packaged
WarehouseMigrationITBoot2, inventory10, old schema3), BUILD SUCCESSFUL1m9s.
Safe proof task43/preservation-migration-verification.json. Full clean and V172
colliding upgrade run every packaged migration. 3 original assets,2ONU,11cases,
3 identity conflicts; unknown82500 units preserved,0 new verified stock/documents.
Batch test derives all11manifest rows/hash under VALIDATING, rejects forged empty
manifest, immutable evidence, foreign tenant visibility and premature ENFORCED.
No actual application HTTP reconciliation/admission yet;43 stays OPEN.

M05 snapshots8 legacy source kinds in inventory_provenance_case, exact source IDs/
whitelisted JSON/DB-generated SHA256. No new units/prices/origins/stock. Immutable
inventory_migration_batch binds existing cutover batchid/epoch/watermark and derives
case manifest. App can only SELECT cases, INSERT/SELECT batch under validatingfence.
No prior VALIDATING row is forced to have a batch before operator bootstrap.
Next free177.1;178reserved finalconstraints. Initial V177 attempt rolled back
(nonimmutable convert_to in generated column); fixed before first successful apply.

NEXT: current-authority/scoped dryrun and bounded cases API, exclusive begin-batch
control command with original-response idempotency, then resolution/independent
approval/opening/posting/finalization andUI. Reuse existing durable approval owner.
For customer scope use inventory root port implemented in customer (current tenant/
area) rather than inventory SQL reading customer tables. Gate global tenant report
before counts if operator lacks affected location/area coverage. Old source snapshots
must remain immutable. Task42complete at01c051f6pushed,41JVM+bothmacOSiOScompilesPASS.
Tasks43–48/F1–F4remain; active goal. No deploy/mainmerge. Commit+push checkpoints.

## Task43 in progress: preservation snapshots first

V177/V178 checked unused and reserved. Current max is175.148; noV176 file. Need
M05 immutable provenance case snapshots for legacy asset/balance/ONU/tombstone/
pending effect and immutable batch manifest at exclusive VALIDATING fence. Never
seed stock/receipt or choose a canonical collision winner during Flyway. Start
with full packaged clean+V172colliding upgrade test, current tenant reports and
before counts, then integrate resolution/independent approval/opening admission
and final constraints. No SQL43 written yet at this note.

Existing blockers to replace deliberately: InventoryTenantPolicyService rejects
all approvalOperations even inVALIDATING and finalizeValidation returns enum
INDEPENDENT_APPROVAL_NOT_INSTALLED. DB warehouse_cutover_guard similarly always
rejects VALIDATING->ENFORCED. WarehousePostingService onlyENFORCED. OpeningBalance
service/controller always fail INDEPENDENT_APPROVER_REQUIRED (multipart only
migrationReference/sourceSnapshot/cutoff; no real baseline). Warehouse claim guard
blocks app changes toreserved/conflict identities; candidate rows owner-only.
Use existing durable approval/posting authority, not a parallel mechanism:
DurableApprovalService.request currently ORDINARY_STOCK; decide requiresENFORCED.
PolicySource already derives OPENING_BALANCE but requires actual nonempty source
lines; PolicyEvaluation rejects unknown cost except title-correction nonmonetary
special case. Migration approval must never invent cost or treat unknown as zero
for tier comparisons; require independent full-tier review for nonmonetary source.
Approval owner interface supports validate/prepare/apply, ReceiptPostingApproval
kind enum lacks OPENING yet; operation/source/outbox/DB constraints must match.
Empty validated cutover needs approved control evidence without a fake stock leg.

Claims/candidates source snapshots already created by173/174; M04 actual files
175.48–54 and following, not176. WarehouseSchemaITUpgrade currently stops174.13
with hardcoded184 migration count; task43 needs full packaged migration boot with
legacy collisions/mixed tenant stages plus realHTTP admission/restart. Source
unit ambiguities and identity conflicts must remain staged until evidence-backed
resolution; customer-owned/unknown installed history never becomes ISP available.
No43 product edits yet; no globalconstraint enable/reset/destructive dedup.

42:41JVMtests and both iOS app compile tasks +common metadata succeeded locally
(Kotlin2.3.21 Linux cross-compilation produces native Klib IR, not skipped). Proof
material-ios-local-verification.json. Additional macOS CI run36074462296 at eef43d24
succeeded; safe commit-bound material-ios-macos-verification.json saved. Task42 complete. No native
runtime or release claim. No owned localQAservices running after cleanup.

## Next-phase design constraints after read/begin batch

Existing legacy fulfillment outbox must be audited in addition to pending inventory
movements: FulfillmentPersistence.claimPending/claim/claimOrCreate use CONTROL_PLANE
cutover fence; actual inventory effect rejects ORDINARY_STOCK during VALIDATING.
Future batch/finalization needs actual captured pending legacy effects through a
root inventory port implemented by fulfillment, never inventory SQL on private tables.

Approval for unknown-cost opening must not invent 0 cost or pass value thresholds
with unknown amounts. Use explicit unvalued independent review of all configured
tiers; keep actual source costs nullable. Existing title special-case 0/1 evaluation
is not authority to fabricate opening valuations. Empty validated tenant needs
approved control evidence without a fake physical leg; use same durable approval
owner/posting authority with narrow zero-baseline proof. All ordinary operations
stay closed until finalization.

Case resolutions must bind immutable case hash, batch/cutoff and actual evidence.
Separate source physical identity from old duplicate balance representations: never
count asset + legacy balance twice. Preserve loser raw rows/IDs, claim candidates,
installed links; evidence-backed excluded/duplicate/provenance-only status is separate
from immutable snapshot. Unique legacy installed ONU may remain staged/excluded until
title/source proven. Customer-owned/unknown installed stock must not become available.
For orphan source locations platform migration reports will need a safe preserved-only
view (current reader still calls master lookup for every location). Nonplatform access
must not reveal missing/foreign references. Current-area authorization uses new
InventoryProvenanceCustomerPort implemented in customer, not boot-frozen area.

## Next implementation after31b8f4df (pushed): evidence foundation177.2

177.2 reserved in docs; NO177.2 SQL/code yet at this note. Reuse validateReceiptEvidence
(PDF/PNG/JPEG<=15MiB), ObjectStorage, readback/hash verification. Existing receipt
evidence requires inventory_receipt_intake binding by DB trigger; do not fabricate
receipt/supplier/intake for migration. Use migration-specific append-only metadata
bound to actual batch+case hash and currentVALIDATINGepoch. Upload/list/download
under same provenance coverage authority, original-response replay actor/hashbound.
Use shared canonical filevalidation, not a second approval/posting authority.

Important:177 batch/case/evidence SELECT/INSERT-only tables have UPDATE revoked.
SELECT FOR UPDATE/SHARE would require UPDATE privilege. Serialize batch draft/evidence/
resolution commands with a consistent pg_advisory_xact_lock under shared cutover
fence, reused by DB insert guard and rollback reconciliation, not weakened privileges.
Order future drafts: cutover -> current authority -> batch advisory -> topology/customer
-> case/document. Approval source-owner lock must use same batch ordering. BEGIN has
exclusivecutover beforeeverything, so it can use existing path.

Allow PROVENANCE_RESOLUTION operation in VALIDATING when actual evidence/resolution
service installed; retain MIGRATION_APPROVAL/BASELINE/FINALIZATION hardclosure until
those real owners are installed. Update WarehouseSchemaITPolicy test currently expects
both baseline andresolution tofailINDEPENDENT_APPROVER_REQUIRED. Allordinarywrites
remainclosed. Rootcustomerportcurrentlookup+mastercoverage canbefactored reusable
access helper for query/evidence/resolutions; preserve scope tests.

Follow evidence with evidence-bound append-only case resolutions: BASELINE_STOCK
(known serialized1EA or explicit legacyunitconversion, preserved source raw quantities),
PROVENANCE_ONLY (excluded availability), DUPLICATE (actualsameidentity/link, no counttwice),
and explicit pending-effect cancellation/reconciliation. Keep snapshotimmutable and
record separate decision history. Final seal/approval must exclude requesters/resolvers/
custodians from decision; unknown value uses all-tier unvalued review, no fake0cost.

## Current resolution implementation research after7bc939ec

177.3 reserved: proposals BASELINE_STOCK/PROVENANCE_ONLY/DUPLICATE/CANCEL_PENDING,
append-only revisions, actor/key/body replay, real existing evidence, quantity derived
from immutable source. SERIAL keeps actual assetID and1EA; balances need explicitEA/MM/M
and actualSKU. Customer/installed stock cannot become ISP availability. Duplicate points
to a current baseline proposal and retains original rows; seal must revalidate winner
revision. Pending legacy movement needs explicit cancellation proposal, never ignored.

Critical remaining43 issue found:177 preservation occurs at Flyway time, while legacy
writers may run before BEGIN. Batch capture currently uses those old snapshots. Before
admission implement current exclusive-fence source capture/versioning or rigorously reject
source drift and provide a real refresh path; do not admit stale boot quantities. Include
new/changed/deleted legacy sources and pending fulfillment effects at watermark. Existing
case raw snapshots stay immutable. Resolutions/evidence must remain tied to exact generation.

## Next phase after 8d584188 (pushed): pending legacy fulfillment

Reserve177.5. Add inventory-root work-order provenance authorization port implemented
in workorder, and fulfillment capture port implemented in fulfillment. Use owner
public read views like177.4. Capture legacy WORK_ORDER checkpoint/outbox records with
no fulfillment_approval_snapshot; no raw payload/customer contact/worker credentials.
Record actual IDs, hashes, state/effect progress and source linkage. Extend allowed
case kinds and source counts deliberately. Pending sources require cancellation review,
while APPLIED history never replays as new physical stock. Final cancellation must use
existing coordinator's permanent outcome plus immutable approved batch-bound receipt.

Current fulfillment facts: FulfillmentApprovalService.lock returns early when no
snapshot; preflight WORK_ORDER then require() rejects FULFILLMENT_SNAPSHOT_REQUIRED.
PublicApiFulfillmentEffectExecutor.INVENTORY only approvals.verify (already a verified
material settlement, no direct posting). FulfillmentCoordinator.process wraps actual
work in REQUIRES_NEW transaction and error reconciliation in another transaction.
Checkpoint claim/claimOrCreate/claimPending take CONTROL_PLANE cutover fence. save,
enqueue/markConsumed/effect progress rely on caller transaction; inspect additional
fences before capture. terminal guards currently seal only frozen APPLIED checkpoints;
legacy permanent cancellation needs a narrow additional immutable terminal guard.
manualResolve sets MANUAL_RESOLVED, but process currently only treats APPLIED and
FAILED_PERMANENT as terminal; assess and fix redelivery of MANUAL_RESOLVED.

Live report reads currently use shared cutover fence. With live177.4 views, a new
source can appear between authorization and count under READ_COMMITTED. Before exposing
new dynamic effect sources, make tenant-wide migration summary/cases use exclusive
cutover for consistent authorization+counts (GET still does not mutate business data).
Batch evidence/resolution reads already use frozen source membership. Work-order scope
locks must precede topology/customer locks to match ordinary operation ordering.
