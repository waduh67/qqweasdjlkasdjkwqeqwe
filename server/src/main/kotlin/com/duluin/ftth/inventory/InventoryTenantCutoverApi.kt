package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryTenantCutoverApi {
    fun read(): TenantCutoverSnapshot
    fun lockForCommand(expectedEpoch: Long, operation: WarehouseOperationClass): TenantCutoverFence
    fun lockForTransition(expectedEpoch: Long): TenantCutoverChangeFence
}

interface TenantCutoverFence {
    val snapshot: TenantCutoverSnapshot
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
)

enum class WarehouseCutoverState { LEGACY, VALIDATING, ENFORCED }
enum class WarehouseAdmission { LEGACY_UNRESOLVED, VERIFIED }
enum class WarehouseIdentityClaimState { LEGACY_RESERVED, CONFLICT, ADMITTED, RETIRED }
enum class WarehouseOperationClass {
    CONTROL_PLANE, MIGRATION_REPORT, PROVENANCE_RESOLUTION, MIGRATION_APPROVAL,
    MIGRATION_BASELINE, CUTOVER_FINALIZATION, ORDINARY_STOCK, ASSET_ASSIGNMENT, LEGACY_EFFECT,
}
