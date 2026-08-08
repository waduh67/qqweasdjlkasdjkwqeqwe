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
 * Nilai default level-platform untuk field wajib `POST /v1/sub-merchants` yang SAMA untuk semua
 * sub-account tenant (referensi bisnis/industri). Karena sub-account NON_KYC bertransaksi atas nama
 * akun master, field-field ini logis diisi sekali oleh platform, bukan tiap tenant. Field identitas
 * spesifik-tenant (nama, email, PIC, alamat, rekening) diisi tenant di profil sub-account.
 *
 * Semua nullable: bila belum dikonfigurasi, provisioning melempar ValidationException yang menyebut
 * field mana yang kurang. Nilai referensi (mcc/parentIndustry/childIndustry/districtId/businessStructure)
 * WAJIB valid menurut daftar referensi Pivot — diverifikasi di sandbox.
 *
 * Bagian named interface `gateway` — dibawa [PivotMasterContext] ke resolver/provisioning billing.
 */
@NamedInterface("gateway")
data class SubAccountDefaults(
    val businessType: String?,
    val businessStructure: String?,
    val parentIndustry: String?,
    val childIndustry: String?,
    val mcc: String?,
    val digitalStatus: String?,
    val businessCountry: String?,
    val countryOfEntity: String?,
    val logoUrl: String?,
    val website: String?,
    val districtId: Int?,
    val postCode: String?,
) {
    companion object {
        fun empty() = SubAccountDefaults(
            businessType = null, businessStructure = null, parentIndustry = null,
            childIndustry = null, mcc = null, digitalStatus = null, businessCountry = null,
            countryOfEntity = null, logoUrl = null, website = null, districtId = null, postCode = null,
        )
    }
}

/**
 * Bentuk siap-pakai kredensial + kebijakan MASTER Pivot (sudah terdekripsi), disuntikkan ke
 * resolver saat menyusun [ResolvedGatewayContext]. Analog [PlatformGatewayCreds] lama, tapi untuk
 * model Pivot "business as platform": satu akun master menampung semua sub-account tenant.
 *
 * Bagian named interface `gateway` — `platformbilling` menyusun konteks langganan SaaS dari sini.
 */
