package com.duluin.ftth.billing.adapter.outbound.gateway

import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotApiClient
import com.duluin.ftth.billing.adapter.outbound.gateway.pivot.PivotCredentials
import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.ChargeResult
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.port.outbound.PaymentGateway
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.application.port.outbound.QrInstruction
import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
import com.duluin.ftth.billing.application.port.outbound.VaInstruction
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.PivotFeeType
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
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
import java.util.UUID

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
 * Auth/token & HTTP ditangani [PivotApiClient]. `X-REQUEST-ID` create charge UNIK per panggilan
 * (Pivot menuntut keunikan — id yang diulang ditolak). Callback diverifikasi header static
 * `X-API-Key` (Callback API Key master, BUKAN HMAC) dibanding constant-time; hanya status
 * pelunasan jadi settlement.
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
        // Metode diisi (VIRTUAL_ACCOUNT/QR) → charge mode-API in-app (instruksi bayar langsung di
        // respons, TANPA redirect). Kosong → mode REDIRECT lama (halaman ter-host Pivot).
        val method = request.method?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val apiMode = method != null
        val body = buildChargeBody(request, ctx, method)

        val node = try {
            apiClient.post(
                path = "/v2/payments",
                body = body,
                creds = creds,
                subMerchantId = ctx.subAccountId,
                requestId = newRequestId(),
            )
        } catch (e: ConflictException) {
            // Pivot menolak QR bila produk QRIS belum di-enable di akun merchant ("merchant not
            // registered qr") — ini setelan dashboard Pivot, bukan bug. Beri pesan actionable.
            if (method == METHOD_QR && e.message?.contains("not registered", ignoreCase = true) == true) {
                throw ConflictException(
                    "QRIS belum aktif di akun Pivot. Aktifkan produk QRIS di dashboard Pivot lalu coba lagi.",
                )
            }
            throw e
        }
        val data = node.get("data")
        return if (apiMode) parseApiCharge(data, method!!) else parseRedirectCharge(data)
    }

    /**
     * Rakit body `POST /v2/payments`. Murni (tanpa HTTP) agar bisa diuji. [method] non-null →
     * mode-API (menambah `autoConfirm`+`paymentMethod`+`paymentMethodOptions` untuk instruksi bayar
     * inline); null → mode REDIRECT. Blok `redirectUrl` SELALU disertakan: Pivot memvalidasinya
     * `required` di KEDUA mode (di mode-API tak ada redirect nyata, tapi field tetap dituntut).
     */
    internal fun buildChargeBody(
        request: ChargeRequest,
        ctx: ResolvedGatewayContext,
        method: String?,
    ): Map<String, Any> {
        val amountValue = request.amount.setScale(0, RoundingMode.HALF_UP).toLong()
        val apiMode = method != null
        val expiryAt = pivotExpiryAt(request.dueDate, Instant.now(), ZoneId.systemDefault())
        val redirectBase = billingProperties.pivot.redirectBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Pivot butuh redirect base URL — set FTTH_BILLING_PIVOT_REDIRECT_BASE_URL")

        return buildMap {
            // clientReferenceId UNIK per charge (Pivot menolak yang berulang: "client reference id
            // already exist") — nomor invoice jadi prefiks agar terlacak, tapi keunikan menjaga
            // ganti metode / retry untuk invoice yang sama tetap diterima. Pemetaan pelunasan kembali
            // ke invoice memakai `metadata.invoiceNumber` (di bawah), bukan clientReferenceId.
            put("clientReferenceId", newClientReferenceId(request.invoiceNumber))
            put("amount", mapOf("value" to amountValue, "currency" to "IDR"))
            put("paymentType", "SINGLE")
            // Batas waktu sesi bayar = jatuh tempo tagihan (di-clamp ke maksimum Pivot: expiryAt di
            // luar batas ditolak → charge gagal). Pivot TAK punya field `expirationMode` —
            // mengirimnya untuk VA/QR/E-wallet ditolak ("payment method type is not allowed to set
            // expiration mode").
            expiryAt?.let { put("expiryAt", it) }
            // redirectUrl WAJIB di kedua mode (Pivot memvalidasinya `required`). Mode-API tak
            // benar-benar me-redirect — instruksi bayar kembali inline di `chargeDetails[0]`.
            put("mode", if (apiMode) "API" else "REDIRECT")
            put(
                "redirectUrl",
                mapOf(
                    "successReturnUrl" to "$redirectBase/paid",
                    "failureReturnUrl" to "$redirectBase/failed",
                    "expirationReturnUrl" to "$redirectBase/expired",
                ),
            )
            if (apiMode) {
                // Mode API + autoConfirm → Pivot langsung terbitkan instrumen (nomor VA / string QRIS)
                // di `chargeDetails[0]`; pelanggan membayar dari dalam aplikasi ini, tanpa redirect.
                put("autoConfirm", true)
                put("paymentMethod", mapOf("type" to method!!))
                put("paymentMethodOptions", buildPaymentMethodOptions(method, request, expiryAt))
            }
            put(
                "customer",
                buildMap<String, Any> {
                    put("givenName", request.customerName)
                    // `customer.email` WAJIB di Pivot (tanpa itu: 400 "Email ... required"). Tolak
                    // di sini dengan pesan yang bisa ditindaklanjuti — JANGAN diisi email palsu,
                    // karena struk & notifikasi penyedia dikirim ke alamat tsb.
                    put(
                        "email",
                        request.customerEmail?.takeIf { it.isNotBlank() }
                            ?: throw ValidationException(
                                "Pivot mewajibkan email pembayar, tapi email \"${request.customerName}\" " +
                                    "masih kosong. Lengkapi email dulu sebelum membuat pembayaran.",
                            ),
                    )
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
    }

    /** Opsi metode bayar mode-API sesuai instrumen yang dipilih. */
    private fun buildPaymentMethodOptions(
        method: String,
        request: ChargeRequest,
        expiryAt: String?,
    ): Map<String, Any> = when (method) {
        METHOD_VA -> {
            val channel = request.vaChannel?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: throw ConflictException("Bank Virtual Account wajib dipilih")
            mapOf(
                "virtualAccount" to buildMap<String, Any> {
                    put("channel", channel)
                    expiryAt?.let { put("expiryAt", it) }
                },
            )
        }
        METHOD_QR -> mapOf(
            "qr" to buildMap<String, Any> {
                expiryAt?.let { put("expiryAt", it) }
            },
        )
        else -> throw ConflictException("Metode bayar '$method' tidak didukung")
    }

    /** REDIRECT: baca id sesi & tautan bayar ter-host. */
    private fun parseRedirectCharge(data: JsonNode?): ChargeResult = ChargeResult(
        provider = "PIVOT",
        gatewayRef = data?.get("id")?.asString()?.takeIf { it.isNotBlank() },
        payUrl = data?.get("paymentUrl")?.asString()?.takeIf { it.isNotBlank() },
    )

    /** API: baca instruksi bayar in-app dari `chargeDetails[0]` (nomor VA / string QRIS). */
    private fun parseApiCharge(data: JsonNode?, method: String): ChargeResult {
        val charge = data?.get("chargeDetails")?.takeIf { it.isArray && !it.isEmpty }?.get(0)
        val va = charge?.get("virtualAccount")?.takeIf { !it.isNull }?.let { node ->
            val number = node.get("virtualAccountNumber")?.asString()?.takeIf { it.isNotBlank() }
            number?.let {
                VaInstruction(
                    channel = node.get("channel")?.asString()?.takeIf { c -> c.isNotBlank() },
                    number = it,
                    name = node.get("virtualAccountName")?.asString()?.takeIf { n -> n.isNotBlank() },
                    expiresAt = parseInstant(node.get("expiryAt")),
                )
            }
        }
        val qr = charge?.get("qr")?.takeIf { !it.isNull }?.let { node ->
            val content = node.get("qrContent")?.asString()?.takeIf { it.isNotBlank() }
            content?.let {
                QrInstruction(
                    content = it,
                    url = node.get("qrUrl")?.asString()?.takeIf { u -> u.isNotBlank() },
                    expiresAt = parseInstant(node.get("expiryAt")),
                )
            }
        }
        if (va == null && qr == null) {
            throw ConflictException("Pivot tak mengembalikan instruksi bayar untuk metode '$method'")
        }
        return ChargeResult(
            provider = "PIVOT",
            gatewayRef = data.get("id")?.asString()?.takeIf { it.isNotBlank() },
            payUrl = null,
            status = charge?.get("status")?.asString()?.takeIf { it.isNotBlank() },
            method = method,
            virtualAccount = va,
            qr = qr,
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
            // Nomor invoice dibaca dari `metadata.invoiceNumber` (di-echo Pivot) lebih dulu karena
            // `clientReferenceId` kini unik per charge (bukan lagi nomor invoice). Fallback ke
            // clientReferenceId untuk kompatibilitas charge lama / mode REDIRECT.
            val invoiceNumber = data.at("/metadata/invoiceNumber").asString().takeIf { it.isNotBlank() }
                ?: data.get("clientReferenceId")?.asString()?.takeIf { it.isNotBlank() }
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

    /**
     * Simulasi pembayaran Pivot (`POST /v2/payments/simulations`) — HANYA tersedia di lingkungan
     * testing (`api-stg`), karena itu ditolak tegas bila kredensial bukan sandbox alih-alih
     * membiarkan produksi menabrak 404. `amount` sengaja tak dikirim: opsional untuk `paymentType`
     * SINGLE (semua charge di sini SINGLE) sehingga Pivot memakai nominal sesi apa adanya.
     *
     * Efeknya asinkron: Pivot mengirim callback pembayaran seperti transaksi nyata, dan pelunasan
     * tagihan terjadi di jalur webhook (`PivotCallbackService`) — bukan di sini.
     */
    override fun simulateCharge(
        paymentSessionId: String,
        chargeStatus: SimulatedChargeStatus,
        ctx: ResolvedGatewayContext,
    ) {
        val creds = ctx.pivotCredentials()
        if (!creds.sandbox) {
            throw ConflictException("Simulasi pembayaran hanya tersedia saat Pivot dalam mode sandbox")
        }
        apiClient.post(
            path = "/v2/payments/simulations",
            body = mapOf("paymentSessionId" to paymentSessionId, "chargeStatus" to chargeStatus.name),
            creds = creds,
            subMerchantId = ctx.subAccountId,
            requestId = newRequestId(),
        )
        log.info("Simulasi pembayaran Pivot dikirim: sesi={} status={}", paymentSessionId, chargeStatus)
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
        return parseInstant(charges.get(0)?.get("paidAt"))
    }

    /**
     * Urai timestamp ISO-8601 Pivot → [Instant]; abaikan nilai kosong, tak-terurai, atau sentinel
     * "kosong" (mis. `0001-01-01T…` yang Pivot pakai saat tak ada kedaluwarsa).
     */
    private fun parseInstant(node: JsonNode?): Instant? {
        val text = node?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { Instant.parse(text) }.getOrNull() ?: return null
        return parsed.takeIf { it.isAfter(EPOCH_FLOOR) }
    }

    /**
     * `X-REQUEST-ID` create charge: alfanumerik 16–36 char, UNIK per panggilan. Pivot MENOLAK id
     * yang diulang (`Use unique X-Request-Id`) — bukan meng-echo charge pertama — jadi tiap create
     * charge memakai UUID acak (hex 32 char + prefiks `req` = 35, masuk rentang). Konsekuensi: retry
     * jaringan bisa melahirkan sesi bayar ganda; diterima untuk charge on-demand (sesi lama
     * kedaluwarsa lewat `expiryAt`).
     */
    internal fun newRequestId(): String =
        (REQUEST_PREFIX + UUID.randomUUID().toString().replace("-", "")).take(REQUEST_ID_MAX)

    /**
     * `clientReferenceId` UNIK per charge: `<invoiceNumber>-<epochMillis>-<rand4>`. Nomor invoice
     * tetap jadi prefiks (mudah dilacak di dashboard Pivot); komponen waktu + acak menjamin keunikan
     * agar Pivot tak menolak "client reference id already exist" saat tenant ganti metode (VA↔QRIS)
     * atau retry untuk invoice yang sama. Pemetaan pelunasan kembali ke invoice memakai
     * `metadata.invoiceNumber`, bukan nilai ini.
     */
    internal fun newClientReferenceId(invoiceNumber: String): String =
        "$invoiceNumber-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4)}"

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val CALLBACK_KEY_HEADER = "X-API-Key"
        const val MAX_ITEM_NAME = 255
        const val PERCENT_BASIS = 100L
        const val REQUEST_PREFIX = "req"
        const val REQUEST_ID_MAX = 36
        const val METHOD_VA = "VIRTUAL_ACCOUNT"
        const val METHOD_QR = "QR"
        /** Ambang bawah timestamp valid — nilai sebelum ini dianggap sentinel "kosong" Pivot. */
        val EPOCH_FLOOR: Instant = Instant.parse("2000-01-01T00:00:00Z")
        val SETTLED_STATUSES = setOf("PAID", "SETTLED", "SUCCESS")
    }
}
