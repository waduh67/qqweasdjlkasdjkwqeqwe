package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotApiClient
import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotCredentials
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.PivotFeeType
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** maximumExpiry terbesar yang dijamin Pivot (kartu & virtual account = 30 HARI, per get-payment-method-config). */
private const val PIVOT_MAX_EXPIRY_DAYS = 30L

/**
 * `expiryAt` (ISO-8601 UTC) untuk sesi bayar Pivot STRICT: berlaku sampai HABIS hari jatuh tempo
 * ([dueDate] akhir hari di [zone]). Di-clamp ke `[now+1 jam, now+30 hari]` — 30 hari = maximumExpiry
 * terbesar Pivot; expiryAt di atas itu ditolak → charge gagal. Batas bawah jaga-jaga bila jatuh
 * tempo sudah lewat (terbit-ulang/overdue) agar sesi tak langsung mati. `null` (tanpa [dueDate]) →
 * tak dikirim, Pivot memakai default (~28 hari).
 */
internal fun pivotExpiryAt(dueDate: LocalDate?, now: Instant, zone: ZoneId): String? {
    val target = dueDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant() ?: return null
    val floor = now.plus(1, ChronoUnit.HOURS)
    val ceil = now.plus(PIVOT_MAX_EXPIRY_DAYS, ChronoUnit.DAYS)
    return target.coerceIn(floor, ceil).toString()
}

/**
 * Kunci metadata routing charge Pivot — disematkan saat create charge & di-echo Pivot di callback
 * pembayaran. Dipakai [com.duluin.ftth.billing.application.service.PivotCallbackService] memilah
 * charge pelanggan tenant (scope [TENANT] + [TENANT_SLUG_KEY]) vs langganan SaaS (scope [SAAS]).
 */
internal object PivotChargeScope {
    const val KEY = "scope"
    const val TENANT_SLUG_KEY = "tenantSlug"
    const val TENANT = "TENANT"
    const val SAAS = "SAAS"
}

/**
 * Adapter Pivot (pivot-payment.com) untuk model "business as platform": SEMUA charge dibuat di
 * akun MASTER platform ([ResolvedGatewayContext.apiKey]/[ResolvedGatewayContext.secretKey]).
 *
 *  - **Tagihan pelanggan tenant**: charge dibuat ON-BEHALF-OF sub-account tenant
 *    ([ResolvedGatewayContext.subAccountId] → header `x-submerchant-id`) + `splitRoutingConfigurations`
 *    memotong fee platform ([ResolvedGatewayContext.platformFeeMinor]) ke akun master. Pelanggan
 *    membayar nominal tagihan apa adanya; tenant menerima sisanya (fee terpotong dari hasil tenant).
 *  - **Langganan SaaS tenant**: `subAccountId` null → charge langsung di master, tanpa split (100%
 *    ke platform).
 *
 * Auth/token & HTTP ditangani [PivotApiClient]. Idempotency `X-REQUEST-ID` diturunkan dari nomor
 * tagihan (deterministik → retry aman). Callback diverifikasi header static `X-API-Key` (Callback
 * API Key master, BUKAN HMAC) dibanding constant-time; hanya status pelunasan jadi settlement.
 *
 * IDR zero-decimal — `amount.value` dibulatkan ke bilangan bulat (nilai tagihan di DB tetap scale-2).
 * Butuh `ftth.billing.pivot.redirect-base-url` (mode REDIRECT wajib URL balik).
 */
