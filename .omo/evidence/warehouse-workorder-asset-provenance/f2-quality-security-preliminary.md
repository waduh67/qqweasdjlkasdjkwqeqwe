# F2 preliminary source review

Status: **REJECT current source — one high security finding; runtime gates pending.** This is not final F2 approval.

- Reviewed revision: `e17827188175d51a9281cc56c50b950f4fe9e38f`.
- Full feature base: `ebf98fdf270b30ac30b7a01b1f609b39e8414618`.
- Isolated reviewer worktree: `.omo/runtime/warehouse-f2-review`.
- Scope: C1–C11 and F2, including the complete feature change inventory, older compatibility routes, production services, persistence/SQL guards, migrations, fixtures, web/KMP delivery, and the latest work-order report port.
- No product edits, commits, migrations, Java/Gradle/Docker/browser/database QA, or original/validation index changes were performed. The coordinator owns the host QA lock.

## Findings

### F2-01 — HIGH / P1: legacy readers bypass warehouse and area visibility

Primary locations:

- `server/src/main/kotlin/com/duluin/ftth/inventory/application/service/InventoryApiService.kt:27`
- `server/src/main/kotlin/com/duluin/ftth/inventory/application/service/InventoryApiService.kt:30`
- `server/src/main/kotlin/com/duluin/ftth/inventory/application/service/InventoryApiService.kt:41`
- `server/src/main/kotlin/com/duluin/ftth/inventory/application/service/InventoryApiService.kt:44`
- `server/src/main/kotlin/com/duluin/ftth/inventory/adapter/inbound/web/InventoryQueryController.kt:14`
- `server/src/main/kotlin/com/duluin/ftth/inventory/adapter/inbound/web/InventoryController.kt:23`

The feature scopes the new warehouse readers and legacy `/api/inventory/stock` through `WarehouseQueryService.access()`. However, the other legacy routes still call tenant-only repository readers. `locations()`, `items()`, and `custody()` use `findAll(TenantContext.tenantId())`; `findSerializedAsset()` uses an ID lookup. The repository implementation in `InventoryPersistence.kt` does not add warehouse/area predicates. These HTTP routes check only their permission family and never obtain current warehouse/area visibility.

Credible trigger: create warehouse A and B in one tenant, receive a serialized unit into B, and give a user `inventory.item.view` and/or `inventory.custody.view` with warehouse scope limited to A, or with no warehouse scope. The new stock/assets readers hide B, but `GET /api/inventory/items` exposes B's asset ID, serial and MAC; `GET /api/inventory/custody` exposes its location/custodian IDs. The returned asset ID can then be used with `GET /api/inventory/serialized/{id}`. `/api/inventory/warehouses` similarly reveals locations outside the warehouse/area scope. Tenant RLS does not protect a boundary between locations in the same tenant.

This leaves a usable bypass of C7/C8, including after a warehouse scope is revoked while the permission family remains granted. It is not merely an unused internal API: the legacy controllers are still mapped.

Required correction: preserve the legacy DTOs through readers that obtain current authority and filter by the same warehouse/area/site rules as the new API. Treat inaccessible single-asset reads as 404. Keep internal owner API use separate where necessary; do not weaken the new visibility policy to preserve the legacy behavior.

Required real regression cases: both in-scope and out-of-scope warehouses/areas; empty warehouse scope; scope revocation using the existing session; missing/foreign asset; and matching legacy response fields without exposing cost or additional internal data. Cover every retained legacy read route, not only `/stock`.

Validation status: independently traced in current source; no runtime reproduction attempted while QA is locked. Sent promptly to the coordinator.

### F2-02 — MEDIUM / P2: the legacy reservation projection misses canonical reservations

Location: `server/src/main/kotlin/com/duluin/ftth/inventory/application/service/InventoryApiService.kt:37`.

`GET /api/inventory/reservations` filters serialized assets by `InventoryStatus.RESERVED`. Canonical reservations are now durable `inventory_reservation` rows. They encumber availability without changing the asset's physical status; `WarehousePostingService` rejects physical legs with status `RESERVED`, and `docs/warehouse-queries.md:40` documents this distinction.

Credible trigger: receive and reserve one serialized unit through the new request/reservation workflow. The unit remains physically `AVAILABLE`, so the legacy endpoint returns no reservation despite a positive durable reservation. This is a silent compatibility failure under C8. The current `WarehouseQueryITCompatibility` covers stock/items but does not exercise this route after reserve/pick/release.

