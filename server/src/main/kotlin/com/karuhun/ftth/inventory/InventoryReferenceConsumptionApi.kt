package com.karuhun.ftth.inventory

import com.karuhun.ftth.common.infrastructure.web.WarehouseCanonicalPayload
import com.karuhun.ftth.iam.CurrentAuthority
import java.time.Instant
import java.util.UUID

interface InventoryReferenceConsumptionApi {
    /** Seals the caller's completion proof before posting stock in the caller's transaction. */
    fun <T> consume(command: ReferenceConsumptionCommand,
        seal: (ReferenceConsumptionResult) -> ReferenceConsumptionProof<T>): ReferenceConsumptionProof<T>
}

data class ReferenceConsumptionCommand(val workOrderId: UUID, val customerId: UUID?, val technicianName: String,
    val revision: Long, val completedAt: Instant, val notes: String, val selections: List<ReferenceConsumptionSelection>,
    val request: WarehouseCanonicalPayload, val authority: CurrentAuthority, val cutoverEpoch: Long)
data class ReferenceConsumptionSelection(val stockIdentityId: UUID, val quantityBase: String)
data class ReferenceConsumedMaterial(val lineId: UUID, val stockIdentityId: UUID, val consumedIdentityId: UUID,
    val skuId: UUID, val skuName: String, val quantityBase: String, val baseUnit: String, val tracking: String,
    val serial: String?, val mac: String?)
data class ReferenceConsumptionResult(val documentId: UUID?, val materials: List<ReferenceConsumedMaterial>)
data class ReferenceConsumptionProof<T>(val value: T, val canonical: WarehouseCanonicalPayload)
