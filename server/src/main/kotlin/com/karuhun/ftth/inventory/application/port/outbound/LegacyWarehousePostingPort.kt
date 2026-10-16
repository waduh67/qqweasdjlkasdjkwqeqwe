package com.karuhun.ftth.inventory.application.port.outbound

import com.karuhun.ftth.inventory.InventoryFulfillmentCommand
import com.karuhun.ftth.inventory.domain.model.CustomerMaterialFact
import com.karuhun.ftth.inventory.domain.model.MovementCommand
import java.util.UUID

interface LegacyWarehousePostingPort {
    fun fulfillment(command: MovementCommand): InventoryFulfillmentCommand
    fun reference(command: InventoryFulfillmentCommand): LegacyPostingReference
    fun resolve(command: InventoryFulfillmentCommand, returned: Boolean, cutoverEpoch: Long, reference: LegacyPostingReference): WarehousePost
    fun facts(customerId: UUID): List<CustomerMaterialFact>
    fun hasFact(operationKey: String, payloadHash: String): Boolean
}

data class LegacyPostingReference(val line: UUID, val document: UUID, val revision: Long, val authorityEpoch: Long, val returnLocation: UUID)
