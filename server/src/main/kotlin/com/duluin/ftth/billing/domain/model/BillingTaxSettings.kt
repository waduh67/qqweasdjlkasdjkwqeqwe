package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Setelan pajak & kontribusi regulatoris satu tenant (satu baris per tenant).
 *
 * Dua hal berbeda sifatnya digabung karena keduanya "kebijakan pajak tenant":
 *
 *  - **PPN** ([ppnEnabled]/[ppnRate]) — komponen yang DITAGIHKAN ke pelanggan: saat aktif,
 *    penerbit tagihan menambahkan `dasar × ppnRate` ke atas nilai langganan (lihat
 *    `InvoiceGenerator`). Nonaktif → tagihan tanpa PPN (perilaku lama).
 *  - **BHP/USO** ([regulatoryEnabled]/[bhpRate]/[usoRate]) — kewajiban LAPORAN tenant, BUKAN
 *    ditagih ke pelanggan. Dihitung dari peredaran bruto (pendapatan tertagih sebelum PPN)
 *    hanya untuk KPI/laporan (lihat `TaxService.obligation`). BHP Telekomunikasi & Kontribusi
 *    USO adalah dua PNBP terpisah, jadi tarifnya dipisah walau dilaporkan bersama.
 *
 * Default aman meniru `NotificationSettings`: kedua fitur MATI, tapi tarif sudah terisi angka
 * lazim Indonesia (PPN 11%, BHP 0.5%, USO 1.25%) agar tenant tinggal menyalakan. Semua tarif
 * adalah PECAHAN di [0,1) (0.11 = 11%), bukan persen.
 */
class BillingTaxSettings private constructor(
    val id: UUID,
    val tenantId: UUID,
    ppnEnabled: Boolean,
    ppnRate: BigDecimal,
    regulatoryEnabled: Boolean,
    bhpRate: BigDecimal,
    usoRate: BigDecimal,
) {
    var ppnEnabled: Boolean = ppnEnabled
        private set

    var ppnRate: BigDecimal = ppnRate
        private set

    /** Saklar pelaporan BHP/USO; mati = kewajiban dianggap nol (tak dihitung/ditampilkan). */
    var regulatoryEnabled: Boolean = regulatoryEnabled
        private set

    var bhpRate: BigDecimal = bhpRate
        private set

    var usoRate: BigDecimal = usoRate
        private set

    fun update(
        ppnEnabled: Boolean,
        ppnRate: BigDecimal,
        regulatoryEnabled: Boolean,
        bhpRate: BigDecimal,
        usoRate: BigDecimal,
    ) {
        this.ppnEnabled = ppnEnabled
        this.ppnRate = validateRate(ppnRate, "Tarif PPN")
        this.regulatoryEnabled = regulatoryEnabled
        this.bhpRate = validateRate(bhpRate, "Tarif BHP")
        this.usoRate = validateRate(usoRate, "Tarif USO")
    }

    /** Tarif PPN efektif untuk penerbitan tagihan: [ppnRate] bila aktif, else null (tanpa PPN). */
    fun effectivePpnRate(): BigDecimal? = if (ppnEnabled) ppnRate else null

    /** Gabungan tarif kontribusi (BHP + USO) bila pelaporan aktif, else nol. */
    fun regulatoryRate(): BigDecimal = if (regulatoryEnabled) bhpRate.add(usoRate) else BigDecimal.ZERO

    companion object {
        val DEFAULT_PPN_RATE: BigDecimal = BigDecimal("0.1100")
        val DEFAULT_BHP_RATE: BigDecimal = BigDecimal("0.0050")
        val DEFAULT_USO_RATE: BigDecimal = BigDecimal("0.0125")

        /** Setelan bawaan tenant yang belum pernah menyetel — kedua fitur MATI, tarif lazim terisi. */
        fun defaultFor(tenantId: UUID): BillingTaxSettings = BillingTaxSettings(
            id = UuidV7.generate(),
            tenantId = tenantId,
            ppnEnabled = false,
            ppnRate = DEFAULT_PPN_RATE,
            regulatoryEnabled = false,
            bhpRate = DEFAULT_BHP_RATE,
            usoRate = DEFAULT_USO_RATE,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            ppnEnabled: Boolean,
            ppnRate: BigDecimal,
            regulatoryEnabled: Boolean,
            bhpRate: BigDecimal,
            usoRate: BigDecimal,
        ): BillingTaxSettings =
            BillingTaxSettings(id, tenantId, ppnEnabled, ppnRate, regulatoryEnabled, bhpRate, usoRate)

        /** Tarif wajib pecahan di [0,1) — mis. 0.11. Dinormalkan skala 4. */
        private fun validateRate(rate: BigDecimal, label: String): BigDecimal {
            if (rate.signum() < 0) throw ValidationException("$label tidak boleh negatif")
            if (rate >= BigDecimal.ONE) throw ValidationException("$label harus di bawah 1 (mis. 0.11 untuk 11%)")
            return rate.setScale(4, RoundingMode.HALF_UP)
        }
    }
}
