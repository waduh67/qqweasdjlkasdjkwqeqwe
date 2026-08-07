package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ManageTenantPivotAccountUseCase
import com.duluin.ftth.billing.application.port.inbound.SaveTenantPivotProfileCommand
import com.duluin.ftth.billing.application.port.inbound.SetPivotPayoutAccountCommand
import com.duluin.ftth.billing.application.port.inbound.TenantPivotAccountView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Manajemen sub-account Pivot tenant (`/payment-gateway`). Menggantikan form kredensial BYO: tenant
 * memantau status sub-account, mengajukan upgrade KYC, & menyetel rekening payout. Setelan metode
 * aktif + pembayaran manual tetap di `/api/billing/gateway-settings`.
 */
@RestController
@RequestMapping("/api/billing/pivot-account")
@Tag(name = "Billing — sub-account Pivot")
@SecurityRequirement(name = "bearer-jwt")
class TenantPivotAccountController(
    private val useCase: ManageTenantPivotAccountUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('billing.gateway.view')")
    @Operation(summary = "Status sub-account Pivot tenant")
    fun get(): TenantPivotAccountView = useCase.get()

    @PostMapping("/provision")
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Provisi sub-account NON_KYC bila belum ada (tenant lama pra-fitur)")
    fun provision(): TenantPivotAccountView = useCase.provision()

    @PostMapping("/refresh")
    @PreAuthorize("@authz.can('billing.gateway.view')")
    @Operation(summary = "Tarik status terbaru sub-account dari Pivot")
    fun refresh(): TenantPivotAccountView = useCase.refreshStatus()

    @PostMapping("/request-kyc")
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Ajukan upgrade ke KYC (transaksi atas nama tenant sendiri)")
    fun requestKyc(): TenantPivotAccountView = useCase.requestKyc()

    @PutMapping("/profile")
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Simpan profil bisnis sub-account (identitas + PIC + alamat), wajib sebelum provisioning")
    fun saveProfile(@Valid @RequestBody request: PivotProfileRequest): TenantPivotAccountView =
        useCase.saveProfile(request.toCommand())

    @PostMapping("/payout-account")
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Setel rekening payout tenant (divalidasi via inquiry Pivot)")
    fun setPayoutAccount(@Valid @RequestBody request: PivotPayoutAccountRequest): TenantPivotAccountView =
        useCase.setPayoutAccount(SetPivotPayoutAccountCommand(request.channelCode, request.accountNumber))
}

/**
 * Profil bisnis sub-account yang diisi tenant (non-rahasia). `legalName` opsional (fallback nama
 * tenant). Field wajib divalidasi di service (`profileComplete`) sebelum request create ke Pivot.
 */
data class PivotProfileRequest(
    @field:Size(max = 200) val legalName: String? = null,
    @field:Size(max = 160) val merchantEmail: String? = null,
    @field:Size(max = 40) val merchantPhone: String? = null,
    @field:Size(max = 160) val picName: String? = null,
    @field:Size(max = 160) val picEmail: String? = null,
    @field:Size(max = 40) val picPhone: String? = null,
    @field:Size(max = 500) val address: String? = null,
    @field:Size(max = 40) val channelCode: String? = null,
    @field:Size(max = 60) val accountNumber: String? = null,
) {
    fun toCommand() = SaveTenantPivotProfileCommand(
        legalName = legalName,
        merchantEmail = merchantEmail,
        merchantPhone = merchantPhone,
        picName = picName,
        picEmail = picEmail,
        picPhone = picPhone,
        address = address,
        channelCode = channelCode,
        accountNumber = accountNumber,
    )
}

/** Rekening payout tenant. Nama pemilik diisi otomatis hasil `POST /v1/inquiry-account`, bukan input. */
data class PivotPayoutAccountRequest(
    @field:NotBlank(message = "Channel bank wajib diisi") @field:Size(max = 40)
    val channelCode: String,
    @field:NotBlank(message = "Nomor rekening wajib diisi") @field:Size(max = 60)
    val accountNumber: String,
)
