package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.notification.application.port.inbound.EmailSubjectView
import com.duluin.ftth.notification.application.port.inbound.EmailTestResultView
import com.duluin.ftth.notification.application.port.inbound.ManagePlatformEmailSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.PlatformEmailSettingsView
import com.duluin.ftth.notification.application.port.inbound.UpdatePlatformEmailSettingsCommand
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.application.port.outbound.EmailSubjectRepository
import com.duluin.ftth.notification.application.port.outbound.PlatformEmailSettingsRepository
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.EmailBranding
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sisi super-admin setelan email platform.
 *
 * Tiap perubahan dicatat ke jejak audit atas nama tenant platform (pola
 * `PivotMasterConfigService`): mengganti relay SMTP atau alamat pengirim menentukan atas nama
 * siapa seluruh surat aplikasi berangkat — kalau suatu hari ada email aneh yang terkirim,
 * pertanyaan pertamanya selalu "siapa yang mengubah setelannya, dan kapan".
 *
 * Enkripsi password SMTP TIDAK terjadi di sini melainkan di persistence adapter; service
 * hanya mengedarkan plaintext, persis seperti kredensial Pivot.
 */
@Service
@Transactional(readOnly = true)
@Suppress("LongParameterList")
class PlatformEmailSettingsService(
    private val repository: PlatformEmailSettingsRepository,
    private val subjectRepo: EmailSubjectRepository,
    private val brandingResolver: EmailBrandingResolver,
    private val renderer: EmailRenderer,
    private val dispatcher: EmailDispatcher,
    private val storage: ObjectStorage,
    private val auditor: AuditRecorder,
    private val tenantApi: TenantApi,
) : ManagePlatformEmailSettingsUseCase {

    override fun get(): PlatformEmailSettingsView = settings().toView()

    @Transactional
    override fun update(command: UpdatePlatformEmailSettingsCommand): PlatformEmailSettingsView {
        val settings = settings()
        settings.update(
            smtpHost = command.smtpHost,
            smtpPort = command.smtpPort,
            smtpUsername = command.smtpUsername,
            smtpPassword = command.smtpPassword,
            smtpAuth = command.smtpAuth,
            smtpStartTls = command.smtpStartTls,
            fromAddress = command.fromAddress,
            fromName = command.fromName,
            branding = EmailBranding.of(command.accentColor, command.footerText, command.signatureText),
            publicBaseUrl = command.publicBaseUrl,
        )
        val saved = repository.save(settings)
        subjectRepo.replacePlatform(sanitizeSubjects(command.subjects))
        audit("platform.email.settings.updated", saved, mapOf("smtpConfigured" to saved.smtpConfigured))
        return saved.toView()
    }

    @Transactional
    override fun uploadLogo(contentType: String, bytes: ByteArray): PlatformEmailSettingsView {
        validateLogo(contentType, bytes)
        val settings = settings()
        // Byte ke storage dulu, metadata belakangan (pola PaymentGatewaySettingsService):
        // baris yang menunjuk objek yang gagal ditulis akan menyajikan 500 selamanya.
        storage.put(LOGO_KEY, contentType, bytes)
        settings.attachLogo(LOGO_KEY, contentType)
        val saved = repository.save(settings)
        audit("platform.email.logo.uploaded", saved)
        return saved.toView()
    }

    @Transactional
    override fun deleteLogo(): PlatformEmailSettingsView {
        val settings = settings()
        settings.branding.logoStorageKey?.let { storage.delete(it) }
        settings.clearLogo()
        val saved = repository.save(settings)
        audit("platform.email.logo.deleted", saved)
        return saved.toView()
    }

    override fun getLogo(): StoredObject? {
        val key = repository.find()?.branding?.logoStorageKey?.takeIf { it.isNotBlank() } ?: return null
        return storage.get(key)
    }

    /**
     * Memakai setelan TERSIMPAN, bukan isi form yang sedang disunting: yang ingin dibuktikan
     * operator adalah bahwa email sungguhan nanti akan berangkat, dan email sungguhan berangkat
     * dari baris di DB. Karena itu UI harus menyimpan lebih dulu baru menguji.
     */
    override fun sendTest(to: String): EmailTestResultView {
        val recipient = PlatformEmailSettings.validateEmail(to, "Alamat tujuan uji")
            ?: throw ValidationException("Alamat tujuan uji wajib diisi")
        val outcome = dispatcher.send(
            renderer.render(
                to = recipient,
                subject = EmailPreviewSample.TEST_SUBJECT,
                body = EmailPreviewSample.TEST_BODY,
                identity = brandingResolver.platformOnly(),
            ),
        )
        return EmailTestResultView(
            delivered = outcome.status == DeliveryStatus.SENT,
            detail = outcome.detail ?: outcome.status.name,
        )
    }

    override fun preview(): String =
        renderer.renderHtml(EmailPreviewSample.SUBJECT, EmailPreviewSample.BODY, brandingResolver.platformOnly())

    private fun settings(): PlatformEmailSettings = repository.find() ?: PlatformEmailSettings.default()

    private fun audit(action: String, saved: PlatformEmailSettings, detail: Map<String, Any?> = emptyMap()) {
        auditor.record(
            action = action,
            entityType = "PlatformEmailSettings",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = detail,
        )
    }

    private fun PlatformEmailSettings.toView(): PlatformEmailSettingsView {
        val overrides = subjectRepo.platformSubjects()
        return PlatformEmailSettingsView(
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpUsername = smtpUsername,
            smtpPasswordSet = smtpPasswordSet,
            smtpAuth = smtpAuth,
            smtpStartTls = smtpStartTls,
            smtpConfigured = smtpConfigured,
            fromAddress = fromAddress,
            fromName = fromName,
            logoSet = branding.logoSet,
            logoUrl = brandingResolver.logoUrl(this),
            accentColor = branding.accentColor,
            footerText = branding.footerText,
            signatureText = branding.signatureText,
            publicBaseUrl = publicBaseUrl,
            subjects = EmailSubjectResolver.DEFAULT_SUBJECTS.map { (trigger, fallback) ->
                EmailSubjectView(
                    trigger = trigger,
                    subject = overrides[trigger],
                    inheritedSubject = fallback,
                )
            },
        )
    }

    internal companion object {
        /** Satu key global; unggah ulang menimpa. Tak terprefiks tenant — logo ini milik platform. */
        const val LOGO_KEY = "platform/email/logo"

        /**
         * Logo email wajib mungil: ia diunduh ulang tiap kali surat dibuka, dan klien email
         * tak mengenal `srcset` maupun lazy-loading untuk meringankannya.
         */
        const val MAX_LOGO_BYTES = 2 * 1024 * 1024
        private const val MAX_SUBJECT = 200

        fun validateLogo(contentType: String, bytes: ByteArray) {
            if (!contentType.startsWith("image/")) throw ValidationException("Logo email harus berupa berkas gambar")
            if (bytes.isEmpty()) throw ValidationException("Berkas logo email kosong")
            if (bytes.size > MAX_LOGO_BYTES) {
                throw ValidationException("Logo email maksimal ${MAX_LOGO_BYTES / (1024 * 1024)} MB")
            }
        }

        /**
         * Rapikan peta subjek dari form: dipangkas, yang kosong DIBUANG (bukan disimpan sebagai
         * string kosong) supaya mengosongkan kolom benar-benar berarti "kembali ke bawaan".
         */
        fun sanitizeSubjects(input: Map<NotificationTrigger, String>): Map<NotificationTrigger, String> =
            input.mapNotNull { (trigger, value) ->
                val subject = value.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (subject.length > MAX_SUBJECT) {
                    throw ValidationException("Subjek email maksimal $MAX_SUBJECT karakter")
                }
                trigger to subject
            }.toMap()
    }
}