Required correction: derive the legacy serialized reservation DTO from open durable reservation rows, preserving legacy count/identity semantics and the access restrictions in F2-01. Test reserve, picked-but-not-dispatched, release and dispatch, plus scoped reads. Bulk millimetres must not be coerced into an old device-count field.

Validation status: independently traced in source; runtime regression needed. Sent to the coordinator.

## Source coverage and observations

`f2-source-inventory.json` records SHA-256 and line counts for all 1,646 changed text files outside `.omo`, including 571 changed server Kotlin product files and 195 added migration files. It is an inventory/static-scan record, not a claim of equal manual scrutiny for every line. Manual inspection followed the security and correctness boundaries below, across the full feature rather than only e178.

| Boundary | Inspected source and executable assertions | Source assessment |
|---|---|---|
| Single posting authority and units | `WarehousePostingService`, `WarehousePostingPersistence`, `PostingStock`, `PostingProjection`, `PostingReservations`, `PostingDocuments`/line bindings; `StockQuantity`, `ReceiptConversion`; posting/schema concurrency and constraint fixtures | Paired quantities are conserved per SKU/lot/unit and physical identity. Checked arithmetic is used for mutations. Splits retain identity lineage. Zero positions use unique dimensions and locks; consumption moves from acknowledged technician custody into a sink. The only production movement/leg insertions located in inventory/fulfillment are the canonical posting helpers. No production in-memory stock/fact/approval store or empty-allocation success fallback was found. |
| Idempotency and race recovery | `WarehouseCommandService`, `WarehousePreparedCommand`, `WarehouseOperationStore`, `WarehouseLegacyCommandExecutor`, document-specific replay branches, `WarehouseOutboxDispatcher`, delivery stores | Canonical payloads and stored responses are actor/resource bound; current permissions/scopes are checked before replay. Document business-action uniqueness is separate from keys. Durable delivery is outside local stock locks. Lock/serialization conflicts map through stable conflict paths. These are source conclusions pending current full execution. |
| Current authorization and WO mutation locks | `CurrentAuthorityPersistence`, IAM mutation fence consumers, `WarehouseScopePersistence`, `WorkOrderService`, `WorkOrderPersistenceAdapter`, `WorkOrderMaterialContextAdapter`, `WorkOrderInventoryValidationAdapter`, `InventoryCommandWorkOrderAdapter` | Commands acquire cutover/current-authority fences and current roster/context locks. WO persistence uses pessimistic writes in mutation transactions. Deployment consume validates the persisted actor, active assignment, customer, work type and all bound revisions before posting. The legacy read exception is F2-01. |
| Approval independence and count staleness | `DurableApprovalService`, `WarehouseApprovalAuthority`, `WarehousePolicyEvaluationService`, approval source locks/owners, `WarehouseCountApprovalOwner`, approval and schema fixtures | Requesters/custodians/counters and delegated identities are excluded; platform privilege does not waive independent identities. Decisions recheck live eligibility and source revision/hash. Count balance observations are rechecked under locks at final execution. Unknown/mismatched monetary basis blocks value rules; unvalued migration/title exceptions remain purpose-bound. |
| Material physical facts, QA and residual custody | `MaterialWorkflowService`, `InventoryMaterialUsageService`, usage preparation/deltas, receipt/residual/handover services, `FulfillmentApprovalService`, `PublicApiFulfillmentEffectExecutor`, lifecycle/numeric fixtures | Usage is a physical posting before QA; approval verifies a frozen settlement snapshot. Serialized deployment has a separate authorization path. Reassignment is not custody transfer, and old custody is handled through residual/physical handover paths. Service effects are derived from explicit links. |
| Same physical asset reuse, title and RMA | `InventoryDeploymentService`, `RmaDeploymentService`, `CustomerAssetService`, `CustomerAssetInstallationStore`, replacement/removal/title/handover services, return inspection and loss owners | Each deployment appends a new customer episode while retaining the physical asset ID. SALE title changes through acceptance; original-customer RMA has a separate purpose-bound path. Returned ISP stock requires inspection/reset and customer-owned stock is not released as ISP availability. Historical customer/ONU retention guards reject destructive removal. |
| Privacy and temporal attribution | `WarehouseReportService`/SQL, `CustomerAssetWorkbenchQuery`, `InventoryAssetPresentationService`, `PortalAssetController`, `CustomerObservationService`, `MetricIngestionService`, CPE sync/binding/ownership services, compatibility/privacy fixtures | Portal maps to a separate minimal DTO with customer derived from the portal session. Observation attribution uses episode intervals, historical topology and trusted time; delayed observations do not update the next episode's live status. CPE fields require freshness after a new episode. F2-01 remains a separate staff legacy-reader leak. |
| New e178 report owner port | All changed port/service/report SQL and `WarehouseReportWorkOrderScopeIT` | Tenant and area membership are resolved in the workorder owner. Matching IDs are ordered and locked `FOR SHARE` in the same transaction; reports join only those IDs. Empty/restricted/foreign scopes do not become unrestricted. The two LOAN/SALE cases assert assignment/cost/print disappearance and exact restoration after an area move; the other cases assert tenant/scope separation and a real concurrent updater blocked by the report lock. No new lock-order correctness regression was established. |
| Cutover/migration and retention | Full migration inventory, M01/M02 DDL and identity claims; finalization/admission/legacy-closure guards; `InventoryTenantPolicyService`, migration/finalization services; `TenantEraser`, V178.9; packaged-upgrade/schema fixtures | All 195 migration changes are new files; no pre-existing migration was modified relative to the feature base. No RLS-disabling or BYPASSRLS role alteration was found in added migrations. Ambiguous identities remain reserved/staged, and finalization is batch/evidence/approval bound. Tenant history deletion rejects with Suspend guidance; only the two empty initialization rows use the narrowly guarded tenant-delete cascade. Runtime schema/upgrade assertions are still required. |
| Web/KMP delivery | Warehouse transport/runtime decoders, command dialog/query hook, exact quantity codec, material/customer/RMA/provenance actions; `MaterialRepository`, encrypted outbox, Android/iOS records, MVI/session handling and their test sources | Quantities remain strings/BigInt or checked Long. Ambiguous retries retain original bytes/key; mobile queued commands are encrypted and user/tenant/device/session bound with source revalidation before first delivery. Offline enqueue does not claim a server stock change. Native compile is not a native release claim. |
| GPON scope and external producers | Shared contract, collector retry classification, GPON adapter/profiles, `docs/gpon-profile-evidence.md`, offline test source | Profiles state documentation limits; raw indexes stay `UNVERIFIED_INDEX`, FiberHome's unestablished default is explicitly unavailable, and Huawei source corrections match the documented object distinctions. Physical GPON certification remains deferred by the user. This review does not substitute for checking the referenced documentation/offline execution evidence. |
| Fixtures and evidence quality | `WarehouseSchemaDatabase`, schema/posting fixtures, actual privacy/concurrency/upgrade test assertions, `ModularityTests`, CI workflow and `ci-results.py` | Disposable database fixtures are explicitly marker/URL constrained; application assertions use `warehouse_app`, separate from migration ownership. Modularity performs `modules.verify()`. Result publication checks actual testcase cardinality and refuses failed/skipped/zero suites; source proof and summaries cannot replace the private executed reports. |

