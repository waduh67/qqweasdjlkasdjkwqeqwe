package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.EmailSubjectRepository
import com.duluin.ftth.notification.application.port.outbound.PlatformEmailSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.TenantEmailSettingsRepository
import com.duluin.ftth.notification.domain.model.EmailBranding
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import com.duluin.ftth.notification.domain.model.TenantEmailSettings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Adapter setelan email platform sekaligus BATAS ENKRIPSI password SMTP: domain memegang
 * password apa adanya, DB hanya pernah melihat ciphertext (pola
 * `PivotMasterConfigPersistenceAdapter` di module billing). Singleton global — [find]
 * mengambil baris tunggal.
 */
@Component
class PlatformEmailSettingsPersistenceAdapter(
    private val jpa: PlatformEmailSettingJpaRepository,
    private val cipher: SecretCipher,
) : PlatformEmailSettingsRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun find(): PlatformEmailSettings? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(settings: PlatformEmailSettings): PlatformEmailSettings {
        val encryptedPassword = settings.smtpPassword?.let(cipher::encrypt)
        val b = settings.branding
        val entity = jpa.findById(settings.id).orElse(null)?.apply {
            smtpHost = settings.smtpHost
            smtpPort = settings.smtpPort
            smtpUsername = settings.smtpUsername
            smtpPassword = encryptedPassword
            smtpAuth = settings.smtpAuth
            smtpStartTls = settings.smtpStartTls
            fromAddress = settings.fromAddress
            fromName = settings.fromName
            logoStorageKey = b.logoStorageKey
            logoContentType = b.logoContentType
            accentColor = b.accentColor
            footerText = b.footerText
            signatureText = b.signatureText
            publicBaseUrl = settings.publicBaseUrl
        } ?: PlatformEmailSettingJpaEntity(
            id = settings.id,
            smtpHost = settings.smtpHost,
            smtpPort = settings.smtpPort,
            smtpUsername = settings.smtpUsername,
            smtpPassword = encryptedPassword,
            smtpAuth = settings.smtpAuth,
            smtpStartTls = settings.smtpStartTls,
            fromAddress = settings.fromAddress,
            fromName = settings.fromName,
            logoStorageKey = b.logoStorageKey,
            logoContentType = b.logoContentType,
            accentColor = b.accentColor,
            footerText = b.footerText,
            signatureText = b.signatureText,
            publicBaseUrl = settings.publicBaseUrl,
        )
        return jpa.save(entity).toDomain()
    }

    private fun PlatformEmailSettingJpaEntity.toDomain(): PlatformEmailSettings = PlatformEmailSettings.rehydrate(
        id = id,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpUsername = smtpUsername,
        // Password yang tak bisa didekripsi (mis. kunci dirotasi tanpa migrasi) tak boleh
        // menggagalkan pemuatan setelan: barisnya tetap tampil, hanya kehilangan password
        // dan bisa diisi ulang operator — pola yang sama dengan token gateway WA.
        smtpPassword = smtpPassword?.let { ciphertext ->
            runCatching { cipher.decrypt(ciphertext) }
                .onFailure { log.warn("Password SMTP platform tidak bisa didekripsi; perlu diisi ulang") }
                .getOrNull()
        },
        smtpAuth = smtpAuth,
        smtpStartTls = smtpStartTls,
        fromAddress = fromAddress,
        fromName = fromName,
        branding = EmailBranding(logoStorageKey, logoContentType, accentColor, footerText, signatureText),
        publicBaseUrl = publicBaseUrl,
    )
}

/** Adapter timpaan email tenant. Tak ada rahasia di sini — tenant tak memegang kredensial SMTP. */
@Component
class TenantEmailSettingsPersistenceAdapter(
    private val jpa: TenantEmailSettingJpaRepository,
) : TenantEmailSettingsRepository {

    override fun find(): TenantEmailSettings? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(settings: TenantEmailSettings): TenantEmailSettings {
        val b = settings.branding
        val entity = jpa.findById(settings.id).orElse(null)?.apply {
            replyToAddress = settings.replyToAddress
            fromName = settings.fromName
            logoStorageKey = b.logoStorageKey
            logoContentType = b.logoContentType
            accentColor = b.accentColor
            footerText = b.footerText
            signatureText = b.signatureText
        } ?: TenantEmailSettingJpaEntity(
            id = settings.id,
            replyToAddress = settings.replyToAddress,
            fromName = settings.fromName,
            logoStorageKey = b.logoStorageKey,
            logoContentType = b.logoContentType,
            accentColor = b.accentColor,
            footerText = b.footerText,
            signatureText = b.signatureText,
        )
        return jpa.save(entity).toDomain()
    }

    private fun TenantEmailSettingJpaEntity.toDomain(): TenantEmailSettings = TenantEmailSettings.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        replyToAddress = replyToAddress,
        fromName = fromName,
        branding = EmailBranding(logoStorageKey, logoContentType, accentColor, footerText, signatureText),
    )
}

/**
 * Adapter subjek dua tingkat. `replace*` menghapus lalu menulis ulang seluruh peta,
 * bukan meng-upsert baris demi baris: pemicu yang hilang dari peta memang harus kembali
 * ke subjek bawaan, dan jumlah barisnya paling banyak delapan sehingga menulis ulang
 * jauh lebih sederhana daripada mendiff.
 */
@Component
class EmailSubjectPersistenceAdapter(
    private val platform: PlatformEmailSubjectJpaRepository,
    private val tenant: TenantEmailSubjectJpaRepository,
) : EmailSubjectRepository {

    override fun platformSubjects(): Map<NotificationTrigger, String> =
        platform.findAll().associate { it.trigger to it.subject }

    override fun tenantSubjects(): Map<NotificationTrigger, String> =
        tenant.findAll().associate { it.trigger to it.subject }

    override fun replacePlatform(subjects: Map<NotificationTrigger, String>) {
        platform.deleteAllInBatch()
        platform.saveAll(subjects.map { (trigger, subject) -> PlatformEmailSubjectJpaEntity(UuidV7.generate(), trigger, subject) })
    }

    override fun replaceTenant(subjects: Map<NotificationTrigger, String>) {
        // deleteAll (bukan deleteAllInBatch): batch bypass filter @TenantId, sedangkan
        // baris tenant lain wajib tak tersentuh. RLS jadi jaring pengaman terakhir.
        tenant.deleteAll(tenant.findAll())
        tenant.saveAll(subjects.map { (trigger, subject) -> TenantEmailSubjectJpaEntity(UuidV7.generate(), trigger, subject) })
    }
}
