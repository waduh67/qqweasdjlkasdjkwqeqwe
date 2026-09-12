package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.domain.model.CustomerMaterialFact
import java.util.UUID

/**
 * Fakta material yang terpasang/diretur di sisi pelanggan (`inventory_customer_material_fact`).
 *
 * [payloadHash] ikut disimpan karena idempotency modul ini bukan sekadar "kunci sudah
 * dipakai atau belum": kunci yang sama dengan payload BERBEDA adalah konflik yang wajib
 * ditolak, bukan replay yang boleh dibalas dengan hasil lama.
 */
data class RecordedMaterialFact(val fact: CustomerMaterialFact, val payloadHash: String)

interface MaterialConsumptionRepository {
    fun findByOperation(tenantId: UUID, operationKey: String): RecordedMaterialFact?

    /** Kembalikan fakta yang SUDAH ada kalau `(tenantId, operationKey)` bentrok, `null` kalau tersimpan. */
    fun appendIfAbsent(fact: CustomerMaterialFact, operationKey: String, payloadHash: String): RecordedMaterialFact?

    fun forCustomer(tenantId: UUID, customerId: UUID): List<CustomerMaterialFact>
}
