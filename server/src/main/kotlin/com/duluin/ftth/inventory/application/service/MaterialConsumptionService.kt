package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.InventoryFulfillmentCommand
import com.duluin.ftth.inventory.application.port.outbound.LegacyWarehousePostingPort
import com.duluin.ftth.inventory.domain.model.CustomerMaterialFact
import com.duluin.ftth.inventory.domain.model.MaterialConsumptionCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MaterialConsumptionService(private val fulfillment: DurableInventoryFulfillmentService, private val legacy: LegacyWarehousePostingPort) {
    @Transactional fun consume(command: MaterialConsumptionCommand): CustomerMaterialFact = record(command,false)
    @Transactional fun returnUnused(command: MaterialConsumptionCommand): CustomerMaterialFact = record(command,true)
    @Transactional(readOnly=true) fun forCustomer(tenantId: UUID,customerId: UUID): List<CustomerMaterialFact> {
        require(tenantId==TenantContext.tenantId())
        return legacy.facts(customerId)
    }
    @Transactional(readOnly=true) fun hasRecorded(command: MaterialConsumptionCommand): Boolean {
        require(command.tenantId==TenantContext.tenantId())
        return legacy.hasFact(command.operationKey,command.payloadHash)
    }
    private fun record(command: MaterialConsumptionCommand,returned: Boolean): CustomerMaterialFact {
        require(command.installed!=returned)
        val result=fulfillment.apply(InventoryFulfillmentCommand(command.tenantId,command.itemId,command.itemId,command.skuId,command.locationId,
            command.customerId,command.workOrderId,command.quantity,command.serialized,command.installed,command.actorId,"workorder.material.${command.workOrderId}",
            command.operationKey,command.payloadHash,command.reason,command.itemCategory),returned)
        return CustomerMaterialFact(command.tenantId,command.customerId,command.workOrderId,command.itemCategory,command.quantity,command.installed,returned,result.recordedAt)
    }
}
