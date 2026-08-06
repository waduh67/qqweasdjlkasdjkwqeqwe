package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ManagePivotMasterConfigUseCase
import com.duluin.ftth.billing.application.port.inbound.PivotMasterConfigView
import com.duluin.ftth.billing.application.port.inbound.UpdatePivotMasterConfigCommand
import com.duluin.ftth.billing.domain.model.PivotFeeType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Setelan akun MASTER Pivot untuk super-admin platform: kredensial (merchant id/secret/callback key),
 * toggle sandbox, fee platform per transaksi, dan rekening payout platform. Dijaga izin
 * `platform.billing.*` (platform admin otomatis lolos). Rahasia write-only — respons hanya penanda
 * `*Set`, tak pernah membocorkan nilai. URL callback didaftarkan per PRODUK di dashboard Pivot
 * (akun master, satu URL per produk) di bawah `{origin}/api/platform/pivot/callbacks/{produk}` — verifikasi
 * header `X-API-Key` master. Daftar lengkapnya tampil siap-salin di setelan Billing Langganan Platform.
 */
@RestController
@RequestMapping("/api/platform/pivot-config")
@Tag(name = "Platform — Setelan master Pivot")
@SecurityRequirement(name = "bearer-jwt")
class PivotMasterConfigController(
    private val useCase: ManagePivotMasterConfigUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.billing.view')")
    @Operation(summary = "Baca setelan master Pivot (tanpa membocorkan rahasia)")
    fun get(): PivotMasterConfigView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('platform.billing.manage')")
    @Operation(summary = "Ubah kredensial master, fee per transaksi & rekening payout platform")
    fun update(@Valid @RequestBody request: PivotMasterConfigRequest): PivotMasterConfigView =
        useCase.update(request.toCommand())
}

/**
 * Rahasia (merchantId/secret/callbackApiKey) null/kosong = biarkan apa adanya (tak menghapus saat
 * menyunting fee/payout). Fee & rekening payout non-rahasia → selalu diganti.
 */
data class PivotMasterConfigRequest(
    val enabled: Boolean = false,
    val sandbox: Boolean = false,
    @field:Size(max = 512) val merchantId: String? = null,
    @field:Size(max = 512) val merchantSecret: String? = null,
    @field:Size(max = 512) val callbackApiKey: String? = null,
    @field:Min(0) val platformFeeMinor: Long = 0,
    @field:NotNull val platformFeeType: PivotFeeType = PivotFeeType.FIXED,
    @field:Size(max = 40) val payoutChannelCode: String? = null,
    @field:Size(max = 60) val payoutAccountNumber: String? = null,
) {
    fun toCommand() = UpdatePivotMasterConfigCommand(
        enabled = enabled,
        sandbox = sandbox,
        merchantId = merchantId,
        merchantSecret = merchantSecret,
        callbackApiKey = callbackApiKey,
        platformFeeMinor = platformFeeMinor,
        platformFeeType = platformFeeType,
        payoutChannelCode = payoutChannelCode,
        payoutAccountNumber = payoutAccountNumber,
    )
}
