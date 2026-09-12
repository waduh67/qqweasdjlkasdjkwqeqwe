package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.inventory.application.port.outbound.WorkOrderMaterialRepository
import com.duluin.ftth.inventory.application.port.outbound.WorkOrderMaterialTemplateRepository
import com.duluin.ftth.inventory.domain.model.MaterialOutcome
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialLine
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialSerial
import com.duluin.ftth.inventory.domain.model.WorkOrderMaterialTemplateLine
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "work_order_material")
class WorkOrderMaterialJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var workOrderId: UUID,
    @Column(nullable = false, length = 24, updatable = false) var workOrderType: String,
    @Column(nullable = false, updatable = false) var itemId: UUID,
    @Column(nullable = false, updatable = false) var customerId: UUID,
    @Column(nullable = false, length = 24, updatable = false) var itemCategory: String,
    @Column(nullable = false, updatable = false) var serialized: Boolean,
    @Column var templateQuantity: Int?,
    @Column(nullable = false) var plannedQuantity: Int,
    @Column(nullable = false) var issuedQuantity: Int,
    @Column(nullable = false) var usedQuantity: Int,
    @Column(nullable = false) var returnedQuantity: Int,
    @Column(nullable = false) var lostQuantity: Int,
    @Column var technicianId: UUID?,
    @Column var technicianLocationId: UUID?,
    @Column(length = 500) var varianceReason: String?,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "work_order_material_serial")
class WorkOrderMaterialSerialJpaEntity(
    id: UUID,
    @Column(nullable = false, updatable = false) var materialId: UUID,
    @Column(nullable = false, updatable = false) var workOrderId: UUID,
    @Column(nullable = false, updatable = false) var assetId: UUID,
    @Column(nullable = false, length = 128, updatable = false) var serialNumber: String,
    @Column(length = 32) var macAddress: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var outcome: MaterialOutcome,
    @Column(nullable = false) var scannedAt: Instant,
    @Column(nullable = false) var scannedBy: UUID,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "work_order_material_template")
class WorkOrderMaterialTemplateJpaEntity(
    id: UUID,
    @Column(nullable = false, length = 24, updatable = false) var workOrderType: String,
    @Column(nullable = false, updatable = false) var itemId: UUID,
    @Column(nullable = false) var plannedQuantity: Int,
    @Column(length = 200) var note: String?,
) : TenantAwareJpaEntity(id)

interface WorkOrderMaterialJpaRepository : JpaRepository<WorkOrderMaterialJpaEntity, UUID> {
    fun findAllByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId: UUID, workOrderId: UUID): List<WorkOrderMaterialJpaEntity>
    fun findByTenantIdAndWorkOrderIdAndItemId(tenantId: UUID, workOrderId: UUID, itemId: UUID): WorkOrderMaterialJpaEntity?
}

interface WorkOrderMaterialSerialJpaRepository : JpaRepository<WorkOrderMaterialSerialJpaEntity, UUID> {
    fun findAllByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId: UUID, workOrderId: UUID): List<WorkOrderMaterialSerialJpaEntity>
    fun findAllByTenantIdAndMaterialIdOrderByCreatedAtAsc(tenantId: UUID, materialId: UUID): List<WorkOrderMaterialSerialJpaEntity>
    fun findByTenantIdAndAssetIdAndOutcome(tenantId: UUID, assetId: UUID, outcome: MaterialOutcome): WorkOrderMaterialSerialJpaEntity?
    fun deleteAllByTenantIdAndMaterialId(tenantId: UUID, materialId: UUID)
}

interface WorkOrderMaterialTemplateJpaRepository : JpaRepository<WorkOrderMaterialTemplateJpaEntity, UUID> {
    fun findAllByTenantIdAndWorkOrderTypeOrderByCreatedAtAsc(tenantId: UUID, workOrderType: String): List<WorkOrderMaterialTemplateJpaEntity>
    fun findAllByTenantIdOrderByWorkOrderTypeAscCreatedAtAsc(tenantId: UUID): List<WorkOrderMaterialTemplateJpaEntity>
    fun deleteAllByTenantIdAndWorkOrderType(tenantId: UUID, workOrderType: String)
}

