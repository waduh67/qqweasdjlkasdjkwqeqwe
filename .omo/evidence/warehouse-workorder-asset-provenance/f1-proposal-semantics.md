# F1 targeted C8 applicability review: specialized approval proposals

Reviewed source: `e17827188175d51a9281cc56c50b950f4fe9e38f`, detached F1 review worktree. This is a targeted read-only source interpretation, not a final F1 verdict or an executed QA result. No product changes or QA were performed for this review. Root accepted the operational-draft versus committed-proposal distinction as contract applicability, not an owner scope reduction.

## Conclusion

C8 does not mandate same-resource PUT merely because a backing `inventory_document` row has state `DRAFT`. Specialized request/report commands generally commit an immutable approval proposal containing specific source facts, actor, evidence and expected revisions. A corrected successor proposal can satisfy the contract while preserving C3 original-response/history and C7 independent approval requirements. C4 does not define the ordinary editable-draft lifecycle for every LOSS/TITLE/etc proposal family.

Operational receipt, material-plan, transfer and count drafts remain editable working documents and require their revision-guarded edit commands. The established saved transfer/count PUT/UI gaps remain valid. This interpretation does not exempt retained unapproved proposals from C7 idle expiry. Expiry must preserve immutable history and must not post stock, erase custody or release physical obligations.

`S/` below means `server/src/main/kotlin/com/duluin/ftth/`; `T/` means `server/src/test/kotlin/com/duluin/ftth/`; `M/` means `server/src/main/resources/db/migration/`.

## Specialized family matrix

| Family | Actual source/UI semantics and successor evidence | C8 applicability |
|---|---|---|
| LOSS/SCRAP | `web/src/pages/warehouse/WarehouseDispositionForm.tsx:46` identifies an independently approved request; lines 49–52 confirm saving that request without moving goods. `S/inventory/application/service/WarehouseDispositionService.kt:27` captures the source return/physical/cost evidence. `M/V175_138__warehouse_disposition_request.sql:21` makes the request append-only. `T/inventory/WarehouseDispositionGuardsIT.kt:99` preserves a rejected source and creates a corrected request with its own approval. | Committed proposal. Corrected successor POST is appropriate; no generic same-resource PUT requirement established. |
| DISPOSITION_REVERSAL | The form explicitly says correction creates a new record referring to the old posting (`WarehouseDispositionForm.tsx:41`). `WarehouseCompensationService.kt:26` binds original posting and return revisions. `M/V175_140__warehouse_disposition_compensation_request.sql:21` seals the request. `T/inventory/WarehouseCompensationGuardsIT.kt:101` covers a corrected successor while retaining the rejected source. | Committed proposal; same-resource PUT unnecessary. |
| ASSET_LOSS | `WarehouseAssetLossService.kt:26` captures handover/signature and current assignment/title/WO revisions. `M/V175_143__warehouse_asset_loss_request.sql:21` seals the request. `T/inventory/WarehouseAssetLossGuardsIT.kt:94` encodes a fresh corrected assessment after rejection. | API/schema describe a committed proposal; successor POST is appropriate. Separate missing creation UI observation below. |
| TITLE_CORRECTION | `InventoryAssetTitleService.kt:22` captures signed evidence and current title/assignment revisions. `M/V175_70__warehouse_title_correction_storage.sql` seals the request and identifies source-title revision/payload. | Fresh immutable proposal is appropriate; no same-resource PUT necessity. Existing rework behavior is inconsistent, described below. |
| RETURN_TITLE | `web/src/pages/warehouse/WarehouseReturnReacquisition.tsx:63`–71 confirms a title-transfer request. `ReturnReacquisitionService.kt:40` captures customer consent/signature and return/asset revisions. `M/V175_131__warehouse_return_reacquisition_request.sql:23` seals it. `T/inventory/WarehouseReturnTitleGuardsIT.kt:64` encodes a new request after rejection. | Committed proposal; successor POST is appropriate. |
| Transfer ADJUSTMENT | `WarehouseTransferActions.tsx:56` confirms “Catat selisih” and explains stock remains in transit until approval. `WarehouseTransferDiscrepancyService.kt:40` binds actual transfer revision. `WarehouseTransferDiscrepancyStore.recoveryBlock` rejects replacement while approval/effect remains active. `T/inventory/WarehouseTransferDiscrepancyRecoveryIT.kt:46` encodes corrected report replacement with retained approval history and stock. | Existing named successor report command fits C8; no need to mutate an already committed report. |
| OPENING_BALANCE | `WarehouseProvenanceOpening.tsx:38` describes saved proposals; line 56 states newer case decisions require a new review/proposal. `WarehouseOpeningBalanceService.kt:52` seals batch/review evidence. `M/V177_6__warehouse_migration_opening_review.sql:147`–164 prevents mutation of the sealed request/document/lines. `T/inventory/WarehouseMigrationOpeningApprovalIT.kt:438` encodes a new immutable request after rejection. | Committed baseline proposal; successor POST preserves evidence and meets the correction purpose. |
| Supplier replacement | `SupplierReplacementService.kt:57` creates the receipt and immutable replacement binding. `M/V175_134__warehouse_supplier_replacement_request.sql` binds the original receipt snapshot/revision. `T/inventory/WarehouseSupplierReplacementGuardsIT.kt:111` encodes a fresh receipt request after rejection. | Backend successor-proposal semantics can comply. Current editable-draft UI is inconsistent and must be corrected; see below. |

