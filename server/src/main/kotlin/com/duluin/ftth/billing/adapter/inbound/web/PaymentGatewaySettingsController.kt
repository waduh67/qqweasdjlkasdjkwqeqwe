package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.PaymentGatewaySettingsView
import com.duluin.ftth.billing.application.port.inbound.UpdatePaymentGatewaySettingsCommand
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
 * Setelan penagihan tenant: Pivot platform, Tripay BYOK, atau pembayaran manual. Key Tripay
 * diterima hanya saat PUT dan tidak pernah dikirim kembali lewat GET.
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
    @Operation(summary = "Ubah metode aktif, Tripay BYOK, dan konfigurasi pembayaran manual")
    fun update(@Valid @RequestBody request: PaymentGatewaySettingsRequest): PaymentGatewaySettingsView =
        useCase.update(request.toCommand())

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
 * Metode aktif + konfigurasi pembayaran manual. API/private key Tripay write-only; respons GET
 * hanya memuat penanda apakah masing-masing sudah tersimpan.
 */
data class PaymentGatewaySettingsRequest(
    @field:NotNull val provider: PaymentProvider,
    @field:NotNull val enabled: Boolean,
    val manualTransferEnabled: Boolean = false,
    @field:Size(max = 120) val bankName: String? = null,
    @field:Size(max = 60) val accountNumber: String? = null,
    @field:Size(max = 160) val accountHolder: String? = null,
    val manualQrisEnabled: Boolean = false,
    @field:Size(max = 80) val tripayMerchantCode: String? = null,
    @field:Size(max = 512) val tripayApiKey: String? = null,
    @field:Size(max = 512) val tripayPrivateKey: String? = null,
    val tripaySandbox: Boolean = true,
) {
    fun toCommand() = UpdatePaymentGatewaySettingsCommand(
        provider = provider,
        enabled = enabled,
        manualTransferEnabled = manualTransferEnabled,
        bankName = bankName,
        accountNumber = accountNumber,
        accountHolder = accountHolder,
        manualQrisEnabled = manualQrisEnabled,
        tripayMerchantCode = tripayMerchantCode,
        tripayApiKey = tripayApiKey,
        tripayPrivateKey = tripayPrivateKey,
        tripaySandbox = tripaySandbox,
    )
}