The whole-diff import scan found no changed server product import into another domain module's `adapter`, `application`, or `domain` package after excluding intentionally shared `common` imports. The scan does not replace `ModularityTests` or SQL ownership review.

Non-blocking observation: the new report port materializes and locks the full visible WO ID set even for a filtered cost report or a print request. This is broader lock coverage than the individual document needs; no material correctness failure was established. It must not be represented as a demonstrated deadlock or release blocker.

## Outstanding gates before final F2

1. Correct F2-01 and F2-02; independently re-review the affected source and inspect the new executed regression cases.
2. Obtain the complete current full-server reports, including nonzero `ModularityTests`, with exact source identity and successful process completion. The coordinator reported compile + 30 focused classes and nonserver jobs at CI `36137258991`, but those summaries were not promoted to full-server evidence here. Earlier failures/incomplete runs are not passes.
3. Inspect current web lint/unit/build and KMP shared result evidence and case cardinality. A successful native compilation may only be reported as compilation.
4. Confirm packaged upgrade, non-owner/NOBYPASSRLS, browser and GPON documentation/offline evidence relevant to this source through the coordinated final verification. Any correction after e178 requires affected validation and source identity reconciliation.
5. Keep raw XML/system-out, auth/environment material and browser traces private. Only allowlisted count/hash/source proofs belong in public evidence.

Final `f2-quality-security.md` must be produced only after these gates. The current source findings independently prevent approval even if the in-progress e178 full regression passes.
