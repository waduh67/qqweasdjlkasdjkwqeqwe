package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.application.port.outbound.MaterialConsumptionRepository
import com.duluin.ftth.inventory.application.port.outbound.RecordedMaterialFact
import com.duluin.ftth.inventory.domain.model.CustomerMaterialFact
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Fakta material yang menempel di pelanggan. Seluruh kolom `updatable = false`: catatan ini
 * adalah bukti "barang apa yang dipasang di rumah siapa" yang dipakai saat pelanggan berhenti
 * berlangganan, jadi ia BUKAN data yang boleh disunting belakangan — koreksi dibuat sebagai
 * fakta retur baru, bukan dengan menimpa yang lama.
 */
@Entity
@Table(name = "inventory_customer_material_fact")
class InventoryCustomerMaterialFactJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var customerId: UUID,
    @Column(nullable = false, updatable = false) var workOrderId: UUID,
    @Column(nullable = false, length = 120, updatable = false) var itemCategory: String,
    @Column(nullable = false, updatable = false) var quantity: Int,
    @Column(nullable = false, updatable = false) var installed: Boolean,
    @Column(nullable = false, updatable = false) var returned: Boolean,
    @Column(nullable = false, updatable = false) var recordedAt: Instant,
    @Column(nullable = false, length = 240, updatable = false) var operationKey: String,
    @Column(nullable = false, length = 128, updatable = false) var payloadHash: String,
) : TenantAwareJpaEntity(id)

interface InventoryCustomerMaterialFactJpaRepository : JpaRepository<InventoryCustomerMaterialFactJpaEntity, UUID> {
    fun findByTenantIdAndOperationKey(tenantId: UUID, operationKey: String): InventoryCustomerMaterialFactJpaEntity?

    fun findAllByTenantIdAndCustomerIdOrderByRecordedAt(tenantId: UUID, customerId: UUID): List<InventoryCustomerMaterialFactJpaEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO inventory_customer_material_fact (
                id, tenant_id, customer_id, work_order_id, item_category, quantity, installed,
                returned, recorded_at, operation_key, payload_hash
            ) VALUES (
                :id, :tenantId, :customerId, :workOrderId, :itemCategory, :quantity, :installed,
                :returned, :recordedAt, :operationKey, :payloadHash
            )
            ON CONFLICT (tenant_id, operation_key) DO NOTHING
        """,
        nativeQuery = true,
    )
    @Suppress("LongParameterList")
    fun insertIfAbsent(
        id: UUID,
        tenantId: UUID,
        customerId: UUID,
        workOrderId: UUID,
        itemCategory: String,
        quantity: Int,
        installed: Boolean,
        returned: Boolean,
        recordedAt: Instant,
        operationKey: String,
        payloadHash: String,
    ): Int
}

@Component
class MaterialConsumptionPersistenceAdapter(
    private val facts: InventoryCustomerMaterialFactJpaRepository,
) : MaterialConsumptionRepository {

    override fun findByOperation(tenantId: UUID, operationKey: String): RecordedMaterialFact? =
        facts.findByTenantIdAndOperationKey(tenantId, operationKey)?.toRecorded()

    override fun appendIfAbsent(fact: CustomerMaterialFact, operationKey: String, payloadHash: String): RecordedMaterialFact? {
        val inserted = facts.insertIfAbsent(
            UuidV7.generate(), fact.tenantId, fact.customerId, fact.workOrderId, fact.itemCategory,
            fact.quantity, fact.installed, fact.returned, fact.recordedAt, operationKey, payloadHash,
        )
        if (inserted == 0) {
            // Pemenang balapan sudah commit ketika ON CONFLICT DO NOTHING melepas kuncinya,
            // jadi baris ini PASTI terbaca di sini.
            return facts.findByTenantIdAndOperationKey(fact.tenantId, operationKey)?.toRecorded()
                ?: throw NotFoundException("Fakta material hilang saat sisipan bersamaan")
        }
        return null
    }

    override fun forCustomer(tenantId: UUID, customerId: UUID): List<CustomerMaterialFact> =
        facts.findAllByTenantIdAndCustomerIdOrderByRecordedAt(tenantId, customerId).map { it.toDomain() }

    private fun InventoryCustomerMaterialFactJpaEntity.toRecorded() = RecordedMaterialFact(toDomain(), payloadHash)

    private fun InventoryCustomerMaterialFactJpaEntity.toDomain() = CustomerMaterialFact(
        tenantId!!, customerId, workOrderId, itemCategory, quantity, installed, returned, recordedAt,
    )
}
