package com.duluin.ftth.inventory.domain.model

import java.time.Instant
import java.util.UUID

data class MaterialConsumptionCommand(
    val tenantId: UUID,
    val workOrderId: UUID,
    val customerId: UUID,
    val actorId: UUID,
    val operationKey: String,
    val payloadHash: String,
    val skuId: UUID,
    val itemId: UUID,
    val locationId: UUID,
    val quantity: Int,
    val serialized: Boolean,
    val reason: String,
    val itemCategory: String,
    val installed: Boolean = true,
    /**
     * Unit fisik yang dipakai, WAJIB untuk material berserial (V174). Tanpa ini, "1 ONT
     * terpasang di pelanggan X" tak pernah bisa dicocokkan dengan aset SN berapa.
     */
    val assetId: UUID? = null,
    val serialNumber: String? = null,
) {
    init {
        require(operationKey.isNotBlank() && payloadHash.isNotBlank() && reason.isNotBlank())
        require(quantity > 0)
        require(!serialized || quantity == 1)
        require(!serialized || assetId != null) { "material berserial WAJIB menyebut aset (assetId)" }
    }
}

data class CustomerMaterialFact(
    val tenantId: UUID,
    val customerId: UUID,
    val workOrderId: UUID,
    val itemCategory: String,
    val quantity: Int,
    val installed: Boolean,
    val returned: Boolean,
    val recordedAt: Instant,
)
