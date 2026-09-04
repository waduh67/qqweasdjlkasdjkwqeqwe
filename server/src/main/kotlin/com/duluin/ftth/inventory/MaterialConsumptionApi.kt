package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.MaterialConsumptionService
import com.duluin.ftth.inventory.domain.model.MaterialConsumptionCommand
import java.util.UUID
import java.time.Instant

interface MaterialConsumptionApi {
    fun consume(command: MaterialConsumptionCommand): CustomerMaterialFactRef
    fun returnUnused(command: MaterialConsumptionCommand): CustomerMaterialFactRef
    fun forCustomer(tenantId: UUID, customerId: UUID): List<CustomerMaterialFactRef>
}

class MaterialConsumptionApiAdapter(
    private val service: MaterialConsumptionService,
) : MaterialConsumptionApi {
    override fun consume(command: MaterialConsumptionCommand) = service.consume(command).toRef()
    override fun returnUnused(command: MaterialConsumptionCommand) = service.returnUnused(command).toRef()
    override fun forCustomer(tenantId: UUID, customerId: UUID) = service.forCustomer(tenantId, customerId).map { it.toRef() }
}

data class CustomerMaterialFactRef(val tenantId: UUID, val customerId: UUID, val workOrderId: UUID, val itemCategory: String, val quantity: Int, val installed: Boolean, val returned: Boolean, val recordedAt: Instant)
private fun com.duluin.ftth.inventory.domain.model.CustomerMaterialFact.toRef() = CustomerMaterialFactRef(tenantId, customerId, workOrderId, itemCategory, quantity, installed, returned, recordedAt)
