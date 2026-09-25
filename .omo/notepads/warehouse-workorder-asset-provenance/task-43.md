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
