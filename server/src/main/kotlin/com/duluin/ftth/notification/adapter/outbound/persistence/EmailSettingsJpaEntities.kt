package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Setelan email platform (singleton). Tabel PLATFORM-level, tanpa RLS — karena itu
 * turunan [BaseJpaEntity], bukan [TenantAwareJpaEntity]. [smtpPassword] menyimpan
 * CIPHERTEXT; enkripsi terjadi di adapter, DB tak pernah melihat password asli.
 */
@Entity
@Table(name = "platform_email_setting")
class PlatformEmailSettingJpaEntity(
    id: UUID,

    @Column(name = "smtp_host", length = 255)
    var smtpHost: String?,

    @Column(name = "smtp_port", nullable = false)
    var smtpPort: Int,

    @Column(name = "smtp_username", length = 255)
    var smtpUsername: String?,

    @Column(name = "smtp_password", length = 1024)
    var smtpPassword: String?,

    @Column(name = "smtp_auth", nullable = false)
    var smtpAuth: Boolean,

    @Column(name = "smtp_starttls", nullable = false)
    var smtpStartTls: Boolean,

    @Column(name = "from_address", length = 254)
    var fromAddress: String?,

    @Column(name = "from_name", nullable = false, length = 100)
    var fromName: String,

    @Column(name = "logo_storage_key", length = 300)
    var logoStorageKey: String?,

    @Column(name = "logo_content_type", length = 80)
    var logoContentType: String?,

    @Column(name = "accent_color", length = 9)
    var accentColor: String?,

    @Column(name = "footer_text", length = 500)
    var footerText: String?,

    @Column(name = "signature_text", length = 200)
    var signatureText: String?,

    @Column(name = "public_base_url", length = 300)
    var publicBaseUrl: String?,
) : BaseJpaEntity(id)

/** Timpaan subjek level platform. Satu baris per pemicu; baris absen = subjek bawaan di kode. */
@Entity
@Table(name = "platform_email_subject")
class PlatformEmailSubjectJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 40)
    var trigger: NotificationTrigger,

    @Column(nullable = false, length = 200)
    var subject: String,
) : BaseJpaEntity(id)

/** Timpaan setelan email satu tenant. Semua kolom nullable: null = warisi platform. */
@Entity
@Table(name = "tenant_email_setting")
class TenantEmailSettingJpaEntity(
    id: UUID,

    /** Alamat `Reply-To` saja — `From` milik platform, tak bisa ditimpa (V100). */
    @Column(name = "reply_to_address", length = 254)
    var replyToAddress: String?,

    @Column(name = "from_name", length = 100)
    var fromName: String?,

    @Column(name = "logo_storage_key", length = 300)
    var logoStorageKey: String?,

    @Column(name = "logo_content_type", length = 80)
    var logoContentType: String?,

    @Column(name = "accent_color", length = 9)
    var accentColor: String?,

    @Column(name = "footer_text", length = 500)
    var footerText: String?,

    @Column(name = "signature_text", length = 200)
    var signatureText: String?,
) : TenantAwareJpaEntity(id)

/** Timpaan subjek level tenant. Cermin [PlatformEmailSubjectJpaEntity], tapi ber-RLS. */
@Entity
@Table(name = "tenant_email_subject")
class TenantEmailSubjectJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 40)
    var trigger: NotificationTrigger,

    @Column(nullable = false, length = 200)
    var subject: String,
) : TenantAwareJpaEntity(id)
