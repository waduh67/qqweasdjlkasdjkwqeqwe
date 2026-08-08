package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateSource
import com.duluin.ftth.notification.domain.model.TemplateStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Satu template pesan di katalog tenant. Mutable: hasil sync menimpa status/kategori/body
 * setiap kali "Tarik dari Meta" dijalankan.
 */
@Entity
@Table(name = "notification_message_template")
class NotificationMessageTemplateJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 128)
    var name: String,

    @Column(nullable = false, length = 10)
    var language: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var category: TemplateCategory,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TemplateStatus,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var source: TemplateSource,

    @Column(name = "meta_template_id", length = 64)
    var metaTemplateId: String?,

    @Column(name = "body_preview", length = 1024)
    var bodyPreview: String?,

    @Column(name = "body_param_count", nullable = false)
    var bodyParamCount: Int,

    @Column(name = "synced_at")
    var syncedAt: Instant?,
) : TenantAwareJpaEntity(id)

/**
 * Pemetaan satu pemicu ke satu template. Tanpa relasi JPA ke
 * [NotificationMessageTemplateJpaEntity] (pola [BroadcastRecipientJpaEntity]) — adapter yang
 * menjodohkan lewat id, agar tak ada lazy-loading tersembunyi di jalur kirim.
 * `trigger` dipetakan eksplisit karena namanya kata kunci SQL.
 */
@Entity
@Table(name = "notification_trigger_template")
class NotificationTriggerTemplateJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 30)
    var trigger: NotificationTrigger,

    @Column(name = "template_id", nullable = false)
    var templateId: UUID,
) : TenantAwareJpaEntity(id)
