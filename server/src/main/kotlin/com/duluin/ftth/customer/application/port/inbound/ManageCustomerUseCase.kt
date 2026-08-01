package com.duluin.ftth.customer.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.domain.model.CustomerStatus
import java.util.UUID

interface ManageCustomerUseCase {

    fun search(query: String, status: CustomerStatus?, pageRequest: PageRequest): Page<CustomerView>

    fun get(id: UUID): CustomerView

    fun create(command: SaveCustomerCommand): CustomerView

    fun update(id: UUID, command: SaveCustomerCommand): CustomerView

    fun changeStatus(id: UUID, status: CustomerStatus): CustomerView

    fun delete(id: UUID)
}

data class SaveCustomerCommand(
    /** Kosong/null = server membuat kode berurut otomatis (`CUST-000001`). */
    val code: String?,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String,
    val location: Coordinate,
    val areaId: UUID?,
)