@NamedInterface("gateway")
data class PivotMasterContext(
    /** `X-MERCHANT-ID` akun master platform (= Client ID dashboard Pivot). Juga tujuan split-routing fee. */
    val merchantId: String,
    /** `X-MERCHANT-SECRET` akun master platform (= Client Secret dashboard Pivot). */
    val merchantSecret: String,
    /** Callback Secret master (verifikasi header `X-API-Key` semua webhook). Null bila belum diset. */
    val callbackApiKey: String?,
    val sandbox: Boolean,
    /** Fee platform per transaksi (minor unit IDR, mis. 1000). 0 = tanpa fee (tanpa split-routing). */
    val platformFeeMinor: Long,
    val platformFeeType: PivotFeeType,
    /**
     * Biaya payout yang ditagihkan platform ke tenant (minor unit IDR untuk FIXED, angka persen
     * untuk PERCENTAGE). 0 = platform menanggung sendiri biaya Pivot. Dipotong dari nominal yang
     * diminta tenant lalu dipindahkan ke dompet master.
     */
    val payoutFeeMinor: Long,
    val payoutFeeType: PivotFeeType,
    /** Channel bank rekening payout platform (mis. `BCA`); null bila belum diset. */
    val payoutChannelCode: String?,
    /** Nomor rekening payout platform; null bila belum diset. */
    val payoutAccountNumber: String?,
    /** Default field wajib create sub-account (diisi platform sekali). */
    val subAccountDefaults: SubAccountDefaults,
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
    payoutFeeMinor: Long,
    payoutFeeType: PivotFeeType,
    payoutChannelCode: String?,
    payoutAccountNumber: String?,
    subAccountDefaults: SubAccountDefaults,
) {
    var enabled: Boolean = enabled
        private set

    /** Ciphertext di DB, plaintext di sini. `X-MERCHANT-ID` master (= Client ID dashboard Pivot). */
    var merchantId: String? = merchantId
        private set

    /** Ciphertext di DB, plaintext di sini. `X-MERCHANT-SECRET` master (= Client Secret dashboard Pivot). */
    var merchantSecret: String? = merchantSecret
        private set

    /** Ciphertext di DB, plaintext di sini. Callback Secret (verifikasi `X-API-Key`). */
    var callbackApiKey: String? = callbackApiKey
        private set

    /** Default field wajib create sub-account (non-rahasia). */
    var subAccountDefaults: SubAccountDefaults = subAccountDefaults
        private set

    var sandbox: Boolean = sandbox
        private set

    /** Fee platform per transaksi (minor unit IDR). Non-rahasia. */
    var platformFeeMinor: Long = platformFeeMinor
        private set

    var platformFeeType: PivotFeeType = platformFeeType
        private set

    /**
     * Biaya payout yang ditagihkan ke tenant (minor unit IDR). Non-rahasia.
     *
     * Terpisah dari [platformFeeMinor]: yang itu dipotong dari tagihan PELANGGAN lewat split-routing
     * dan mendarat di dompet PAYMENT master, sedangkan yang ini menutup biaya `POST /v1/payouts`
     * yang Pivot tagihkan ke dompet DISBURSEMENT master saat TENANT menyalurkan dana.
     */
    var payoutFeeMinor: Long = payoutFeeMinor
        private set

    var payoutFeeType: PivotFeeType = payoutFeeType
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
        payoutFeeMinor: Long,
        payoutFeeType: PivotFeeType,
        payoutChannelCode: String?,
        payoutAccountNumber: String?,
        subAccountDefaults: SubAccountDefaults,
    ) {
        this.enabled = enabled
        this.sandbox = sandbox
        // Label mengikuti dashboard Pivot: Client ID → X-MERCHANT-ID, Client Secret → X-MERCHANT-SECRET.
        merchantId?.trim()?.takeIf { it.isNotEmpty() }?.let { this.merchantId = validate(it, "Client ID") }
        merchantSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { this.merchantSecret = validate(it, "Client Secret") }
        callbackApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { this.callbackApiKey = validate(it, "Callback Secret") }
        if (platformFeeMinor < 0) throw ValidationException("Fee platform tidak boleh negatif")
        if (platformFeeType == PivotFeeType.PERCENTAGE && platformFeeMinor > MAX_PERCENT_BASIS) {
            throw ValidationException("Fee persentase maksimal 100")
        }
        this.platformFeeMinor = platformFeeMinor
        this.platformFeeType = platformFeeType
        if (payoutFeeMinor < 0) throw ValidationException("Biaya payout tidak boleh negatif")
        if (payoutFeeType == PivotFeeType.PERCENTAGE && payoutFeeMinor > MAX_PERCENT_BASIS) {
            throw ValidationException("Biaya payout persentase maksimal 100")
        }
        this.payoutFeeMinor = payoutFeeMinor
        this.payoutFeeType = payoutFeeType
        this.payoutChannelCode = payoutChannelCode?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        this.payoutAccountNumber = payoutAccountNumber?.trim()?.takeIf { it.isNotEmpty() }
        this.subAccountDefaults = subAccountDefaults.normalized()
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
            payoutFeeMinor = payoutFeeMinor,
            payoutFeeType = payoutFeeType,
            payoutChannelCode = payoutChannelCode,
            payoutAccountNumber = payoutAccountNumber,
            subAccountDefaults = subAccountDefaults,
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
            payoutFeeMinor = 0,
            payoutFeeType = PivotFeeType.FIXED,
            payoutChannelCode = null,
            payoutAccountNumber = null,
            subAccountDefaults = SubAccountDefaults.empty(),
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
            payoutFeeMinor: Long,
            payoutFeeType: PivotFeeType,
            payoutChannelCode: String?,
            payoutAccountNumber: String?,
            subAccountDefaults: SubAccountDefaults,
        ): PivotMasterConfig = PivotMasterConfig(
            id, enabled, merchantId, merchantSecret, callbackApiKey, sandbox,
            platformFeeMinor, platformFeeType, payoutFeeMinor, payoutFeeType,
            payoutChannelCode, payoutAccountNumber,
            subAccountDefaults,
        )

        private fun validate(value: String, label: String): String {
            if (value.length > MAX_SECRET) throw ValidationException("$label maksimal $MAX_SECRET karakter")
            return value
        }

        /** Rapikan nilai default: trim, string kosong → null, businessType/digitalStatus dinormalkan. */
        private fun SubAccountDefaults.normalized() = SubAccountDefaults(
            businessType = businessType?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            businessStructure = businessStructure?.trim()?.takeIf { it.isNotEmpty() },
            parentIndustry = parentIndustry?.trim()?.takeIf { it.isNotEmpty() },
            childIndustry = childIndustry?.trim()?.takeIf { it.isNotEmpty() },
            mcc = mcc?.trim()?.takeIf { it.isNotEmpty() },
            digitalStatus = digitalStatus?.trim()?.takeIf { it.isNotEmpty() },
            businessCountry = businessCountry?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            countryOfEntity = countryOfEntity?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            logoUrl = logoUrl?.trim()?.takeIf { it.isNotEmpty() },
            website = website?.trim()?.takeIf { it.isNotEmpty() },
            districtId = districtId,
            postCode = postCode?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