@Component
class WorkOrderMaterialPersistenceAdapter(
    private val lines: WorkOrderMaterialJpaRepository,
    private val serials: WorkOrderMaterialSerialJpaRepository,
) : WorkOrderMaterialRepository {

    /**
     * Serial diambil SEKALI untuk seluruh WO lalu dikelompokkan di memori, bukan satu query
     * per baris material. Satu WO PSB bisa punya belasan baris; bentuk per-baris menjadikan
     * layar material teknisi N+1 pada jalur yang dipanggil setiap kali WO dibuka.
     */
    override fun findByWorkOrder(tenantId: UUID, workOrderId: UUID): List<WorkOrderMaterialLine> {
        val rows = lines.findAllByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId, workOrderId)
        if (rows.isEmpty()) return emptyList()
        val byMaterial = serials.findAllByTenantIdAndWorkOrderIdOrderByCreatedAtAsc(tenantId, workOrderId)
            .groupBy { it.materialId }
        return rows.map { it.toDomain(byMaterial[it.id].orEmpty().map { serial -> serial.toDomain() }) }
    }

    override fun findByWorkOrderAndItem(tenantId: UUID, workOrderId: UUID, itemId: UUID): WorkOrderMaterialLine? {
        val row = lines.findByTenantIdAndWorkOrderIdAndItemId(tenantId, workOrderId, itemId) ?: return null
        return row.toDomain(serials.findAllByTenantIdAndMaterialIdOrderByCreatedAtAsc(tenantId, row.id).map { it.toDomain() })
    }

    override fun findInstalledSerialByAsset(tenantId: UUID, assetId: UUID): WorkOrderMaterialSerial? =
        serials.findByTenantIdAndAssetIdAndOutcome(tenantId, assetId, MaterialOutcome.INSTALLED)?.toDomain()

    /**
     * Jalur INSERT mengembalikan objek domain yang DITERIMA, bukan `saved.toDomain()`.
     *
     * `@TenantId` baru diisi Hibernate saat INSERT-nya di-flush; membaca `tenantId!!` dari
     * entity yang baru disimpan melempar NPE. Pola yang sama dipakai di
     * `InventoryPersistence.kt` dan `InventoryItemPersistence.kt`.
     */
    override fun save(line: WorkOrderMaterialLine): WorkOrderMaterialLine {
        val entity = lines.findById(line.id).orElse(null)
        if (entity == null) {
            lines.save(
                WorkOrderMaterialJpaEntity(
                    line.id, line.workOrderId, line.workOrderType, line.itemId, line.customerId,
                    line.itemCategory, line.serialized, line.templateQuantity, line.plannedQuantity,
                    line.issuedQuantity, line.usedQuantity, line.returnedQuantity, line.lostQuantity,
                    line.technicianId, line.technicianLocationId, line.varianceReason,
                ),
            )
            return line
        }
        entity.templateQuantity = line.templateQuantity
        entity.plannedQuantity = line.plannedQuantity
        entity.issuedQuantity = line.issuedQuantity
        entity.usedQuantity = line.usedQuantity
        entity.returnedQuantity = line.returnedQuantity
        entity.lostQuantity = line.lostQuantity
        entity.technicianId = line.technicianId
        entity.technicianLocationId = line.technicianLocationId
        entity.varianceReason = line.varianceReason
        lines.save(entity)
        return line
    }

    override fun saveSerial(serial: WorkOrderMaterialSerial): WorkOrderMaterialSerial {
        val entity = serials.findById(serial.id).orElse(null)
        if (entity == null) {
            serials.save(
                WorkOrderMaterialSerialJpaEntity(
                    serial.id, serial.materialId, serial.workOrderId, serial.assetId,
                    serial.serialNumber, serial.macAddress, serial.outcome, serial.scannedAt, serial.scannedBy,
                ),
            )
            return serial
        }
        entity.macAddress = serial.macAddress
        entity.outcome = serial.outcome
        entity.scannedAt = serial.scannedAt
        entity.scannedBy = serial.scannedBy
        serials.save(entity)
        return serial
    }

    override fun deleteLine(tenantId: UUID, id: UUID) {
        serials.deleteAllByTenantIdAndMaterialId(tenantId, id)
        lines.deleteById(id)
    }

    private fun WorkOrderMaterialJpaEntity.toDomain(rows: List<WorkOrderMaterialSerial>) = WorkOrderMaterialLine(
        id, tenantId!!, workOrderId, workOrderType, itemId, customerId, itemCategory, serialized,
        templateQuantity, plannedQuantity, issuedQuantity, usedQuantity, returnedQuantity, lostQuantity,
        technicianId, technicianLocationId, varianceReason, rows,
    )

    private fun WorkOrderMaterialSerialJpaEntity.toDomain() = WorkOrderMaterialSerial(
        id, tenantId!!, materialId, workOrderId, assetId, serialNumber, macAddress, outcome, scannedAt, scannedBy,
    )
}

@Component
class WorkOrderMaterialTemplatePersistenceAdapter(
    private val templates: WorkOrderMaterialTemplateJpaRepository,
) : WorkOrderMaterialTemplateRepository {

    override fun findByType(tenantId: UUID, workOrderType: String): List<WorkOrderMaterialTemplateLine> =
        templates.findAllByTenantIdAndWorkOrderTypeOrderByCreatedAtAsc(tenantId, workOrderType).map { it.toDomain() }

    override fun findAll(tenantId: UUID): List<WorkOrderMaterialTemplateLine> =
        templates.findAllByTenantIdOrderByWorkOrderTypeAscCreatedAtAsc(tenantId).map { it.toDomain() }

    /**
     * BOM disimpan sebagai GANTI SELURUHNYA, bukan tambal per baris.
     *
     * Sunting per baris menuntut klien mengirim id baris yang tidak pernah ia lihat, dan
     * item yang dihapus dari template tidak akan pernah punya request "hapus" — ia hanya
     * hilang dari daftar yang dikirim, lalu tetap hidup di basis data dan terus mempra-isi
     * rencana WO dengan barang yang sudah tidak dipakai lagi.
     */
    override fun replaceType(
        tenantId: UUID,
        workOrderType: String,
        lines: List<WorkOrderMaterialTemplateLine>,
    ): List<WorkOrderMaterialTemplateLine> {
        templates.deleteAllByTenantIdAndWorkOrderType(tenantId, workOrderType)
        // Flush eksplisit: tanpa ini Hibernate bisa menunda DELETE sampai setelah INSERT dan
        // UNIQUE (tenant_id, work_order_type, item_id) meledak untuk item yang sebenarnya
        // hanya diubah jumlahnya.
        templates.flush()
        lines.forEach {
            templates.save(WorkOrderMaterialTemplateJpaEntity(it.id, it.workOrderType, it.itemId, it.plannedQuantity, it.note))
        }
        return lines
    }

    private fun WorkOrderMaterialTemplateJpaEntity.toDomain() =
        WorkOrderMaterialTemplateLine(id, tenantId!!, workOrderType, itemId, plannedQuantity, note)
}
