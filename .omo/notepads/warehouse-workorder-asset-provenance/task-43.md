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
