package com.duluin.ftth.notification.adapter.inbound.web

import com.duluin.ftth.notification.application.port.inbound.ManageNotificationTemplateUseCase
import com.duluin.ftth.notification.application.port.inbound.ReplaceAssignmentsCommand
import com.duluin.ftth.notification.application.port.inbound.SaveTemplateCommand
import com.duluin.ftth.notification.application.port.inbound.SyncTemplatesResult
import com.duluin.ftth.notification.application.port.inbound.TemplateCatalogView
import com.duluin.ftth.notification.domain.model.NotificationTrigger
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
 * di UI baru terbuka setelah gateway Meta Cloud aktif & kredensialnya tersimpan — prasyarat
 * itu ditegakkan use case (409 bila belum), bukan oleh izin.
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
    @Operation(summary = "Tambah template manual")
    fun create(@Valid @RequestBody request: TemplateRequest): TemplateCatalogView =
        useCase.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Ubah nama/bahasa template")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: TemplateRequest): TemplateCatalogView =
        useCase.update(id, request.toCommand())

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Hapus template dari katalog (tidak menghapusnya di Meta)")
    fun delete(@PathVariable id: UUID): TemplateCatalogView = useCase.delete(id)

    @PutMapping("/assignments")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Setel template yang dipakai tiap pemicu otomatis")
    fun replaceAssignments(@Valid @RequestBody request: AssignmentsRequest): TemplateCatalogView =
        useCase.replaceAssignments(request.toCommand())

    @PostMapping("/sync")
    @PreAuthorize("@authz.can('notification.template.manage')")
    @Operation(summary = "Tarik daftar template UTILITY dari Meta")
    fun sync(): SyncTemplatesResult = useCase.sync()
}

/** Nama & bahasa template sebagaimana terdaftar di Meta. Bahasa kosong = bawaan `id`. */
data class TemplateRequest(
    @field:NotBlank @field:Size(max = 128) val name: String?,
    @field:Size(max = 10) val language: String? = null,
) {
    fun toCommand() = SaveTemplateCommand(name = name, language = language)
}

/**
 * Peta pemicu → id template yang menggantikan SELURUH pemetaan. Pemicu yang tak disebut
 * (atau bernilai null) berarti tanpa template → dikirim sebagai teks biasa.
 */
data class AssignmentsRequest(
    val assignments: Map<NotificationTrigger, UUID?> = emptyMap(),
) {
    fun toCommand() = ReplaceAssignmentsCommand(
        assignments = assignments.mapNotNull { (trigger, id) -> id?.let { trigger to it } }.toMap(),
    )
}
