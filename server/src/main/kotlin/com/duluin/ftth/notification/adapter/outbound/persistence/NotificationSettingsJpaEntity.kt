package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Satu baris setelan notifikasi per tenant. Berbeda dari [BroadcastJpaEntity] yang
 * append-only, entity ini mutable (kolom tanpa `updatable = false`) karena setelan
 * memang disunting berulang. [httpToken]/[metaAccessToken]/[qontakAccessToken] menyimpan
 * CIPHERTEXT — enkripsi terjadi di adapter, DB tak pernah melihat token asli.
 */
@Entity
@Table(name = "notification_settings")
class NotificationSettingsJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: WhatsAppProvider,

    @Column(name = "gateway_enabled", nullable = false)
    var gatewayEnabled: Boolean,

    @Column(name = "email_enabled", nullable = false)
    var emailEnabled: Boolean,

    @Column(name = "http_endpoint_url", length = 500)
    var httpEndpointUrl: String?,

    @Column(name = "http_token", length = 512)
    var httpToken: String?,

    @Column(name = "http_phone_field", nullable = false, length = 50)
    var httpPhoneField: String,

    @Column(name = "http_message_field", nullable = false, length = 50)
    var httpMessageField: String,

    @Column(name = "meta_phone_number_id", length = 64)
    var metaPhoneNumberId: String?,

    @Column(name = "meta_access_token", length = 2048)
    var metaAccessToken: String?,

    @Column(name = "meta_waba_id", length = 64)
    var metaWabaId: String?,

    @Column(name = "qontak_access_token", length = 2048)
    var qontakAccessToken: String?,

    @Column(name = "qontak_channel_integration_id", length = 64)
    var qontakChannelIntegrationId: String?,

    @Column(name = "notify_subscription_lifecycle", nullable = false)
    var notifyOnSubscriptionLifecycle: Boolean,

    @Column(name = "notify_invoice_reminder", nullable = false)
    var notifyOnInvoiceReminder: Boolean,

    @Column(name = "notify_work_order_schedule", nullable = false)
    var notifyOnWorkOrderSchedule: Boolean,

    @Column(name = "notify_incident_open", nullable = false)
    var notifyOnIncidentOpen: Boolean,
) : TenantAwareJpaEntity(id)
