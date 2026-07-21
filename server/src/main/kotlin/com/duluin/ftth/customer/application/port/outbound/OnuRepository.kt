package com.duluin.ftth.customer.application.port.outbound

import com.duluin.ftth.customer.domain.model.Onu
import java.util.UUID

interface OnuRepository {

    fun save(onu: Onu): Onu

    fun findById(id: UUID): Onu?

    fun findByCustomerId(customerId: UUID): List<Onu>

    fun findByCustomerIds(customerIds: Set<UUID>): List<Onu>

    fun findAllByIds(ids: Set<UUID>): List<Onu>

    /** Pencocokan massal serial yang dilaporkan OLT ke ONU terdaftar. */
    fun findBySerialNumbers(serialNumbers: Set<String>): List<Onu>

    /** ONU yang terpasang pada sebuah ODP, terurut menurut nomor port. */
    fun findByOdpId(odpId: UUID): List<Onu>

    fun existsBySerialNumber(serialNumber: String): Boolean

    /** Jumlah ONU terpasang per ODP dalam satu query — menghindari N+1 di peta. */
    fun countByOdpIds(odpIds: Set<UUID>): Map<UUID, Long>

    fun deleteById(id: UUID)
}
