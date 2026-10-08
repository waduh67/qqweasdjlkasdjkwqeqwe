package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryTenantCutoverApi {
    fun read(): TenantCutoverSnapshot
    fun lockForCommand(expectedEpoch: Long, operation: WarehouseOperationClass): TenantCutoverFence
    fun lockForTransition(expectedEpoch: Long): TenantCutoverChangeFence
}

interface TenantCutoverFence {
    val snapshot: TenantCutoverSnapshot
    val operation: WarehouseOperationClass
    fun assertHeld()
}

interface TenantCutoverChangeFence {
    val snapshot: TenantCutoverSnapshot
    fun assertHeld()
}

data class TenantCutoverSnapshot(
    val tenantId: UUID,
    val state: WarehouseCutoverState,
    val epoch: Long,
    val migrationBatchId: UUID?,
    val snapshotWatermark: String?,
    val workflow: WarehouseWorkflow = WarehouseWorkflow.LEGACY,
    val drainingFromEpoch: Long? = null,
)

enum class WarehouseCutoverState { LEGACY, VALIDATING, ENFORCED }
enum class WarehouseWorkflow { LEGACY, DRAINING, REFERENCE }
enum class WarehouseAdmission { LEGACY_UNRESOLVED, VERIFIED }
enum class WarehouseIdentityClaimState { LEGACY_RESERVED, CONFLICT, ADMITTED, RETIRED }
enum class WarehouseOperationClass {
    CONTROL_PLANE, MIGRATION_REPORT, PROVENANCE_RESOLUTION, MIGRATION_APPROVAL,
    MIGRATION_BASELINE, CUTOVER_FINALIZATION, ORDINARY_STOCK, ASSET_ASSIGNMENT, LEGACY_EFFECT,
    LEGACY_STOCK_CREATE, LEGACY_WORK_ORDER_CREATE, LEGACY_WORK_ORDER_CHANGE, LEGACY_FULFILLMENT,
    LEGACY_CONFIGURATION, LEGACY_MAINTENANCE,
    REFERENCE_STOCK, REFERENCE_WORK_ORDER,
}
