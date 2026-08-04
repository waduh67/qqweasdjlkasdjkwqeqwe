package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.PaymentGatewaySettingsView
import com.duluin.ftth.billing.application.port.inbound.PaywuzMethodView
import com.duluin.ftth.billing.application.port.inbound.UpdatePaymentGatewaySettingsCommand
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.common.domain.error.ValidationException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Setelan payment gateway tenant: penyedia (Xendit/Paywuz/Pivot/Manual) + mode (BYO/PLATFORM)
 * + kredensial bawa-sendiri. Kredensial hanya boleh DITULIS (write-only): dikirim saat update,
 * tak pernah dikembalikan — GET hanya menandakan sudah terisi/belum.
 */
@RestController
@RequestMapping("/api/billing/gateway-settings")
@Tag(name = "Billing — setelan payment gateway")
@SecurityRequirement(name = "bearer-jwt")
class PaymentGatewaySettingsController(
    private val useCase: ManagePaymentGatewaySettingsUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('billing.gateway.view')")
    @Operation(summary = "Setelan payment gateway tenant")
    fun get(): PaymentGatewaySettingsView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Ubah penyedia, mode, & kredensial payment gateway")
    fun update(@Valid @RequestBody request: PaymentGatewaySettingsRequest): PaymentGatewaySettingsView =
        useCase.update(request.toCommand())

    @GetMapping("/paywuz-methods")
    @PreAuthorize("@authz.can('billing.gateway.view')")
    @Operation(summary = "Daftar metode pembayaran proyek Paywuz tenant (untuk pilihan di UI)")
    fun paywuzMethods(): List<PaywuzMethodView> = useCase.listPaywuzMethods()

    @PostMapping("/qris", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Unggah/ganti gambar QRIS pembayaran manual")
    fun uploadQris(@RequestParam("file") file: MultipartFile): PaymentGatewaySettingsView {
        val contentType = file.contentType ?: throw ValidationException("Tipe berkas QRIS tidak diketahui")
        return useCase.uploadQrisImage(contentType, file.bytes)
    }

    @DeleteMapping("/qris")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Hapus gambar QRIS pembayaran manual")
    fun deleteQris(): PaymentGatewaySettingsView = useCase.deleteQrisImage()

    @GetMapping("/qris")
    @PreAuthorize("@authz.canAny('billing.gateway.view','billing.invoice.view')")
    @Operation(summary = "Sajikan gambar QRIS pembayaran manual (byte, ter-gate)")
    fun qrisImage(): ResponseEntity<ByteArray> {
        val image = useCase.getQrisImage() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType)).body(image.bytes)
    }
}

/**
 * Kredensial ([apiKey]/[secretKey]/[webhookToken]) opsional: kosong/absen = biarkan yang
 * tersimpan. Batas panjang mengikuti validasi domain agar pesan galat konsisten di klien.
 */
data class PaymentGatewaySettingsRequest(
    @field:NotNull val provider: PaymentProvider,
    @field:NotNull val mode: GatewayMode,
    @field:NotNull val enabled: Boolean,
    @field:Size(max = 512) val apiKey: String? = null,
    @field:Size(max = 512) val secretKey: String? = null,
    @field:Size(max = 512) val webhookToken: String? = null,
    @field:Size(max = 64) val paymentMethod: String? = null,
    // Pembayaran manual (transfer/QRIS) — non-rahasia. Gambar QRIS diunggah terpisah.
    val manualTransferEnabled: Boolean = false,
    @field:Size(max = 120) val bankName: String? = null,
    @field:Size(max = 60) val accountNumber: String? = null,
    @field:Size(max = 160) val accountHolder: String? = null,
    val manualQrisEnabled: Boolean = false,
) {
    fun toCommand() = UpdatePaymentGatewaySettingsCommand(
        provider = provider,
        mode = mode,
        enabled = enabled,
        apiKey = apiKey,
        secretKey = secretKey,
        webhookToken = webhookToken,
        paymentMethod = paymentMethod,
        manualTransferEnabled = manualTransferEnabled,
        bankName = bankName,
        accountNumber = accountNumber,
        accountHolder = accountHolder,
        manualQrisEnabled = manualQrisEnabled,
    )
}
