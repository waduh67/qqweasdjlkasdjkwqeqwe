package com.duluin.ftth.inventory.legacy

import com.duluin.ftth.inventory.application.service.InventoryMovementLedgerService
import com.duluin.ftth.inventory.domain.model.*
import java.time.Instant
import java.util.UUID

class LegacyMaterialCharacterization(private val ledger: InventoryMovementLedgerService) {
    private val facts=linkedMapOf<Pair<UUID,String>,Pair<String,CustomerMaterialFact>>()
    fun consume(command: MaterialConsumptionCommand): CustomerMaterialFact {
        val key=command.tenantId to command.operationKey
        facts[key]?.let { require(it.first==command.payloadHash); return it.second }
        ledger.apply(MovementCommand(command.tenantId,command.actorId,"workorder.material.${command.workOrderId}",command.operationKey,
            command.payloadHash,command.reason,MovementKind.CONSUME,listOf(MovementLeg(LegDirection.OUT,command.itemId,command.skuId,
                command.locationId,command.quantity,command.serialized,command.actorId,OwnerKind.TECHNICIAN,InventoryStatus.ISSUED))))
        return CustomerMaterialFact(command.tenantId,command.customerId,command.workOrderId,command.itemCategory,command.quantity,true,false,Instant.now())
            .also { facts[key]=command.payloadHash to it }
    }
    fun forCustomer(tenant: UUID,customer: UUID) = facts.values.map { it.second }.filter { it.tenantId==tenant && it.customerId==customer }
}
