package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.CustomerAssetWorkbenchQuery
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
class InventoryAssetPresentationService(private val query: CustomerAssetWorkbenchQuery) : InventoryAssetPresentationApi {
    override fun forCustomer(customerId: UUID, page: WarehousePageRequest): WarehousePage<CustomerAssetPresentation> {
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val rows = query.history(customerId, page, null)
        return WarehousePage(rows.items.map { row -> CustomerAssetPresentation(row.sku.name, row.serial, row.ownershipMode,
            row.legalOwner, row.provenance, row.startedAt, row.endedAt) }, rows.page, rows.size, rows.totalElements)
    }
}
