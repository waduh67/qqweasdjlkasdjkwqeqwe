Latest checkpoint: opening directory and browser clients are VERIFIED. See leading
continuation.md and task43/opening-directory-verification.json. Page/forms/routes
still missing; all prototype client files mentioned below now have tests/TS/lint.
GET paged opening directory has been added with actual current review-location scope.

# Task43 finalization and UI continuation

Current uncommitted M06/API work: V178, MigrationFinalizationStore,
WarehouseMigrationFinalizationService, GET/POST batches/{batch}/finalization.
V178 APPLIED/IMMUTABLE: f6b8780d898577c16f2b63298e9d3fd43244d6e32a02a4c921ae04c7a1c9997d.
Next free178.1; only forward fixes. SQL owner works including realzero and original asset.
r2 exposed concurrent read-before-lock stale-epoch race. Added concrete policy owner
lockCurrentForTransition (under exclusive lock before checking replay/expectedepoch)
for finalizer and begin/report owner. No public interface or ordinary command bypass.
r3 zero/finalization/replay/current-identity/fresh-context tests PASS; 1 existing lease
claim returnednull in first opening test. May involve original and manually started
contexts; no weakening. Added safe lease-state diagnostic and replaced nested context
with actual stop/restart phases: Order1 preparation + DirtiesContext AFTER_METHOD;
Order2 asserts ContextClosedEvent observed then fresh Spring boot realHTTP replay,
A ENFORCED/B VALIDATING, oldasset/ONU/customer IDs, staged claim blocks competing
receipt, current actor revoke. Restores original test fixture area explicitly.
6 OpeningApprovalIT tests now. First test additionally finalizes real3 cancellation
receipts (inventory movement, fulfillment checkpoint/outbox). Changed-peer test
also corrupts real evidence bytes, rejects finalization with0receipt, restores bytes.
r4 runtime migration-finalization-regression.sh/log COMPLETE:45tests PASS5m4s. Targets opening6,
boot2,policy11,modularity3,capture3,query2,PostingIT12,ReceiptGuards.
All source edits completed before r4 launch. No SQL changed since first apply.

Finalization POST input: expectedEpoch, openingDocumentId, expectedReviewHash, reason.
Current IAM/source scope precedes replay. Exclusive tenant then history batch fence.
Canonical envelope {batchId,input}; same key/body/actor returns original receipt across
VALIDATING1 -> ENFORCED2. SQL owner checks actual approved admission, document/posting,
event+inbox, permanent cancellation receipts, exact VERIFIED baseline, current claims.
Receipt stores original counts and exact unit sums including explicit zero.
Read review includes finalization original response after cutover.

Added tests in OpeningApprovalIT zero + changed-peer case, concurrent same-key,
rollback after SQL owner returns, missing approval/direct witness/raw cutover denial,
mixed A ENFORCED/B VALIDATING and fresh independent Spring application HTTP replay/read,
original asset/ONU/customer IDs, reserved-serial receipt collision, current actor revoke.
This fresh context currently starts while initial test context is alive; do not call it
a process crash/restart proof. Strengthen actual process restart in task44/browser45.
Finalizer with real canceled fulfillment checkpoint/outbox still should be added to
first opening test at end. Check evidence corruption before finalization. Full schema/
ordinary posting/owner regression after first focused gate.

UI still missing. Draft provenanceModels.ts and provenance.ts plus exports in migrationReview.ts
are now unverified working files; API/UI tests and TypeScript still required. Read web/DESIGN.md section9 before implementation. Existing:
- web/src/api/warehouse/migrationReview.ts strict sealed review decoder + file checksum.
  Export reusable resolution/rawText as needed, preserve null/raw blank identities.
- WarehouseProvenanceStore summary/cases JSON is actual management response, differs from
  sealed review case: id vs caseId, claims array, current scoped location/customer/WO.
- Cases stable bounded pagination; sourceTable filter; GET cases/{id}.
- Batch review currently returns full manifest + issues. Avoid UI loading every source
  for management table; selected case resolution history/evidence paged separately.
- GET /batches/{batch}/opening/{id}, POST /opening. Need bounded list of sealed opening
  requests to recover after reload; link approval sourceDocumentId, don't lose an
  ambiguous POST key or encourage competing immutable baselines.
- Evidence POST multipart request JSON(expectedEpoch,expectedCaseHash,label)+file.
  Capture exact File bytes/key/input while retrying; private download verifies sha256.
- Resolution input expectedEpoch/caseHash/resolutionRevision, kind, reason,
  evidenceIds<=10, stock{skuId,sourceUnit EA/MM/M,legalOwner ISP} or duplicateCaseId.
  Show frozen raw qty + explicit chosen unit conversion; no client-supplied stock qty.
- WAREHOUSE_PAGES navigation and WarehouseRoutes map must add provenance with manage
  permission. CustomerAssetPanel already links /warehouse/provenance.
- Reuse WarehouseCommandDialog, WarehousePicker, WarehouseQuantity/Time, useWarehouseQuery.
  Dialog retains exact command after ambiguous transport error; no dismissal until retry.
- UI cases must show independent evidence/reconciliation, pending canceled effects,
  duplicate original case selection, unresolved historical exclusion, no fake origin/cost.
  Empty tenant explicit reviewed-zero opening still needs actual review warehouse and
  independent approval. New empty tenant ENFORCED with no batch renders already active.
- Need settings approval tier link and sourceDocumentId workflow for submitting opening.
