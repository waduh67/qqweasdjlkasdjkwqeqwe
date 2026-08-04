package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.platformbilling.application.port.inbound.ConfigureSubscriptionCommand
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

data class ManualPaymentRequest(
    @field:DecimalMin(value = "0", message = "Nilai pembayaran tidak boleh negatif")
    val amount: BigDecimal? = null,
    val note: String? = null,
)
