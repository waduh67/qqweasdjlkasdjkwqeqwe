package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationChannel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notification_broadcast")
class BroadcastJpaEntity(
    id: UUID,

    @Column(name = "incident_id", updatable = false)
    var incidentId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var channel: NotificationChannel,

    @Column(nullable = false, length = 2000, updatable = false)
    var message: String,

    @Column(name = "created_by", nullable = false, updatable = false)
    var createdBy: UUID,

    // Jumlah ter-denormalisasi: broadcast bersifat titik-waktu, hitungannya tak
    // pernah berubah setelah tersiar, jadi aman disimpan agar daftar riwayat ringan.
    @Column(name = "recipient_count", nullable = false, updatable = false)
    var recipientCount: Int,

    @Column(name = "sent_count", nullable = false, updatable = false)
    var sentCount: Int,

    @Column(name = "skipped_count", nullable = false, updatable = false)
    var skippedCount: Int,

    @Column(name = "failed_count", nullable = false, updatable = false)
    var failedCount: Int,

    @Column(name = "sent_at", nullable = false, updatable = false)
    var sentAt: Instant,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "notification_broadcast_recipient")
class BroadcastRecipientJpaEntity(
    id: UUID,

    @Column(name = "broadcast_id", nullable = false, updatable = false)
    var broadcastId: UUID,

    @Column(name = "customer_id", updatable = false)
    var customerId: UUID?,

    @Column(name = "customer_name", nullable = false, length = 150, updatable = false)
    var customerName: String,

    @Column(length = 30, updatable = false)
    var phone: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var status: DeliveryStatus,

    @Column(length = 300, updatable = false)
    var detail: String?,

    @Column(nullable = false, updatable = false)
    var at: Instant,
) : TenantAwareJpaEntity(id)