@Component
class PivotPaymentGateway(
    private val apiClient: PivotApiClient,
    private val objectMapper: ObjectMapper,
    private val billingProperties: BillingProperties,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val provider: String = "PIVOT"

    override fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult {
        val creds = ctx.pivotCredentials()
        val redirectBase = billingProperties.pivot.redirectBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Pivot butuh redirect base URL — set FTTH_BILLING_PIVOT_REDIRECT_BASE_URL")

        val amountValue = request.amount.setScale(0, RoundingMode.HALF_UP).toLong()
        val body = buildMap<String, Any> {
            put("clientReferenceId", request.invoiceNumber)
            put("amount", mapOf("value" to amountValue, "currency" to "IDR"))
            put("paymentType", "SINGLE")
            put("mode", "REDIRECT")
            // Batas waktu sesi bayar = jatuh tempo tagihan (STRICT: tak diperpanjang otomatis). Di-clamp
            // ke rentang aman: expiryAt di luar batas maksimum Pivot ditolak → charge gagal.
            pivotExpiryAt(request.dueDate, Instant.now(), ZoneId.systemDefault())?.let {
                put("expiryAt", it)
                put("expirationMode", "STRICT")
            }
            put(
                "redirectUrl",
                mapOf(
                    "successReturnUrl" to "$redirectBase/paid",
                    "failureReturnUrl" to "$redirectBase/failed",
                    "expirationReturnUrl" to "$redirectBase/expired",
                ),
            )
            put(
                "customer",
                buildMap<String, Any> {
                    put("givenName", request.customerName)
                    request.customerEmail?.takeIf { it.isNotBlank() }?.let { put("email", it) }
                },
            )
            put(
                "orderInformation",
                mapOf(
                    "productDetails" to listOf(
                        mapOf(
                            "type" to "DIGITAL",
                            "name" to request.description.take(MAX_ITEM_NAME),
                            "quantity" to 1,
                            "price" to mapOf("value" to amountValue, "currency" to "IDR"),
                        ),
                    ),
                ),
            )
            // Fee platform via split-routing — HANYA untuk charge on-behalf sub-account (tagihan
            // pelanggan); langganan SaaS (subAccountId null) tanpa split. Fee dihitung sebagai nominal
            // tetap (FIXED) ke akun master; PERCENTAGE dikonversi dari nilai tagihan saat ini.
            splitRouting(ctx, amountValue)?.let { put("splitRoutingConfigurations", it) }
            // Metadata di-echo Pivot di callback pembayaran → dipakai routing satu URL master:
            // charge on-behalf sub-account (subAccountId != null) = tagihan pelanggan tenant
            // (scope TENANT + tenantSlug untuk resolve O(1)); tanpa sub-account = langganan SaaS.
            put(
                "metadata",
                buildMap<String, Any> {
                    put("invoiceNumber", request.invoiceNumber)
                    val tenantSlug = ctx.tenantSlug?.takeIf { it.isNotBlank() }
                    if (ctx.subAccountId != null && tenantSlug != null) {
                        put(PivotChargeScope.KEY, PivotChargeScope.TENANT)
                        put(PivotChargeScope.TENANT_SLUG_KEY, tenantSlug)
                    } else {
                        put(PivotChargeScope.KEY, PivotChargeScope.SAAS)
                    }
                },
            )
        }

        val node = apiClient.post(
            path = "/v2/payments",
            body = body,
            creds = creds,
            subMerchantId = ctx.subAccountId,
            requestId = idempotencyKey(request.invoiceNumber),
        )
        val data = node.get("data")
        return ChargeResult(
            provider = "PIVOT",
            gatewayRef = data?.get("id")?.asString()?.takeIf { it.isNotBlank() },
            payUrl = data?.get("paymentUrl")?.asString()?.takeIf { it.isNotBlank() },
        )
    }

    override fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement? {
        val expected = ctx.webhookToken?.takeIf { it.isNotBlank() }
        if (expected == null) {
            log.warn("Callback Pivot ditolak — Callback API Key master belum diset")
            return null
        }
        val apiKey = callback.headers.entries
            .firstOrNull { it.key.equals(CALLBACK_KEY_HEADER, ignoreCase = true) }
            ?.value
        if (apiKey.isNullOrBlank() || !constantTimeEquals(apiKey, expected)) {
            log.warn("Callback Pivot ditolak — X-API-Key tidak cocok")
            return null
        }
        return runCatching {
            val data = objectMapper.readTree(callback.rawBody).get("data") ?: return@runCatching null
            val status = data.get("status")?.asString()?.uppercase()
            if (status !in SETTLED_STATUSES) {
                log.info("Callback Pivot diabaikan — status '{}' bukan pelunasan", status)
                return@runCatching null
            }
            val invoiceNumber = data.get("clientReferenceId")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val amountText = data.get("amount")?.get("value")?.asString()?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            PaymentSettlement(
                invoiceNumber = invoiceNumber,
                gatewayRef = data.get("id")?.asString()?.takeIf { it.isNotBlank() },
                amount = BigDecimal(amountText),
                paidAt = firstChargePaidAt(data) ?: Instant.now(),
                provider = "PIVOT",
            )
        }.getOrElse {
            log.warn("Callback Pivot tidak bisa diurai: {}", it.message)
            null
        }
    }

    /** Konfigurasi split-routing fee platform, atau null bila tak ada fee / bukan charge on-behalf. */
    private fun splitRouting(ctx: ResolvedGatewayContext, amountValue: Long): List<Map<String, Any>>? {
        if (ctx.subAccountId.isNullOrBlank()) return null
        val masterId = ctx.apiKey?.takeIf { it.isNotBlank() } ?: return null
        val feeValue = when (ctx.platformFeeType) {
            PivotFeeType.FIXED -> ctx.platformFeeMinor
            PivotFeeType.PERCENTAGE -> amountValue * ctx.platformFeeMinor / PERCENT_BASIS
        }
        if (feeValue <= 0 || feeValue >= amountValue) return null
        return listOf(
            mapOf(
                "merchantId" to masterId,
                "type" to "FIXED",
                "currency" to "IDR",
                "fixedAmount" to feeValue,
                "remarks" to "Platform fee",
            ),
        )
    }

    /** Kredensial akun master dari ctx (dijamin lengkap oleh resolver; melempar jelas bila tidak). */
    private fun ResolvedGatewayContext.pivotCredentials(): PivotCredentials {
        val merchantId = apiKey?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Kredensial Pivot master belum lengkap — isi Merchant ID di setelan platform")
        val merchantSecret = secretKey?.takeIf { it.isNotBlank() }
            ?: throw ConflictException("Kredensial Pivot master belum lengkap — isi Merchant Secret di setelan platform")
        return PivotCredentials(merchantId, merchantSecret, sandbox)
    }

    /** Waktu bayar dari `chargeDetails[0].paidAt` bila ada & bisa diurai, else null (pemanggil pakai now). */
    private fun firstChargePaidAt(data: JsonNode): Instant? {
        val charges = data.get("chargeDetails")?.takeIf { it.isArray && !it.isEmpty } ?: return null
        val text = charges.get(0)?.get("paidAt")?.asString()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
    }

    /**
     * `X-REQUEST-ID` idempotency: alfanumerik 16–36 char, deterministik dari nomor tagihan agar
     * retry charge tak menggandakan payment session di Pivot.
     */
    private fun idempotencyKey(invoiceNumber: String): String {
        val cleaned = invoiceNumber.filter { it.isLetterOrDigit() }.ifEmpty { "INV" }
        val padded = (REQUEST_PREFIX + cleaned).take(REQUEST_ID_MAX)
        return if (padded.length >= REQUEST_ID_MIN) padded else padded.padEnd(REQUEST_ID_MIN, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val CALLBACK_KEY_HEADER = "X-API-Key"
        const val MAX_ITEM_NAME = 255
        const val PERCENT_BASIS = 100L
        const val REQUEST_PREFIX = "req"
        const val REQUEST_ID_MIN = 16
        const val REQUEST_ID_MAX = 36
        val SETTLED_STATUSES = setOf("PAID", "SETTLED", "SUCCESS")
    }
}
