package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Berkas impor CSV (V192).
 *
 * `createdAt`/`updatedAt` SENGAJA tidak dideklarasikan ulang di sini: keduanya milik
 * `BaseJpaEntity`, dan nama field superclass yang ditiru subclass DIAM-DIAM diabaikan
 * Hibernate — kolomnya lenyap dari INSERT tanpa satu peringatan pun. Aturan yang sama berlaku
 * untuk `primaryKey` dan `entityId`.
 */
@Entity
@Table(name = "order_import_batch")
@Suppress("LongParameterList")
class OrderImportBatchJpaEntity(
    id: UUID,
    @Column(name = "file_name", nullable = false, length = 255) var fileName: String,
    @Column(name = "content_hash", nullable = false, length = 64, updatable = false) var contentHash: String,
    @Column(name = "byte_size", nullable = false, updatable = false) var byteSize: Long,
    // char(1) di Postgres. Disimpan supaya keluhan "kenapa semua baris saya ditolak" bisa
    // dijawab tanpa menebak pemisah apa yang dipakai Excel di laptop operator.
    @Column(name = "delimiter", nullable = false, length = 1, updatable = false) var delimiter: String,
    @Column(name = "status", nullable = false, length = 16) var status: String,
    @Column(name = "total_rows", nullable = false) var totalRows: Int,
    @Column(name = "accepted_rows", nullable = false) var acceptedRows: Int,
    @Column(name = "rejected_rows", nullable = false) var rejectedRows: Int,
    @Column(name = "created_rows", nullable = false) var createdRows: Int,
    @Column(name = "failed_rows", nullable = false) var failedRows: Int,
    @Column(name = "imported_by", updatable = false) var importedBy: UUID?,
    @Column(name = "committed_at") var committedAt: Instant?,
) : TenantAwareJpaEntity(id)

/** Satu baris berkas impor (V193). */
@Entity
@Table(name = "order_import_row")
@Suppress("LongParameterList")
class OrderImportRowJpaEntity(
    id: UUID,
    @Column(name = "batch_id", nullable = false, updatable = false) var batchId: UUID,
    @Column(name = "line_number", nullable = false, updatable = false) var lineNumber: Int,
    @Column(name = "fingerprint", length = 64, updatable = false) var fingerprint: String?,
    @Column(name = "status", nullable = false, length = 16) var status: String,
    @Column(name = "message", length = 500) var message: String?,
    @Column(name = "name", length = 150) var name: String?,
    @Column(name = "phone", length = 32) var phone: String?,
    @Column(name = "email", length = 200) var email: String?,
    @Column(name = "plan_id") var planId: UUID?,
    @Column(name = "address", columnDefinition = "text") var address: String?,
    @Column(name = "city", length = 120) var city: String?,
    @Column(name = "postal_code", length = 24) var postalCode: String?,
    @Column(name = "notes", length = 1000) var notes: String?,
    @Column(name = "order_id") var orderId: UUID?,
    @Column(name = "lead_id") var leadId: UUID?,
    @Column(name = "order_number", length = 20) var orderNumber: String?,
) : TenantAwareJpaEntity(id)
