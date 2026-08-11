package com.duluin.ftth.notification.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.notification.application.port.inbound.EmailTestResultView
import com.duluin.ftth.notification.application.port.inbound.ManageTenantEmailSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.TenantEmailSettingsView
import com.duluin.ftth.notification.application.port.inbound.UpdateTenantEmailSettingsCommand
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Timpaan setelan email milik tenant: identitas pengirim, logo & tampilan, serta baris subjek
 * per pemicu. Kolom kosong berarti MEWARISI bawaan platform, bukan mengosongkan — karena itu
 * tiap respons juga membawa nilai warisannya untuk ditampilkan sebagai placeholder.
 *
 * Sambungan SMTP tak ada di sini: relay-nya milik platform. Dijaga izin setelan notifikasi
 * yang sudah ada (`notification.settings.*`) — ini masih setelan notifikasi tenant, bukan
 * permukaan wewenang baru.
 */
@RestController
@RequestMapping("/api/notifications/email-settings")
@Tag(name = "Notification")
@SecurityRequirement(name = "bearer-jwt")
class TenantEmailSettingsController(
    private val useCase: ManageTenantEmailSettingsUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('notification.settings.view')")
    @Operation(summary = "Setelan identitas & tampilan email tenant beserta warisan platformnya")
    fun get(): TenantEmailSettingsView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('notification.settings.manage')")
    @Operation(summary = "Ubah identitas pengirim, tampilan & subjek email tenant")
    fun update(@Valid @RequestBody request: TenantEmailSettingsRequest): TenantEmailSettingsView =
        useCase.update(request.toCommand())

    @PostMapping("/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("@authz.can('notification.settings.manage')")
    @Operation(summary = "Unggah/ganti logo email tenant (menimpa logo platform)")
    fun uploadLogo(@RequestParam("file") file: MultipartFile): TenantEmailSettingsView {
        val contentType = file.contentType ?: throw ValidationException("Tipe berkas logo tidak diketahui")
        return useCase.uploadLogo(contentType, file.bytes)
    }

    @DeleteMapping("/logo")
    @PreAuthorize("@authz.can('notification.settings.manage')")
    @Operation(summary = "Kembalikan logo email ke bawaan platform")
    fun deleteLogo(): TenantEmailSettingsView = useCase.deleteLogo()

    @GetMapping("/logo")
    @PreAuthorize("@authz.can('notification.settings.view')")
    @Operation(summary = "Sajikan logo email tenant (byte, ter-gate); 404 bila memakai logo platform")
    fun logo(): ResponseEntity<ByteArray> {
        val image = useCase.getLogo() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType)).body(image.bytes)
    }

    /** Butuh `manage`: benar-benar mengirim surat lewat relay platform, bukan sekadar membaca. */
    @PostMapping("/test")
    @PreAuthorize("@authz.can('notification.settings.manage')")
    @Operation(summary = "Kirim email uji memakai setelan tenant yang tersimpan")
    fun sendTest(@Valid @RequestBody request: EmailTestRequest): EmailTestResultView =
        useCase.sendTest(request.to)

    @GetMapping("/preview", produces = [MediaType.TEXT_HTML_VALUE])
    @PreAuthorize("@authz.can('notification.settings.view')")
    @Operation(summary = "HTML pratinjau bungkus email tenant (platform sudah ditimpa)")
    fun preview(): String = useCase.preview()
}

/**
 * Semua field opsional: null/kosong = hapus timpaan dan warisi platform lagi. Logo diunggah
 * terpisah lewat endpoint multipart.
 */
data class TenantEmailSettingsRequest(
    @field:Size(max = 254) val fromAddress: String? = null,
    @field:Size(max = 100) val fromName: String? = null,
    @field:Size(max = 9) val accentColor: String? = null,
    @field:Size(max = 500) val footerText: String? = null,
    @field:Size(max = 200) val signatureText: String? = null,
    val subjects: Map<NotificationTrigger, String> = emptyMap(),
) {
    fun toCommand() = UpdateTenantEmailSettingsCommand(
        fromAddress = fromAddress,
        fromName = fromName,
        accentColor = accentColor,
        footerText = footerText,
        signatureText = signatureText,
        subjects = subjects,
    )
}
