package com.duluin.ftth.customer.application.port.outbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.CustomerStatus
import java.util.UUID

interface CustomerRepository {

    fun save(customer: Customer): Customer

    fun findById(id: UUID): Customer?

    fun findAllByIds(ids: Set<UUID>): List<Customer>

    /**
     * Pelanggan aktif (belum diputus) yang tak punya ONU terpasang di ODP mana pun.
     * `null` = seluruh area; set kosong = tanpa hasil.
     */
    fun findAwaitingInstallation(areaIds: Set<UUID>?): List<Customer>

    fun search(
        query: String,
        areaIds: Set<UUID>?,
        status: CustomerStatus?,
        pageRequest: PageRequest,
    ): Page<Customer>

    fun existsByCode(code: String): Boolean

    fun deleteById(id: UUID)
}
