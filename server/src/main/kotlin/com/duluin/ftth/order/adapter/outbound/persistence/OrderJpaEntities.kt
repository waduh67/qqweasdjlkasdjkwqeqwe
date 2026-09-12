package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "order_record")
@Suppress("LongParameterList")
class OrderJpaEntity(
    id: UUID,
    // Nullable sejak V177: pemesan boleh berupa calon pelanggan yang belum jadi `customer`.
    // `updatable = false` DIPERTAHANKAN — memindahkan pesanan ke pemilik lain bukan operasi
    // yang sah; promosi lead→customer membuat pesanan BARU menunjuk customer, bukan menimpa.
    @Column(name = "customer_id", updatable = false) var customerId: UUID?,
    @Column(name = "lead_id", updatable = false) var leadId: UUID?,
    @Column(name = "order_number", nullable = false, updatable = false, length = 20) var orderNumber: String,
    @Column(nullable = false, length = 24) var status: String,
    @Column(nullable = false) var revision: Long,
    @Column(name = "address_text", nullable = false) var address: String,
    @Column(nullable = false, length = 120) var city: String,
    @Column(name = "postal_code", nullable = false, length = 24) var postalCode: String,
    var latitude: Double?, var longitude: Double?,
    @Column(name = "appointment_starts_at") var appointmentStartsAt: Instant?,
    @Column(name = "appointment_ends_at") var appointmentEndsAt: Instant?,
    @Column(name = "cancellation_reason", length = 500) var cancellationReason: String?,
    @Column(name = "rejection_reason", length = 500) var rejectionReason: String?,
    @Column(name = "last_actor_id") var lastActorId: UUID?,
    @Column(name = "last_operation_namespace", nullable = false, length = 120) var lastOperationNamespace: String,
    @Column(name = "last_operation_key", nullable = false, length = 240) var lastOperationKey: String,
    @Column(name = "last_operation_hash", nullable = false, length = 128) var lastOperationHash: String,
    // Penanda portal (V184). SENGAJA di akhir dengan default null supaya pemanggil posisional
    // yang sudah ada tak perlu diubah — dan supaya pesanan lama lahir tanpa penanda.
    @Column(name = "portal_flag", length = 24) var portalFlag: String? = null,
    @Column(name = "portal_flag_reason", length = 300) var portalFlagReason: String? = null,
    // Asal penanda (V194). WAJIB sinkron dengan `portalFlag`: DB menegakkan
    // ck_order_record_portal_flag_source_pair, jadi menyalin salah satunya saja akan meledak
    // sebagai DataIntegrityViolationException di flush — jauh dari baris yang salah.
    @Column(name = "portal_flag_source", length = 16) var portalFlagSource: String? = null,
    @Version @Column(name = "persistence_revision", nullable = false) var persistenceRevision: Long? = null,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "order_line")
class OrderLineJpaEntity(
    id: UUID,
    @Column(name = "order_id", nullable = false, updatable = false) var orderId: UUID,
    @Column(name = "catalog_item_id", nullable = false) var catalogItemId: UUID,
    @Column(nullable = false, length = 300) var description: String,
    @Column(nullable = false) var quantity: Int,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "order_operation")
class OrderOperationJpaEntity(
    id: UUID,
    @Column(name = "namespace", nullable = false, length = 120) var namespace: String,
    @Column(name = "operation_key", nullable = false, length = 240) var operationKey: String,
    @Column(name = "payload_hash", nullable = false, length = 128) var payloadHash: String,
    @Column(name = "outcome_json", nullable = false, columnDefinition = "text") var outcomeJson: String,
) : TenantAwareJpaEntity(id)

/**
 * Calon pelanggan.
 *
 * `createdAt`/`updatedAt` SENGAJA tidak dideklarasikan di sini: keduanya sudah milik
 * `BaseJpaEntity`, dan nama field superclass yang ditiru subclass akan DIAM-DIAM diabaikan
 * Hibernate — kolomnya hilang dari INSERT tanpa peringatan apa pun. Aturan yang sama berlaku
 * untuk `primaryKey` dan `entityId`.
 */
@Entity
@Table(name = "order_lead")
@Suppress("LongParameterList")
class OrderLeadJpaEntity(
    id: UUID,
    @Column(nullable = false, length = 150) var name: String,
    @Column(nullable = false, length = 32) var phone: String,
    @Column(length = 200) var email: String?,
    @Column(columnDefinition = "text") var address: String?,
    var latitude: Double?,
    var longitude: Double?,
    @Column(name = "interested_plan_id") var interestedPlanId: UUID?,
    @Column(nullable = false, length = 16, updatable = false) var source: String,
    @Column(nullable = false, length = 16) var status: String,
    @Column(name = "converted_customer_id") var convertedCustomerId: UUID?,
    @Column(length = 1000) var notes: String?,
) : TenantAwareJpaEntity(id)
