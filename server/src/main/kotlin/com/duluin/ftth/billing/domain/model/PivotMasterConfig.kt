package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.modulith.NamedInterface
import java.util.UUID

/**
 * Tipe fee platform per transaksi. [FIXED] = nominal tetap (mis. Rp1.000/transaksi), [PERCENTAGE]
 * = persen dari nominal (basis 100 → `fixedAmount` di Pivot dihitung dari nominal saat charge).
 * Bagian named interface `gateway` — dipakai resolver billing & platformbilling.
 */
@NamedInterface("gateway")
enum class PivotFeeType { FIXED, PERCENTAGE }

/**
 * Bentuk siap-pakai kredensial + kebijakan MASTER Pivot (sudah terdekripsi), disuntikkan ke
 * resolver saat menyusun [ResolvedGatewayContext]. Analog [PlatformGatewayCreds] lama, tapi untuk
 * model Pivot "business as platform": satu akun master menampung semua sub-account tenant.
 *
 * Bagian named interface `gateway` — `platformbilling` menyusun konteks langganan SaaS dari sini.
 */
@NamedInterface("gateway")
data class PivotMasterContext(
    /** `X-MERCHANT-ID` akun master platform. Juga jadi tujuan split-routing fee platform. */
    val merchantId: String,
    /** `X-MERCHANT-SECRET` akun master platform. */
    val merchantSecret: String,
    /** Callback API Key master (verifikasi header `X-API-Key` semua webhook). Null bila belum diset. */
    val callbackApiKey: String?,
    val sandbox: Boolean,
    /** Fee platform per transaksi (minor unit IDR, mis. 1000). 0 = tanpa fee (tanpa split-routing). */
    val platformFeeMinor: Long,
    val platformFeeType: PivotFeeType,
    /** Channel bank rekening payout platform (mis. `BCA`); null bila belum diset. */
    val payoutChannelCode: String?,
    /** Nomor rekening payout platform; null bila belum diset. */
    val payoutAccountNumber: String?,
)

/**
 * Setelan MASTER Pivot milik platform (satu baris global, PLATFORM-level — tanpa RLS, pola
 * `platform_setting`). Menggantikan model BYOK: tak ada lagi kredensial gateway per-tenant;
 * seluruh transaksi berjalan di akun master ini, tiap tenant jadi sub-account (lihat
 * [TenantPivotAccount]) dan aksi dijalankan on-behalf-of.
 *
 * Kredensial ([merchantId]/[merchantSecret]/[callbackApiKey]) plaintext di domain; adapter
 * persistence yang mengenkripsi ke DB — sama pola `TenantPaymentGateway`. Pada [update],
 * kredensial null/kosong = "biarkan apa adanya" agar sunting fee/payout tak menghapus rahasia.
 */
