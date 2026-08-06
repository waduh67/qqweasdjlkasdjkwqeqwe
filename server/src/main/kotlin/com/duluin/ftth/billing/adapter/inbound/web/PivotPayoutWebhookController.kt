package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ReconcilePayoutUseCase
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Webhook rekonsiliasi penyaluran dana Pivot (payout & withdrawal) — TANPA bearer (diizinkan di
 * SecurityConfig). Diverifikasi header `X-API-Key` = Callback API Key MASTER (bukan per-tenant),
 * dibanding constant-time. Tenant di-resolve dari slug path → RLS benar saat memperbarui baris.
 *
 * Terpisah dari [BillingWebhookController] (pelunasan tagihan pelanggan): payout/withdrawal tak
 * menyentuh invoice — hanya menutup status baris `tenant_payout` via referensi Pivot. Callback
 * ganda aman ([ReconcilePayoutUseCase.reconcile] idempotent).
 */
@RestController
@RequestMapping("/api/billing/webhooks")
@Tag(name = "Billing — webhook penyaluran dana")
class PivotPayoutWebhookController(
    private val tenantApi: TenantApi,
    private val masterConfig: PivotMasterConfigProvider,
    private val reconciler: ReconcilePayoutUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{tenantSlug}/pivot-payout")
    @Operation(summary = "Terima callback status payout/withdrawal dari Pivot")
    fun receive(
        @PathVariable tenantSlug: String,
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> {
        val tenant = tenantApi.findBySlug(tenantSlug)
            ?: throw NotFoundException("Tenant '$tenantSlug' tidak dikenal")
        val master = masterConfig.current()
            ?: throw ValidationException("Pivot belum aktif — callback ditolak")

        val presented = headers.entries.firstOrNull { it.key.equals(CALLBACK_KEY_HEADER, ignoreCase = true) }?.value
        val expected = master.callbackApiKey
        if (expected.isNullOrBlank() || presented == null || !constantTimeEquals(presented, expected)) {
            throw ValidationException("Callback ditolak")
        }

        val root = objectMapper.readTree(body)
        val data = root.get("data")?.takeIf { !it.isNull } ?: root
        val reference = data.textOrNull("id") ?: data.textOrNull("referenceId") ?: data.textOrNull("reference")
            ?: throw ValidationException("Callback tak berisi referensi penyaluran")
        val outcome = resolveOutcome(root.textOrNull("event"), data.textOrNull("status"))
            ?: return mapOf("status" to "ignored") // status antara (PROCESSING) — tunggu callback final

        return TenantContext.runAs(tenant.id) {
            reconciler.reconcile(reference, outcome, data.textOrNull("failureReason") ?: data.textOrNull("reason"))
            mapOf("status" to "ok")
        }
    }

    /** Petakan event/status Pivot → sukses(true)/gagal(false)/null(belum final). */
    private fun resolveOutcome(event: String?, status: String?): Boolean? {
        val token = (event ?: status ?: "").uppercase()
        return when {
            SUCCESS_MARKERS.any { token.contains(it) } -> true
            FAILURE_MARKERS.any { token.contains(it) } -> false
            else -> null
        }
    }

    private fun JsonNode.textOrNull(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val CALLBACK_KEY_HEADER = "X-API-Key"
        val SUCCESS_MARKERS = setOf("SUCCESS", "COMPLETED", "SETTLED", "PAID")
        val FAILURE_MARKERS = setOf("FAIL", "REJECT", "CANCEL", "EXPIRED", "RETURNED")
    }
}
