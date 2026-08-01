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

    /**
     * Nomor urut kode-otomatis tertinggi yang sudah dipakai tenant ini (dari kode berbentuk
     * `{prefix}{angka}`), atau 0 bila belum ada. Dipakai untuk membuat kode berikutnya secara
     * berurut. Tercakup RLS per-tenant, jadi hitungannya per-tenant tanpa filter eksplisit.
     */
    fun maxCodeSequence(prefix: String): Int

    fun deleteById(id: UUID)
}