class PivotMasterConfig private constructor(
    val id: UUID,
    enabled: Boolean,
    merchantId: String?,
    merchantSecret: String?,
    callbackApiKey: String?,
    sandbox: Boolean,
    platformFeeMinor: Long,
    platformFeeType: PivotFeeType,
    payoutChannelCode: String?,
    payoutAccountNumber: String?,
) {
    var enabled: Boolean = enabled
        private set

    /** Ciphertext di DB, plaintext di sini. `X-MERCHANT-ID` master. */
    var merchantId: String? = merchantId
        private set

    /** Ciphertext di DB, plaintext di sini. `X-MERCHANT-SECRET` master. */
    var merchantSecret: String? = merchantSecret
        private set

    /** Ciphertext di DB, plaintext di sini. Callback API Key (verifikasi `X-API-Key`). */
    var callbackApiKey: String? = callbackApiKey
        private set

    var sandbox: Boolean = sandbox
        private set

    /** Fee platform per transaksi (minor unit IDR). Non-rahasia. */
    var platformFeeMinor: Long = platformFeeMinor
        private set

    var platformFeeType: PivotFeeType = platformFeeType
        private set

    /** Channel bank rekening payout platform (non-rahasia). */
    var payoutChannelCode: String? = payoutChannelCode
        private set

    /** Nomor rekening payout platform (non-rahasia). */
    var payoutAccountNumber: String? = payoutAccountNumber
        private set

    /** Apakah kredensial master sudah lengkap (untuk view boolean, tanpa membocorkan rahasia). */
    val credentialsSet: Boolean
        get() = !merchantId.isNullOrBlank() && !merchantSecret.isNullOrBlank()

    /**
     * Sunting setelan dari sisi super-admin. Rahasia null/kosong = biarkan apa adanya. Fee &
     * rekening payout non-rahasia → selalu diganti (null/kosong = kosongkan untuk payout).
     */
    fun update(
        enabled: Boolean,
        merchantId: String?,
        merchantSecret: String?,
        callbackApiKey: String?,
        sandbox: Boolean,
        platformFeeMinor: Long,
        platformFeeType: PivotFeeType,
        payoutChannelCode: String?,
        payoutAccountNumber: String?,
    ) {
        this.enabled = enabled
        this.sandbox = sandbox
        merchantId?.trim()?.takeIf { it.isNotEmpty() }?.let { this.merchantId = validate(it, "Merchant ID") }
        merchantSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { this.merchantSecret = validate(it, "Merchant Secret") }
        callbackApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { this.callbackApiKey = validate(it, "Callback API Key") }
        if (platformFeeMinor < 0) throw ValidationException("Fee platform tidak boleh negatif")
        if (platformFeeType == PivotFeeType.PERCENTAGE && platformFeeMinor > MAX_PERCENT_BASIS) {
            throw ValidationException("Fee persentase maksimal 100")
        }
        this.platformFeeMinor = platformFeeMinor
        this.platformFeeType = platformFeeType
        this.payoutChannelCode = payoutChannelCode?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        this.payoutAccountNumber = payoutAccountNumber?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Bentuk siap-pakai untuk resolver, atau null bila master nonaktif / kredensial belum lengkap.
     * Null = pemanggil jatuh ke fallback (billing → MANUAL; platform billing → error jelas).
     */
    fun resolveContext(): PivotMasterContext? {
        if (!enabled) return null
        val mid = merchantId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val secret = merchantSecret?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PivotMasterContext(
            merchantId = mid,
            merchantSecret = secret,
            callbackApiKey = callbackApiKey?.trim()?.takeIf { it.isNotEmpty() },
            sandbox = sandbox,
            platformFeeMinor = platformFeeMinor,
            platformFeeType = platformFeeType,
            payoutChannelCode = payoutChannelCode,
            payoutAccountNumber = payoutAccountNumber,
        )
    }

    companion object {
        private const val MAX_SECRET = 512
        private const val MAX_PERCENT_BASIS = 100L

        /** Baris bawaan saat platform belum mengonfigurasi Pivot — MATI, tanpa kredensial, fee 0. */
        fun default(): PivotMasterConfig = PivotMasterConfig(
            id = UuidV7.generate(),
            enabled = false,
            merchantId = null,
            merchantSecret = null,
            callbackApiKey = null,
            sandbox = false,
            platformFeeMinor = 0,
            platformFeeType = PivotFeeType.FIXED,
            payoutChannelCode = null,
            payoutAccountNumber = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            enabled: Boolean,
            merchantId: String?,
            merchantSecret: String?,
            callbackApiKey: String?,
            sandbox: Boolean,
            platformFeeMinor: Long,
            platformFeeType: PivotFeeType,
            payoutChannelCode: String?,
            payoutAccountNumber: String?,
        ): PivotMasterConfig = PivotMasterConfig(
            id, enabled, merchantId, merchantSecret, callbackApiKey, sandbox,
            platformFeeMinor, platformFeeType, payoutChannelCode, payoutAccountNumber,
        )

        private fun validate(value: String, label: String): String {
            if (value.length > MAX_SECRET) throw ValidationException("$label maksimal $MAX_SECRET karakter")
            return value
        }
    }
}
