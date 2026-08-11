package com.duluin.ftth.notification.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.notification.application.port.inbound.EmailTestResultView
import com.duluin.ftth.notification.application.port.inbound.ManagePlatformEmailSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.PlatformEmailSettingsView
import com.duluin.ftth.notification.application.port.inbound.UpdatePlatformEmailSettingsCommand
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
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
 * Setelan email milik PLATFORM: sambungan SMTP, identitas pengirim bawaan, logo & tampilan
 * bawaan, serta baris subjek per pemicu. Semuanya berlaku sebagai bawaan yang diwarisi seluruh
 * tenant — tenant menimpanya lewat `/api/notifications/email-settings`.
 *
 * Password SMTP write-only: dikirim saat update, tak pernah dikembalikan; GET hanya menandakan
 * sudah terisi atau belum (pola `PivotMasterConfigController`).
 */
@RestController
@RequestMapping("/api/platform/email-settings")
@Tag(name = "Platform — Setelan email")
@SecurityRequirement(name = "bearer-jwt")
class PlatformEmailSettingsController(
    private val useCase: ManagePlatformEmailSettingsUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.email.view')")
    @Operation(summary = "Baca setelan SMTP, tampilan & subjek email platform")
    fun get(): PlatformEmailSettingsView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('platform.email.manage')")
    @Operation(summary = "Ubah SMTP, identitas pengirim, tampilan & subjek email platform")
    fun update(@Valid @RequestBody request: PlatformEmailSettingsRequest): PlatformEmailSettingsView =
        useCase.update(request.toCommand())

    @PostMapping("/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("@authz.can('platform.email.manage')")
    @Operation(summary = "Unggah/ganti logo bawaan email")
    fun uploadLogo(@RequestParam("file") file: MultipartFile): PlatformEmailSettingsView {
        val contentType = file.contentType ?: throw ValidationException("Tipe berkas logo tidak diketahui")
        return useCase.uploadLogo(contentType, file.bytes)
    }

    @DeleteMapping("/logo")
    @PreAuthorize("@authz.can('platform.email.manage')")
    @Operation(summary = "Hapus logo bawaan email")
    fun deleteLogo(): PlatformEmailSettingsView = useCase.deleteLogo()

    /**
     * Penyaji ter-gate untuk layar setelan. Berbeda dari `/api/public/email-logo` yang melayani
     * klien email tanpa auth: yang ini boleh dipakai sebelum URL publik disetel, sehingga
     * pratinjau di layar tetap menampilkan logo di deploy yang belum punya alamat publik.
     */
    @GetMapping("/logo")
    @PreAuthorize("@authz.can('platform.email.view')")
    @Operation(summary = "Sajikan logo bawaan email (byte, ter-gate)")
    fun logo(): ResponseEntity<ByteArray> {
        val image = useCase.getLogo() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType)).body(image.bytes)
    }

    /**
     * Butuh izin `manage`, bukan `view`: memanggilnya benar-benar mengirim surat lewat relay
     * platform, jadi setara menyetel — bukan sekadar membaca setelan yang sudah ada.
     */
    @PostMapping("/test")
    @PreAuthorize("@authz.can('platform.email.manage')")
    @Operation(summary = "Kirim email uji memakai setelan yang tersimpan")
    fun sendTest(@Valid @RequestBody request: EmailTestRequest): EmailTestResultView =
        useCase.sendTest(request.to)

    @GetMapping("/preview", produces = [MediaType.TEXT_HTML_VALUE])
    @PreAuthorize("@authz.can('platform.email.view')")
    @Operation(summary = "HTML pratinjau bungkus email platform")
    fun preview(): String = useCase.preview()
}

/**
 * [smtpPassword] null/kosong = biarkan yang tersimpan (menyunting footer tak boleh menghapus
 * kredensial SMTP). [subjects] mengganti SELURUH timpaan subjek: pemicu yang tak disebut
 * kembali memakai subjek bawaan di kode.
 */
@Suppress("LongParameterList")
data class PlatformEmailSettingsRequest(
    @field:Size(max = 255) val smtpHost: String? = null,
    @field:Min(1) @field:Max(65535) val smtpPort: Int = 587,
    @field:Size(max = 255) val smtpUsername: String? = null,
    @field:Size(max = 512) val smtpPassword: String? = null,
    val smtpAuth: Boolean = true,
    val smtpStartTls: Boolean = true,
    @field:Size(max = 254) val fromAddress: String? = null,
    @field:Size(max = 100) val fromName: String? = null,
    @field:Size(max = 9) val accentColor: String? = null,
    @field:Size(max = 500) val footerText: String? = null,
    @field:Size(max = 200) val signatureText: String? = null,
    @field:Size(max = 300) val publicBaseUrl: String? = null,
    val subjects: Map<NotificationTrigger, String> = emptyMap(),
) {
    fun toCommand() = UpdatePlatformEmailSettingsCommand(
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpUsername = smtpUsername,
        smtpPassword = smtpPassword,
        smtpAuth = smtpAuth,
        smtpStartTls = smtpStartTls,
        fromAddress = fromAddress,
        fromName = fromName,
        accentColor = accentColor,
        footerText = footerText,
        signatureText = signatureText,
        publicBaseUrl = publicBaseUrl,
        subjects = subjects,
    )
}

/** Alamat tujuan email uji; dipakai sisi platform maupun sisi tenant. */
data class EmailTestRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val to: String,
)
