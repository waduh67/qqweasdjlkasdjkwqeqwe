package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.DispatchPayoutCommand
import com.duluin.ftth.billing.application.port.inbound.ManageTenantPayoutUseCase
import com.duluin.ftth.billing.application.port.inbound.PivotBalanceView
import com.duluin.ftth.billing.application.port.inbound.TenantPayoutView
import com.duluin.ftth.billing.application.port.inbound.WithdrawCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Penyaluran dana tenant (bagian dari `/payment-gateway`). PAYOUT untuk akun NON_KYC (dana di
 * master platform → rekening tenant tervalidasi); WITHDRAWAL untuk akun KYC (saldo sub-account
 * tenant → ditarik sendiri). Saldo dibaca langsung dari Pivot; nominal eksplisit.
 */
@RestController
@RequestMapping("/api/billing/pivot-account")
@Tag(name = "Billing — penyaluran dana Pivot")
@SecurityRequirement(name = "bearer-jwt")
class TenantPayoutController(
    private val useCase: ManageTenantPayoutUseCase,
) {
    @GetMapping("/balance")
    @PreAuthorize("@authz.can('billing.gateway.view')")
    @Operation(summary = "Saldo Pivot relevan tenant (master untuk NON_KYC, sub-account untuk KYC)")
    fun balance(): PivotBalanceView = useCase.balance()

    @GetMapping("/payouts")
    @PreAuthorize("@authz.can('billing.gateway.view')")
    @Operation(summary = "Riwayat penyaluran dana tenant")
    fun history(): List<TenantPayoutView> = useCase.history()

    @PostMapping("/payouts")
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Salurkan dana ke rekening beneficiary (inquiry + wajib cek saldo)")
    fun payout(@Valid @RequestBody request: DispatchPayoutRequest): TenantPayoutView =
        useCase.dispatchPayout(
            DispatchPayoutCommand(request.channelCode, request.accountNumber, request.amountMinor, request.description),
        )

    @PostMapping("/withdrawals")
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Tarik saldo sub-account KYC tenant ke rekening payout tersimpan")
    fun withdraw(@Valid @RequestBody request: WithdrawRequest): TenantPayoutView =
        useCase.withdraw(WithdrawCommand(request.amountMinor, request.description))
}

/** Perintah payout beneficiary: bank + nomor rekening tujuan + nominal (rupiah utuh > 0). */
data class DispatchPayoutRequest(
    @field:NotBlank(message = "Channel bank wajib diisi") @field:Size(max = 40)
    val channelCode: String,
    @field:NotBlank(message = "Nomor rekening wajib diisi") @field:Size(max = 60)
    val accountNumber: String,
    @field:Positive(message = "Nominal harus lebih dari 0")
    val amountMinor: Long,
    @field:Size(max = 200)
    val description: String? = null,
)

/** Perintah penarikan saldo KYC: nominal (rupiah utuh > 0) + catatan opsional. */
data class WithdrawRequest(
    @field:Positive(message = "Nominal harus lebih dari 0")
    val amountMinor: Long,
    @field:Size(max = 200)
    val description: String? = null,
)
