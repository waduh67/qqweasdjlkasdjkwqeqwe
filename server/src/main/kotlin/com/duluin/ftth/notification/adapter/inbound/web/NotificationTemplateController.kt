package com.duluin.ftth.notification.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.notification.application.port.inbound.DeleteTemplateResult
import com.duluin.ftth.notification.application.port.inbound.EditTemplateCommand
import com.duluin.ftth.notification.application.port.inbound.ManageNotificationTemplateUseCase
import com.duluin.ftth.notification.application.port.inbound.ReplaceAssignmentsCommand
import com.duluin.ftth.notification.application.port.inbound.SaveTemplateCommand
import com.duluin.ftth.notification.application.port.inbound.SyncTemplatesResult
import com.duluin.ftth.notification.application.port.inbound.TemplateCatalogView
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.TemplateCategory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Katalog template pesan WhatsApp tenant + pemetaan "pemicu mana memakai template mana".
 * Terpisah dari `/api/notifications/settings` karena isinya bukan kredensial: kartu template
 * di UI baru terbuka setelah gateway WhatsApp resmi aktif & kredensialnya tersimpan — prasyarat
 * itu ditegakkan use case (409 bila belum), bukan oleh izin.
 *
 * Endpoint tulis di sini BUKAN operasi lokal: tambah/ubah/hapus benar-benar memanggil API
 * penyedia (Meta Cloud / Mekari Qontak), jadi 409 dari sana muncul apa adanya ke operator.
 *
 * Setiap operasi tulis mengembalikan katalog utuh agar UI tak perlu GET susulan.
 */
@RestController
@RequestMapping("/api/notifications/templates")
@Tag(name = "Notification")
@SecurityRequirement(name = "bearer-jwt")
class NotificationTemplateController(
    private val useCase: ManageNotificationTemplateUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('notification.template.view')")
    @Operation(summary = "Katalog template WhatsApp & pemetaan pemicu")
    fun list(): TemplateCatalogView = useCase.list()

    @PostMapping
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Ajukan template baru ke penyedia WhatsApp")
    fun create(@Valid @RequestBody request: CreateTemplateRequest): TemplateCatalogView =
        useCase.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Ubah isi & kategori template (nama/bahasa terkunci di penyedia)")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: EditTemplateRequest): TemplateCatalogView =
        useCase.update(id, request.toCommand())

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Hapus template (ikut dihapus di penyedia bila penyedianya mendukung)")
    fun delete(@PathVariable id: UUID): DeleteTemplateResult = useCase.delete(id)

    @PutMapping("/assignments")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Setel template yang dipakai tiap pemicu otomatis")
    fun replaceAssignments(@Valid @RequestBody request: AssignmentsRequest): TemplateCatalogView =
        useCase.replaceAssignments(request.toCommand())

    @PostMapping("/sync")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Tarik daftar template UTILITY dari penyedia")
    fun sync(): SyncTemplatesResult = useCase.sync()
}

/**
 * Pengajuan template baru. Bahasa kosong = bawaan `id`. [bodyText] wajib memuat tepat satu
 * variabel `{{1}}` — divalidasi domain, bukan di sini, agar aturannya tunggal.
 */
data class CreateTemplateRequest(
    @field:NotBlank @field:Size(max = 128) val name: String?,
    @field:Size(max = 10) val language: String? = null,
    val category: String? = null,
    @field:NotBlank @field:Size(max = 1024) val bodyText: String? = null,
) {
    fun toCommand() = SaveTemplateCommand(
        name = name,
        language = language,
        category = parseCategory(category),
        bodyText = bodyText,
    )
}

/** Suntingan template: hanya isi & kategori — nama/bahasa tak bisa diubah di kedua penyedia. */
data class EditTemplateRequest(
    val category: String? = null,
    @field:NotBlank @field:Size(max = 1024) val bodyText: String? = null,
) {
    fun toCommand() = EditTemplateCommand(category = parseCategory(category), bodyText = bodyText)
}

/**
 * Kategori kosong = UTILITY, satu-satunya yang relevan untuk pesan transaksional ISP dan
 * satu-satunya yang ikut tersaring saat sync. Nilai asing jadi 400 lewat [ValidationException],
 * bukan 500 dari `valueOf`.
 */
private fun parseCategory(value: String?): TemplateCategory {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return TemplateCategory.UTILITY
    return runCatching { TemplateCategory.valueOf(trimmed.uppercase()) }.getOrElse {
        throw ValidationException(
            "Kategori template tak dikenal — pilih ${TemplateCategory.entries.joinToString(", ")}",
        )
    }
}

/**
 * Peta pemicu → id template yang menggantikan SELURUH pemetaan. Pemicu yang tak disebut
 * (atau bernilai null) berarti tanpa template → dikirim sebagai teks biasa, atau dilewati
 * bila penyedianya Mekari Qontak (API-nya hanya menerima template).
 */
data class AssignmentsRequest(
    val assignments: Map<NotificationTrigger, UUID?> = emptyMap(),
) {
    fun toCommand() = ReplaceAssignmentsCommand(
        assignments = assignments.mapNotNull { (trigger, id) -> id?.let { trigger to it } }.toMap(),
    )
}
