package com.duluin.ftth.notification.adapter.inbound.web

import com.duluin.ftth.notification.application.port.inbound.ManageNotificationSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.NotificationSettingsView
import com.duluin.ftth.notification.application.port.inbound.UpdateNotificationSettingsCommand
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Setelan notifikasi tenant: gateway WhatsApp bawa-sendiri (LOG/HTTP generik/Meta Cloud)
 * plus saklar on/off tiap pemicu otomatis. Token gateway hanya boleh DITULIS (write-only):
 * dikirim saat update, tak pernah dikembalikan — GET hanya menandakan sudah terisi/belum.
 */
@RestController
@RequestMapping("/api/notifications/settings")
@Tag(name = "Notification")
@SecurityRequirement(name = "bearer-jwt")
class NotificationSettingsController(
    private val useCase: ManageNotificationSettingsUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('notification.settings.view')")
    @Operation(summary = "Setelan gateway & pemicu notifikasi tenant")
    fun get(): NotificationSettingsView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('notification.settings.manage')")
    @Operation(summary = "Ubah gateway WA & saklar pemicu notifikasi")
    fun update(@Valid @RequestBody request: NotificationSettingsRequest): NotificationSettingsView =
        useCase.update(request.toCommand())
}

/**
 * Token ([httpToken]/[metaAccessToken]) opsional: kosong/absen = biarkan yang tersimpan.
 * Batas panjang mengikuti validasi domain agar pesan galat konsisten di sisi klien.
 */
data class NotificationSettingsRequest(
    @field:NotNull val provider: WhatsAppProvider,
    @field:NotNull val gatewayEnabled: Boolean,
    @field:Size(max = 500) val httpEndpointUrl: String? = null,
    @field:Size(max = 255) val httpToken: String? = null,
    @field:Size(max = 50) val httpPhoneField: String? = null,
    @field:Size(max = 50) val httpMessageField: String? = null,
    @field:Size(max = 64) val metaPhoneNumberId: String? = null,
    @field:Size(max = 1024) val metaAccessToken: String? = null,
    @field:Size(max = 128) val metaTemplateName: String? = null,
    @field:Size(max = 10) val metaTemplateLang: String? = null,
    @field:NotNull val notifyOnSubscriptionLifecycle: Boolean,
    @field:NotNull val notifyOnInvoiceReminder: Boolean,
    @field:NotNull val notifyOnWorkOrderSchedule: Boolean,
    @field:NotNull val notifyOnIncidentOpen: Boolean,
) {
    fun toCommand() = UpdateNotificationSettingsCommand(
        provider = provider,
        gatewayEnabled = gatewayEnabled,
        httpEndpointUrl = httpEndpointUrl,
        httpToken = httpToken,
        httpPhoneField = httpPhoneField,
        httpMessageField = httpMessageField,
        metaPhoneNumberId = metaPhoneNumberId,
        metaAccessToken = metaAccessToken,
        metaTemplateName = metaTemplateName,
        metaTemplateLang = metaTemplateLang,
        notifyOnSubscriptionLifecycle = notifyOnSubscriptionLifecycle,
        notifyOnInvoiceReminder = notifyOnInvoiceReminder,
        notifyOnWorkOrderSchedule = notifyOnWorkOrderSchedule,
        notifyOnIncidentOpen = notifyOnIncidentOpen,
    )
}
