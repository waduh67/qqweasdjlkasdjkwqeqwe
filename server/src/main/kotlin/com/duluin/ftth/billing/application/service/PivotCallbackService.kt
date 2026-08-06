package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.adapter.outbound.gateway.PivotChargeScope
import com.duluin.ftth.billing.application.port.inbound.PivotCallbackApi
import com.duluin.ftth.billing.application.port.inbound.RecordPaymentUseCase
import com.duluin.ftth.billing.application.port.inbound.ReconcilePayoutUseCase
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.TenantPayoutRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.billing.domain.model.SubAccountKycStatus
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Otoritas callback Pivot sisi billing (lihat [PivotCallbackApi]). Pivot mengirim SATU URL per produk
 * di akun MASTER, jadi tak ada slug tenant di path — tenant di-resolve dari payload:
 *  - PAYMENT: `metadata.tenantSlug` (di-echo dari charge) → resolve O(1) via [TenantApi.findBySlug].
 *  - PAYOUT/WITHDRAWAL & SUB_ACCOUNT_REGISTRATION: cari lintas tenant (pola `findActiveTenantIds` +
 *    `runAs`, sama seperti job terjadwal lintas-tenant) sampai baris/ akun dengan referensi cocok.
 *
 * Tidak `@Transactional` di level method: tiap operasi tenant dijalankan dalam `TenantContext.runAs`
 * sendiri agar sesi Hibernate terbuka dengan GUC `app.tenant_id` yang benar (RLS). Verifikasi
 * `X-API-Key` master lebih dulu; rekonsiliasi idempotent.
 */
