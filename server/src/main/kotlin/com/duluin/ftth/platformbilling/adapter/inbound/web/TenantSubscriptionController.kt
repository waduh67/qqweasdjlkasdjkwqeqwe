package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.platformbilling.application.port.inbound.ConfigureSubscriptionCommand
import com.duluin.ftth.platformbilling.application.port.inbound.GrantFreeMonthsCommand
import com.duluin.ftth.platformbilling.application.port.inbound.ManageTenantSubscriptionUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.ManualPaymentCommand
import com.duluin.ftth.platformbilling.application.port.inbound.SubscriptionInvoiceView
import com.duluin.ftth.platformbilling.application.port.inbound.TenantSubscriptionDetailView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

/**
 * Pengelolaan langganan tenant ke aplikasi (SaaS) untuk super-admin platform. Dijaga izin
 * `platform.subscription.*`. Charge/webhook ada di controller terpisah.
 */
@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/subscription")
@Tag(name = "Platform — Tenant Subscription")
@SecurityRequirement(name = "bearer-jwt")
class TenantSubscriptionController(
    private val useCase: ManageTenantSubscriptionUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.subscription.view')")
    fun get(@PathVariable tenantId: UUID): TenantSubscriptionDetailView =
        useCase.get(tenantId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant belum berlangganan")

    @PutMapping
    @PreAuthorize("@authz.can('platform.subscription.manage')")
    fun configure(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody body: ConfigureRequest,
    ): TenantSubscriptionDetailView =
        useCase.configure(
            tenantId,
            ConfigureSubscriptionCommand(
                monthlyFee = body.monthlyFee!!,
                billingDay = body.billingDay,
                graceDays = body.graceDays,
            ),
        )

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('platform.subscription.manage')")
    fun generateInvoice(@PathVariable tenantId: UUID): SubscriptionInvoiceView =
        useCase.generateInvoice(tenantId)

    @PostMapping("/invoices/{invoiceId}/void")
    @PreAuthorize("@authz.can('platform.subscription.manage')")
    fun voidInvoice(
        @PathVariable tenantId: UUID,
        @PathVariable invoiceId: UUID,
    ): SubscriptionInvoiceView = useCase.voidInvoice(invoiceId)

    @PostMapping("/invoices/{invoiceId}/pay")
    @PreAuthorize("@authz.can('platform.subscription.manage')")
    fun recordManualPayment(
        @PathVariable tenantId: UUID,
        @PathVariable invoiceId: UUID,
        @Valid @RequestBody body: ManualPaymentRequest,
    ): SubscriptionInvoiceView =
        useCase.recordManualPayment(invoiceId, ManualPaymentCommand(body.amount, body.note))

    @PostMapping("/grant")
    @PreAuthorize("@authz.can('platform.subscription.manage')")
    fun grantFreeMonths(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody body: GrantFreeMonthsRequest,
    ): TenantSubscriptionDetailView =
        useCase.grantFreeMonths(tenantId, GrantFreeMonthsCommand(body.months!!, body.reason?.trim()?.ifBlank { null }))

    @PostMapping("/cancel")
    @PreAuthorize("@authz.can('platform.subscription.manage')")
    fun cancel(@PathVariable tenantId: UUID): TenantSubscriptionDetailView = useCase.cancel(tenantId)
}

data class ConfigureRequest(
    @field:NotNull(message = "Biaya bulanan wajib diisi")
    @field:DecimalMin(value = "0", message = "Biaya bulanan tidak boleh negatif")
    val monthlyFee: BigDecimal?,
    @field:Min(1) @field:Max(28)
    val billingDay: Int? = null,
    @field:Min(0) @field:Max(90)
    val graceDays: Int? = null,
)

/** Batas 24 bulan menahan salah ketik yang memberi masa aktif bertahun-tahun secara tak sengaja. */
data class GrantFreeMonthsRequest(
    @field:NotNull(message = "Jumlah bulan wajib diisi")
    @field:Min(value = 1, message = "Bonus minimal 1 bulan")
    @field:Max(value = 24, message = "Bonus maksimal 24 bulan")
    val months: Int?,
    @field:Size(max = 500, message = "Alasan maksimal 500 karakter")
    val reason: String? = null,
)

data class ManualPaymentRequest(
    @field:DecimalMin(value = "0", message = "Nilai pembayaran tidak boleh negatif")
    val amount: BigDecimal? = null,
    val note: String? = null,
)