The cited tests were inspected as source assertions only. They were not executed in this targeted review; complete current regression evidence remains required.

## Concrete findings

### TITLE_CORRECTION advertises a rework path that cannot produce a valid reapproval

`S/inventory/application/service/WarehouseApprovalQueryService.kt:132`–135 excludes several immutable proposal kinds from `canRework`, but omits `TITLE_CORRECTION`. `web/src/pages/warehouse/WarehouseApprovalsPage.tsx:109` therefore may display “Buka perbaikan dokumen”. `DurableApprovalService.rework` also omits TITLE_CORRECTION from the kinds redirected to a new request and advances the source document revision.

However, `S/inventory/application/service/AssetTitleCorrectionOwner.kt:27` requires `source.revision == 0`, and the signed request row is append-only. Reopening that same source cannot yield a valid reapproval. Correct behavior is a fresh proposal bound to current title/evidence, with the unusable rework action rejected or redirected. This is an API/UI workflow defect, not proof that same-resource PUT must be added.

### Supplier replacement exposes a false generic draft-edit action

`web/src/pages/warehouse/WarehouseSupplierReplacement.tsx:44` says “Masih draft”; its confirmation uses “Buat draft pengganti” and links to the generic receipt page. `WarehouseReceiptsPage.tsx:92` offers “Ubah draft” for every DRAFT receipt without a supplier-replacement predicate.

The replacement binding's final-state guard (`warehouse_assert_replacement_request` in V175.134 and its followups) seals the receipt to the original request snapshot/revision. The intended correction test requires a fresh replacement receipt request. The UI must expose those actual immutable-proposal/successor semantics, or implement safe specialized revision behavior; offering a generic edit that cannot commit is not compliant. The successor approach itself does not require owner scope reduction.

### No creation caller found for ASSET_LOSS or TITLE_CORRECTION

A repository-wide `web/src` search found no API caller for `/api/v1/warehouse/asset-losses` or `/api/v1/warehouse/asset-title-corrections`, nor a creation action corresponding to `WarehouseAssetLossService.request` or `InventoryAssetTitleService.requestCorrection`. Existing frontend matches are approval/read labels for `ASSET_LOSS` and `TITLE_CORRECTION`.

Thus, the backend API/schema establish proposal semantics, but no creation UI was available to verify or count as implemented. These are separate UI-scope findings to resolve in the final source audit. They do not justify generic PUT on immutable proposals.

## Effect on preliminary F1 record

The broad specialized-family PUT concern in `f1-compliance-preliminary.md` is resolved by the applicability distinction above. It should not be carried forward as a blanket requirement to mutate sealed proposals. Carry forward the actual TITLE_CORRECTION rework defect, supplier replacement edit-affordance defect, and absent creation callers; retain operational transfer/count edit requirements and C7 expiry for every retained unapproved proposal.

No final approval is issued. Corrected source, affected regression/UI evidence and the remaining final gates are still required.
