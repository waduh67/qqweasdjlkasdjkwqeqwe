package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.order.application.port.outbound.OrderImportRepository
import com.duluin.ftth.order.domain.model.OrderImportBatch
import com.duluin.ftth.order.domain.model.OrderImportBatchStatus
import com.duluin.ftth.order.domain.model.OrderImportRow
import com.duluin.ftth.order.domain.model.OrderImportRowStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.util.UUID

interface OrderImportBatchJpaRepository : JpaRepository<OrderImportBatchJpaEntity, UUID> {
    /** `@TenantId` menambahkan predikat tenant sendiri; RLS adalah lapisan keduanya. */
    fun findByContentHash(contentHash: String): OrderImportBatchJpaEntity?
}

interface OrderImportRowJpaRepository : JpaRepository<OrderImportRowJpaEntity, UUID> {
    fun findAllByBatchIdOrderByLineNumberAsc(batchId: UUID): List<OrderImportRowJpaEntity>
}

@Component
class OrderImportPersistenceAdapter(
    private val batches: OrderImportBatchJpaRepository,
    private val rowStore: OrderImportRowJpaRepository,
) : OrderImportRepository {

    @PersistenceContext private lateinit var entityManager: EntityManager

    /**
     * Simpan/perbarui batch. Yang boleh berubah setelah lahir hanyalah cacah hasil eksekusi dan
     * penutupannya: nama berkas, hash, ukuran, dan pemisah adalah FAKTA tentang berkas yang
     * diunggah, dan menimpanya berarti menghapus jejak audit yang jadi satu-satunya alasan
     * tabel ini ada.
     */
    override fun saveBatch(batch: OrderImportBatch) {
        val entity = batches.findById(batch.id).orElse(null)?.apply {
            status = batch.status.name
            totalRows = batch.totalRows
            acceptedRows = batch.acceptedRows
            rejectedRows = batch.rejectedRows
            createdRows = batch.createdRows
            failedRows = batch.failedRows
            committedAt = batch.committedAt
        } ?: OrderImportBatchJpaEntity(
            id = batch.id,
            fileName = batch.fileName,
            contentHash = batch.contentHash,
            byteSize = batch.byteSize,
            delimiter = batch.delimiter.toString(),
            status = batch.status.name,
            totalRows = batch.totalRows,
            acceptedRows = batch.acceptedRows,
            rejectedRows = batch.rejectedRows,
            createdRows = batch.createdRows,
            failedRows = batch.failedRows,
            importedBy = batch.importedBy,
            committedAt = batch.committedAt,
        )
        /*
         * `saveAndFlush`, bukan `save`: benturan `uq_order_import_batch_content` HARUS muncul di
         * sini supaya pemanggil bisa menerjemahkannya jadi "batch yang sama sudah ada". Kalau
         * ditunda sampai commit transaksi, galatnya keluar di tempat yang tak punya konteks apa
         * pun dan operator menerima 500 tanpa penjelasan.
         */
        batches.saveAndFlush(entity)
    }

    override fun findBatch(id: UUID): OrderImportBatch? = batches.findById(id).orElse(null)?.toDomain()

    override fun findBatchByContentHash(tenantId: UUID, contentHash: String): OrderImportBatch? =
        batches.findByContentHash(contentHash)?.toDomain()

    override fun recentBatches(limit: Int): List<OrderImportBatch> = entityManager.createQuery(
        "select b from OrderImportBatchJpaEntity b order by b.createdAt desc, b.id desc",
        OrderImportBatchJpaEntity::class.java,
    ).setMaxResults(limit.coerceIn(1, MAX_HISTORY)).resultList.map { it.toDomain() }

    override fun saveRows(rows: List<OrderImportRow>) = rows.forEach(::saveRow)

    override fun saveRow(row: OrderImportRow) {
        val entity = rowStore.findById(row.id).orElse(null)?.apply {
            status = row.status.name
            message = row.message
            orderId = row.orderId
            leadId = row.leadId
            orderNumber = row.orderNumber
        } ?: OrderImportRowJpaEntity(
            id = row.id,
            batchId = row.batchId,
            lineNumber = row.lineNumber,
            fingerprint = row.fingerprint,
            status = row.status.name,
            message = row.message,
            name = row.name,
            phone = row.phone,
            email = row.email,
            planId = row.planId,
            address = row.address,
            city = row.city,
            postalCode = row.postalCode,
            notes = row.notes,
            orderId = row.orderId,
            leadId = row.leadId,
            orderNumber = row.orderNumber,
        )
        rowStore.save(entity)
    }

    override fun findRows(batchId: UUID): List<OrderImportRow> =
        rowStore.findAllByBatchIdOrderByLineNumberAsc(batchId).map { it.toDomain() }

    override fun findRow(id: UUID): OrderImportRow? = rowStore.findById(id).orElse(null)?.toDomain()

    /**
     * `tenantId` dibaca dari entitas bila ada, jatuh ke `TenantContext` bila belum terisi.
     * Hibernate baru mengisi `@TenantId` saat INSERT-nya benar-benar di-flush — entitas yang
     * baru saja dibuat di memori masih bernilai null di sana.
     */
    private fun OrderImportBatchJpaEntity.toDomain() = OrderImportBatch.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        fileName = fileName,
        contentHash = contentHash,
        byteSize = byteSize,
        delimiter = delimiter.firstOrNull() ?: ',',
        status = OrderImportBatchStatus.valueOf(status),
        totalRows = totalRows,
        acceptedRows = acceptedRows,
        rejectedRows = rejectedRows,
        createdRows = createdRows,
        failedRows = failedRows,
        importedBy = importedBy,
        committedAt = committedAt,
        createdAt = createdAt,
    )

    private fun OrderImportRowJpaEntity.toDomain() = OrderImportRow.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        batchId = batchId,
        lineNumber = lineNumber,
        fingerprint = fingerprint,
        status = OrderImportRowStatus.valueOf(status),
        message = message,
        name = name,
        phone = phone,
        email = email,
        planId = planId,
        address = address,
        city = city,
        postalCode = postalCode,
        notes = notes,
        orderId = orderId,
        leadId = leadId,
        orderNumber = orderNumber,
        createdAt = createdAt,
    )

    private companion object {
        const val MAX_HISTORY = 50
    }
}
