package com.duluin.ftth.catalog.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Paket internet — SUMBER TUNGGAL harga + kecepatan + QoS + FUP + siklus billing
 * sebuah layanan, dipakai ulang banyak langganan.
 *
 * Menggantikan tiga definisi paket yang dulu kececer (RateProfile teknis tanpa harga
 * di bng, teks bebas packageName/monthlyFee di customer, dan penaut rateProfileId di
 * akun). Di modul `catalog` inilah atribut komersial & jaringan disatukan; modul lain
 * merujuk `planId` dan mewarisi:
 *  - **komersial** (harga, flag prorate, override siklus) → di-snapshot ke langganan
 *    saat create/aktivasi agar invoice stabil walau paket berubah kelak.
 *  - **jaringan** (kecepatan/burst/prioritas/limit-at/FUP) → dibaca live oleh bng,
 *    dirakit jadi atribut RADIUS Mikrotik-Rate-Limit lewat [rateLimitString].
 *
 * Paket tidak dihapus keras (integritas snapshot invoice & grup RADIUS); ia
 * dinonaktifkan lewat flag `active` sehingga tak lagi bisa dipilih langganan baru.
 */
class Plan private constructor(
    val id: UUID,
    val tenantId: UUID,
    attributes: PlanAttributes,
) {
    /** Seluruh atribut paket yang bisa diubah, sudah tervalidasi & ternormalisasi. */
    var attributes: PlanAttributes = attributes
        private set

    val name: String get() = attributes.name
    val active: Boolean get() = attributes.active

    fun update(newAttributes: PlanAttributes) {
        attributes = validate(newAttributes)
    }

    /**
     * Atribut **Mikrotik-Rate-Limit** (VSA vendor 14988 tipe 8) yang dirakit dari field
     * terstruktur — pengganti input nama profil manual. Urutan `rx/tx` = `up/down`,
     * sesuai konvensi `RadiusDae.mikrotikRateLimit(up, down)` & seed lab RADIUS.
     *
     * Tata-bahasa MikroTik bersifat posisional:
     * `rx/tx [rx-burst/tx-burst [rx-thr/tx-thr [rx-time/tx-time [priority [rx-min/tx-min]]]]]`.
     * Grup yang tak diisi tapi masih ada grup terisi sesudahnya dijejali placeholder
     * netral; ekor yang seluruhnya kosong dipangkas.
     */
    fun rateLimitString(): String = with(attributes) {
        val rate = "${upMbps}M/${downMbps}M"
        val burst = pair(upBurstMbps, downBurstMbps)
        val threshold = pair(upThresholdMbps, downThresholdMbps)
        val time = burstTimeSec?.let { "$it/$it" }
        val priorityToken = priority.takeIf { it != DEFAULT_PRIORITY }?.toString()
        val minRate = pair(upMinMbps, downMinMbps)

        val groups = listOf(rate, burst, threshold, time, priorityToken, minRate)
        val placeholders = listOf(rate, "0M/0M", "0M/0M", "0/0", DEFAULT_PRIORITY.toString(), "0M/0M")
        val lastSet = groups.indexOfLast { it != null }
        (0..lastSet).joinToString(" ") { groups[it] ?: placeholders[it] }
    }

    /**
     * Atribut Mikrotik-Rate-Limit untuk grup FUP (kecepatan throttle setelah kuota
     * habis). `null` bila paket tak ber-FUP. Sengaja rate polos tanpa burst — throttle
     * harus tegas.
     */
    fun fupRateLimitString(): String? = with(attributes) {
        if (!fupEnabled) return null
        val up = fupUpMbps ?: return null
        val down = fupDownMbps ?: return null
        "${up}M/${down}M"
    }

    companion object {
        const val DEFAULT_PRIORITY = 8

        fun create(tenantId: UUID, attributes: PlanAttributes): Plan =
            Plan(id = UuidV7.generate(), tenantId = tenantId, attributes = validate(attributes))

        fun rehydrate(id: UUID, tenantId: UUID, attributes: PlanAttributes): Plan =
            Plan(id, tenantId, attributes)

        private fun pair(up: Int?, down: Int?): String? =
            if (up != null && down != null) "${up}M/${down}M" else null

        /**
         * Memvalidasi & menormalkan seluruh atribut sekaligus (trim teks, skala harga,
         * rentang, dan kaitan antar-field seperti burst ≥ rate). Sengaja satu pintu agar
         * create & update menegakkan aturan yang persis sama.
         */
        @Suppress("CyclomaticComplexMethod")
        private fun validate(a: PlanAttributes): PlanAttributes {
            val name = a.name.trim().also {
                if (it.length !in 2..60) throw ValidationException("Nama paket harus 2-60 karakter")
            }
            val description = a.description?.trim()?.takeIf { it.isNotEmpty() }?.also {
                if (it.length > 200) throw ValidationException("Keterangan paket maksimal 200 karakter")
            }
            val price = a.price.setScale(2, RoundingMode.HALF_UP).also {
                if (it.signum() < 0) throw ValidationException("Harga paket tidak boleh negatif")
            }

            val downMbps = mbps(a.downMbps, "Kecepatan unduh")
            val upMbps = mbps(a.upMbps, "Kecepatan unggah")

            val downBurst = optionalMbps(a.downBurstMbps, "Burst unduh")
            val upBurst = optionalMbps(a.upBurstMbps, "Burst unggah")
            requirePaired(upBurst, downBurst, "Burst")
            if (downBurst != null && downBurst < downMbps) throw ValidationException("Burst unduh harus ≥ kecepatan unduh")
            if (upBurst != null && upBurst < upMbps) throw ValidationException("Burst unggah harus ≥ kecepatan unggah")

            val downThreshold = optionalMbps(a.downThresholdMbps, "Threshold unduh")
            val upThreshold = optionalMbps(a.upThresholdMbps, "Threshold unggah")
            requirePaired(upThreshold, downThreshold, "Threshold")
            if (downThreshold != null) {
                if (downBurst == null) throw ValidationException("Threshold butuh burst diisi lebih dulu")
                if (downThreshold !in downMbps..downBurst) throw ValidationException("Threshold unduh harus di antara kecepatan & burst")
                if (upThreshold!! !in upMbps..upBurst!!) throw ValidationException("Threshold unggah harus di antara kecepatan & burst")
            }

            val burstTime = a.burstTimeSec?.also {
                if (it !in 1..86_400) throw ValidationException("Waktu burst harus 1-86400 detik")
                if (downBurst == null) throw ValidationException("Waktu burst butuh burst diisi lebih dulu")
            }

            val downMin = optionalMbps(a.downMinMbps, "Limit-at unduh")
            val upMin = optionalMbps(a.upMinMbps, "Limit-at unggah")
            requirePaired(upMin, downMin, "Limit-at")
            if (downMin != null && downMin > downMbps) throw ValidationException("Limit-at unduh harus ≤ kecepatan unduh")
            if (upMin != null && upMin > upMbps) throw ValidationException("Limit-at unggah harus ≤ kecepatan unggah")

            val priority = a.priority.also {
                if (it !in 1..8) throw ValidationException("Prioritas QoS harus 1-8")
            }
            val connectionLimit = a.connectionLimit?.also {
                if (it !in 1..1000) throw ValidationException("Batas koneksi harus 1-1000")
            }

            val fupQuotaMb = a.fupQuotaMb
            val fupDown = optionalMbps(a.fupDownMbps, "Kecepatan FUP unduh")
            val fupUp = optionalMbps(a.fupUpMbps, "Kecepatan FUP unggah")
            if (a.fupEnabled) {
                if (fupQuotaMb == null || fupQuotaMb < 1) throw ValidationException("Kuota FUP wajib diisi (≥1 MB) saat FUP aktif")
                if (fupDown == null || fupUp == null) throw ValidationException("Kecepatan FUP unduh & unggah wajib diisi saat FUP aktif")
                if (fupDown > downMbps) throw ValidationException("Kecepatan FUP unduh harus ≤ kecepatan unduh")
                if (fupUp > upMbps) throw ValidationException("Kecepatan FUP unggah harus ≤ kecepatan unggah")
            }

            if (a.serviceTypes.isEmpty()) throw ValidationException("Pilih minimal satu tipe layanan")

            a.billingDayOfMonth?.also {
                if (it !in 1..31) throw ValidationException("Tanggal jatuh tempo harus 1-31")
            }
            a.dueDays?.also { if (it !in 0..90) throw ValidationException("Tenggat invoice harus 0-90 hari") }
            a.graceDays?.also { if (it !in 0..90) throw ValidationException("Masa tenggang harus 0-90 hari") }

            return PlanAttributes(
                name = name,
                description = description,
                price = price,
                downMbps = downMbps,
                upMbps = upMbps,
                downBurstMbps = downBurst,
                upBurstMbps = upBurst,
                downThresholdMbps = downThreshold,
                upThresholdMbps = upThreshold,
                burstTimeSec = burstTime,
                downMinMbps = downMin,
                upMinMbps = upMin,
                priority = priority,
                connectionLimit = connectionLimit,
                fupEnabled = a.fupEnabled,
                fupQuotaMb = if (a.fupEnabled) fupQuotaMb else null,
                fupDownMbps = if (a.fupEnabled) fupDown else null,
                fupUpMbps = if (a.fupEnabled) fupUp else null,
                serviceTypes = a.serviceTypes,
                prorateOnActivation = a.prorateOnActivation,
                billingDayOfMonth = a.billingDayOfMonth,
                dueDays = a.dueDays,
                graceDays = a.graceDays,
                autoIsolir = a.autoIsolir,
                active = a.active,
            )
        }

        private fun mbps(value: Int, label: String): Int {
            if (value !in 1..100_000) throw ValidationException("$label harus 1-100000 Mbps")
            return value
        }

        private fun optionalMbps(value: Int?, label: String): Int? {
            if (value == null) return null
            if (value !in 1..100_000) throw ValidationException("$label harus 1-100000 Mbps")
            return value
        }

        private fun requirePaired(up: Int?, down: Int?, label: String) {
            if ((up == null) != (down == null)) throw ValidationException("$label harus diisi untuk unduh & unggah sekaligus")
        }
    }
}