@Service
class PivotCallbackService(
    private val masterConfig: PivotMasterConfigProvider,
    private val registry: PaymentGatewayRegistry,
    private val recordPayment: RecordPaymentUseCase,
    private val reconciler: ReconcilePayoutUseCase,
    private val accounts: TenantPivotAccountRepository,
    private val payouts: TenantPayoutRepository,
    private val tenantApi: TenantApi,
    private val objectMapper: ObjectMapper,
) : PivotCallbackApi {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handlePayment(headers: Map<String, String>, body: String): Boolean {
        verifySignature(headers)
        val data = payloadData(body)
        val metadata = data?.get("metadata")
        val scope = metadata.textOrNull("scope")?.uppercase()
        val tenantSlug = metadata.textOrNull(PivotChargeScope.TENANT_SLUG_KEY)

        // Charge SaaS (scope SAAS / tanpa metadata routing) → pemanggil (platform) yang menyetel.
        if (scope != PivotChargeScope.TENANT && tenantSlug == null) return false

        val slug = tenantSlug ?: run {
            log.error("Callback payment scope TENANT tanpa tenantSlug — pelunasan tak bisa dialamatkan")
            return true
        }
        val tenant = tenantApi.findBySlug(slug) ?: run {
            log.error("Callback payment untuk tenantSlug '{}' yang tak dikenal — diabaikan", slug)
            return true
        }
        val settlement = registry.forProvider("PIVOT")?.parseCallback(GatewayCallback(headers, body), masterCtx())
        if (settlement == null) {
            log.info("Callback payment tenant '{}' bukan pelunasan / tak terparse — di-ACK", slug)
            return true
        }
        TenantContext.runAs(tenant.id) { recordPayment.applySettlement(settlement) }
        return true
    }

    override fun handleDisbursement(headers: Map<String, String>, body: String): Boolean {
        verifySignature(headers)
        val root = objectMapper.readTree(body)
        val data = root.get("data")?.takeIf { !it.isNull } ?: root
        val reference = data.textOrNull("id") ?: data.textOrNull("referenceId") ?: data.textOrNull("reference")
        if (reference == null) {
            log.warn("Callback penyaluran tanpa referensi — diabaikan")
            return false
        }
        val outcome = resolveOutcome(root.textOrNull("event"), data.textOrNull("status"))
            ?: return false // status antara (PROCESSING) — tunggu callback final
        val reason = data.textOrNull("failureReason") ?: data.textOrNull("reason")

        val tenantId = resolveTenant { payouts.findByReference(reference) != null }
        if (tenantId == null) {
            log.warn("Callback penyaluran ref '{}' tak cocok baris tenant mana pun — diabaikan", reference)
            return false
        }
        TenantContext.runAs(tenantId) { reconciler.reconcile(reference, outcome, reason) }
        return true
    }

    override fun handleSubAccountRegistration(headers: Map<String, String>, body: String): Boolean {
        verifySignature(headers)
        val root = objectMapper.readTree(body)
        val data = root.get("data")?.takeIf { !it.isNull } ?: root
        val subId = data.textOrNull("id") ?: data.textOrNull("subMerchantId")
            ?: data.textOrNull("subMerchantUuid") ?: data.textOrNull("uuid")
        if (subId == null) {
            log.warn("Callback registrasi sub-account tanpa uuid — diabaikan")
            return false
        }
        val status = mapStatus(data.textOrNull("subAccountStatus") ?: root.textOrNull("event"))
        val kyc = mapKyc(data.textOrNull("subAccountKycStatus") ?: root.textOrNull("event"))

        val tenantId = resolveTenant { accounts.find()?.subMerchantUuid == subId }
        if (tenantId == null) {
            log.warn("Callback registrasi sub-account uuid '{}' tak dikenal — diabaikan", subId)
            return false
        }
        TenantContext.runAs(tenantId) {
            val account = accounts.find() ?: return@runAs
            account.applyStatus(status ?: account.status, kyc ?: account.kycStatus)
            accounts.save(account)
        }
        log.info("Sub-account '{}' diperbarui dari callback (status={}, kyc={})", subId, status, kyc)
        return true
    }

    override fun handleRefund(headers: Map<String, String>, body: String): Boolean {
        verifySignature(headers)
        val data = payloadData(body)
        val reference = data.textOrNull("id") ?: data.textOrNull("referenceId") ?: data.textOrNull("reference")
        // Belum ada domain refund — cukup ACK + log agar Pivot berhenti retry. TODO: proses balik.
        log.info("Callback refund Pivot diterima (ref '{}') — di-ACK, belum diproses (follow-up)", reference)
        return true
    }

    override fun verifySignature(headers: Map<String, String>) {
        val master = masterConfig.current()
            ?: throw ValidationException("Pivot belum aktif — callback ditolak")
        val expected = master.callbackApiKey?.takeIf { it.isNotBlank() }
            ?: throw ValidationException("Callback API Key master belum diset — callback ditolak")
        val presented = headers.entries
            .firstOrNull { it.key.equals(CALLBACK_KEY_HEADER, ignoreCase = true) }?.value
        if (presented.isNullOrBlank() || !constantTimeEquals(presented, expected)) {
            throw ValidationException("Callback ditolak — X-API-Key tidak cocok")
        }
    }

    /** Konteks minimal akun master untuk `parseCallback` (hanya `webhookToken` yang dipakai verifikasi). */
    private fun masterCtx(): ResolvedGatewayContext {
        val master = masterConfig.current()
        return ResolvedGatewayContext(
            provider = "PIVOT",
            mode = GatewayMode.PLATFORM,
            secretKey = master?.merchantSecret,
            webhookToken = master?.callbackApiKey,
            apiKey = master?.merchantId,
        )
    }

    /** Objek `data` payload Pivot (fallback ke root bila tak ada), atau null bila body tak terparse. */
    private fun payloadData(body: String): JsonNode? = runCatching {
        val root = objectMapper.readTree(body)
        root.get("data")?.takeIf { !it.isNull } ?: root
    }.getOrNull()

    /**
     * Cari tenant aktif pertama yang [matches] benar (dievaluasi dalam `TenantContext.runAs` tenant
     * tsb, jadi RLS sudah menyaring ke datanya). O(N) atas jumlah tenant — dipakai callback penyaluran
     * & registrasi yang tak membawa slug tenant. Follow-up: indeks langsung bila jumlah tenant besar.
     */
    private fun resolveTenant(matches: () -> Boolean): UUID? =
        tenantApi.findActiveTenantIds().firstOrNull { tid -> TenantContext.runAs(tid) { matches() } }

    /** Petakan event/status Pivot → sukses(true)/gagal(false)/null(belum final). */
    private fun resolveOutcome(event: String?, status: String?): Boolean? {
        val token = (event ?: status ?: "").uppercase()
        return when {
            SUCCESS_MARKERS.any { token.contains(it) } -> true
            FAILURE_MARKERS.any { token.contains(it) } -> false
            else -> null
        }
    }

    private fun mapStatus(raw: String?): SubAccountStatus? {
        val t = raw?.uppercase() ?: return null
        return when {
            t.contains("REJECT") -> SubAccountStatus.REJECTED
            t.contains("DEACTIV") || t.contains("DISABL") || t.contains("SUSPEND") -> SubAccountStatus.DEACTIVATED
            t.contains("ACTIVE") || t.contains("APPROVE") -> SubAccountStatus.ACTIVE
            t.contains("CREATED") || t.contains("PENDING") -> SubAccountStatus.CREATED
            else -> null
        }
    }

    private fun mapKyc(raw: String?): SubAccountKycStatus? {
        val t = raw?.uppercase() ?: return null
        return when {
            t.contains("NOT_REQUIRED") || t.contains("NOT REQUIRED") -> SubAccountKycStatus.NOT_REQUIRED
            t.contains("WAITING") || t.contains("DOCUMENT") -> SubAccountKycStatus.WAITING_FOR_DOCUMENT
            t.contains("REVIEW") -> SubAccountKycStatus.IN_REVIEW
            t.contains("APPROVE") -> SubAccountKycStatus.APPROVED
            t.contains("REJECT") -> SubAccountKycStatus.REJECTED
            else -> null
        }
    }

    private fun JsonNode?.textOrNull(field: String): String? =
        this?.get(field)?.takeIf { !it.isNull }?.asString()?.takeIf { it.isNotBlank() }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val CALLBACK_KEY_HEADER = "X-API-Key"
        val SUCCESS_MARKERS = setOf("SUCCESS", "COMPLETED", "SETTLED", "PAID")
        val FAILURE_MARKERS = setOf("FAIL", "REJECT", "CANCEL", "EXPIRED", "RETURNED")
    }
}
