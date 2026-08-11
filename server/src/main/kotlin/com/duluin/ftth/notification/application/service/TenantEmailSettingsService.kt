package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.EmailSubjectView
import com.duluin.ftth.notification.application.port.inbound.EmailTestResultView
import com.duluin.ftth.notification.application.port.inbound.ManageTenantEmailSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.TenantEmailSettingsView
import com.duluin.ftth.notification.application.port.inbound.UpdateTenantEmailSettingsCommand
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.application.port.outbound.EmailSubjectRepository
import com.duluin.ftth.notification.application.port.outbound.TenantEmailSettingsRepository
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.EmailBranding
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import com.duluin.ftth.notification.domain.model.TenantEmailSettings
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Sisi tenant setelan email: timpaan identitas & tampilan di atas bawaan platform.
 *
 * Bentuknya cermin [PlatformEmailSettingsService] dengan dua perbedaan yang disengaja.
 * Pertama, TAK ADA setelan SMTP di sini — relay itu milik platform (alasannya di KDoc
 * `EmailDispatcher`). Kedua, tiap view membawa serta nilai WARISANNYA, karena di sisi tenant
 * kolom kosong bukan berarti "tak ada" melainkan "ikut platform", dan layar yang tak
 * menunjukkan apa yang diwarisi memaksa operator menebak isi email yang akan terkirim.
 */
@Service
@Transactional(readOnly = true)
@Suppress("LongParameterList")
class TenantEmailSettingsService(
    private val repository: TenantEmailSettingsRepository,
    private val subjectRepo: EmailSubjectRepository,
    private val brandingResolver: EmailBrandingResolver,
    private val renderer: EmailRenderer,
    private val dispatcher: EmailDispatcher,
    private val storage: ObjectStorage,
    private val auditor: AuditRecorder,
    private val tenants: TenantApi,
) : ManageTenantEmailSettingsUseCase {

    override fun get(): TenantEmailSettingsView = settings().toView()

    @Transactional
    override fun update(command: UpdateTenantEmailSettingsCommand): TenantEmailSettingsView {
        val settings = settings()
        settings.update(
            fromAddress = command.fromAddress,
            fromName = command.fromName,
            branding = EmailBranding.of(command.accentColor, command.footerText, command.signatureText),
        )
        val saved = repository.save(settings)
        subjectRepo.replaceTenant(PlatformEmailSettingsService.sanitizeSubjects(command.subjects))
        // Alamat pengirim ikut dicatat: ia yang menentukan atas nama siapa surat berangkat, dan
        // itulah satu-satunya setelan di layar ini yang bisa membuat email tenant ditolak relay.
        audit("notification.email.settings.updated", saved, mapOf("fromAddress" to saved.fromAddress))
        return saved.toView()
    }

    @Transactional
    override fun uploadLogo(contentType: String, bytes: ByteArray): TenantEmailSettingsView {
        PlatformEmailSettingsService.validateLogo(contentType, bytes)
        val settings = settings()
        val key = logoStorageKey(settings.tenantId)
        storage.put(key, contentType, bytes)
        settings.attachLogo(key, contentType)
        val saved = repository.save(settings)
        audit("notification.email.logo.uploaded", saved)
        return saved.toView()
    }

    @Transactional
    override fun deleteLogo(): TenantEmailSettingsView {
        val settings = settings()
        settings.branding.logoStorageKey?.let { storage.delete(it) }
        settings.clearLogo()
        val saved = repository.save(settings)
        audit("notification.email.logo.deleted", saved)
        return saved.toView()
    }

    /**
     * Hanya logo MILIK tenant. Null saat tenant tak menimpa — bukan diam-diam dialihkan ke logo
     * platform, karena penyaji publiknya perlu tahu bedanya untuk memilih 404 atau meneruskan.
     */
    override fun getLogo(): StoredObject? {
        val key = repository.find()?.branding?.logoStorageKey?.takeIf { it.isNotBlank() } ?: return null
        return storage.get(key)
    }

    override fun sendTest(to: String): EmailTestResultView {
        val recipient = PlatformEmailSettings.validateEmail(to, "Alamat tujuan uji")
            ?: throw ValidationException("Alamat tujuan uji wajib diisi")
        val outcome = dispatcher.send(
            renderer.render(
                to = recipient,
                subject = EmailPreviewSample.TEST_SUBJECT,
                body = EmailPreviewSample.TEST_BODY,
                identity = brandingResolver.forCurrentTenant(),
            ),
        )
        return EmailTestResultView(
            delivered = outcome.status == DeliveryStatus.SENT,
            detail = outcome.detail ?: outcome.status.name,
        )
    }

    override fun preview(): String = renderer.renderHtml(
        EmailPreviewSample.SUBJECT,
        EmailPreviewSample.BODY,
        brandingResolver.forCurrentTenant(),
    )

    private fun settings(): TenantEmailSettings =
        repository.find() ?: TenantEmailSettings.defaultFor(TenantContext.tenantId())

    private fun audit(action: String, saved: TenantEmailSettings, detail: Map<String, Any?> = emptyMap()) {
        auditor.record(
            action = action,
            entityType = "TenantEmailSettings",
            entityId = saved.id,
            tenantId = saved.tenantId,
            detail = detail,
        )
    }

    private fun TenantEmailSettings.toView(): TenantEmailSettingsView {
        val platform = brandingResolver.platformSettings()
        val platformSubjects = subjectRepo.platformSubjects()
        val tenantSubjects = subjectRepo.tenantSubjects()
        return TenantEmailSettingsView(
            fromAddress = fromAddress,
            fromName = fromName,
            logoSet = branding.logoSet,
            accentColor = branding.accentColor,
            footerText = branding.footerText,
            signatureText = branding.signatureText,
            inheritedFromAddress = platform.fromAddress,
            // Rantai yang sama dengan EmailBrandingResolver, dikurangi timpaan tenant: yang
            // ingin ditunjukkan di sini justru "apa yang muncul kalau kolom ini dikosongkan".
            inheritedFromName = tenants.findById(tenantId)?.name ?: platform.fromName,
            effectiveLogoUrl = brandingResolver.logoUrl(platform, tenantId, branding.logoSet),
            inheritedAccentColor = platform.branding.accentColor,
            inheritedFooterText = platform.branding.footerText,
            inheritedSignatureText = platform.branding.signatureText,
            subjects = EmailSubjectResolver.DEFAULT_SUBJECTS.map { (trigger, fallback) ->
                EmailSubjectView(
                    trigger = trigger,
                    subject = tenantSubjects[trigger],
                    inheritedSubject = platformSubjects[trigger] ?: fallback,
                )
            },
        )
    }

    private companion object {
        /** Satu key per tenant; unggah ulang menimpa. Terprefiks tenant (pola bukti work-order). */
        fun logoStorageKey(tenantId: UUID) = "$tenantId/email/logo"
    }
}