/**
 * Tipe layanan tempat paket ini bisa ditawarkan. Semua tipe ditegakkan penuh ke RADIUS-pusat:
 * PPPoE/Hotspot lewat username+password, DHCP/Static lewat MAC (`use-radius`, Static memin
 * `Framed-IP-Address`). Grup rate-limit `plan:{id}` dipakai ulang lintas-tipe.
 */
enum class ServiceType { PPPOE, STATIC, HOTSPOT, DHCP }

/**
 * Seluruh atribut paket yang bisa disunting, sebagai satu value object tervalidasi —
 * menghindari fungsi 25-argumen dan menjaga domain tak bergantung pada command layer.
 *
 * Field null pada override billing (`prorateOnActivation`/`billingDayOfMonth`/`dueDays`/
 * `graceDays`/`autoIsolir`) berarti "ikut kebijakan global" (fallback ke BillingProperties).
 */
data class PlanAttributes(
    // Komersial
    val name: String,
    val description: String?,
    val price: BigDecimal,
    // Jaringan — rate dasar
    val downMbps: Int,
    val upMbps: Int,
    // Jaringan — burst (opsional, berpasangan)
    val downBurstMbps: Int? = null,
    val upBurstMbps: Int? = null,
    val downThresholdMbps: Int? = null,
    val upThresholdMbps: Int? = null,
    val burstTimeSec: Int? = null,
    // Jaringan — limit-at (jaminan minimum, opsional berpasangan)
    val downMinMbps: Int? = null,
    val upMinMbps: Int? = null,
    // QoS
    val priority: Int = Plan.DEFAULT_PRIORITY,
    val connectionLimit: Int? = null,
    // FUP (fair-usage)
    val fupEnabled: Boolean = false,
    val fupQuotaMb: Long? = null,
    val fupDownMbps: Int? = null,
    val fupUpMbps: Int? = null,
    // Ketersediaan
    val serviceTypes: Set<ServiceType> = setOf(ServiceType.PPPOE),
    // Override siklus billing (null = ikut global)
    val prorateOnActivation: Boolean? = null,
    val billingDayOfMonth: Int? = null,
    val dueDays: Int? = null,
    val graceDays: Int? = null,
    val autoIsolir: Boolean? = null,
    // Status
    val active: Boolean = true,
)
