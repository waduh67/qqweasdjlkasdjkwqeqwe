package com.duluin.ftth.billing.application.port.inbound

import java.math.BigDecimal
import java.time.LocalDate

/** Baca & ubah setelan pajak tenant (PPN + kontribusi BHP/USO). */
interface ManageTaxSettingsUseCase {
    fun get(): TaxSettingsView
    fun update(command: UpdateTaxSettingsCommand): TaxSettingsView
}

/** Ringkasan kewajiban pajak/kontribusi untuk sebuah rentang — KPI halaman Tagihan. */
interface ViewTaxObligationUseCase {
    /**
     * PPN terkumpul + kewajiban BHP/USO untuk tagihan LUNAS yang `paidAt`-nya di [from]..[to]
     * (inklusif). Tanpa argumen, controller memakai tahun kalender berjalan.
     */
    fun obligation(from: LocalDate, to: LocalDate): TaxObligationView
}

/** Setelan pajak tenant (tarif sebagai pecahan, mis. 0.1100 untuk 11%). */
data class TaxSettingsView(
    val ppnEnabled: Boolean,
    val ppnRate: BigDecimal,
    val regulatoryEnabled: Boolean,
    val bhpRate: BigDecimal,
    val usoRate: BigDecimal,
)

/** Perintah ubah setelan pajak; semua tarif pecahan di [0,1). */
data class UpdateTaxSettingsCommand(
    val ppnEnabled: Boolean,
    val ppnRate: BigDecimal,
    val regulatoryEnabled: Boolean,
    val bhpRate: BigDecimal,
    val usoRate: BigDecimal,
)

/**
 * Ringkasan pajak satu tenant pada satu rentang (dihitung dari tagihan LUNAS).
 *
 * - [ppnCollected]        Σ PPN yang tertagih (pass-through ke negara, bukan pendapatan tenant).
 * - [regulatoryRevenueBase] peredaran bruto = pendapatan tertagih SEBELUM PPN (dasar BHP/USO).
 * - [bhpAmount]/[usoAmount]/[regulatoryObligation] kewajiban BHP, USO, dan totalnya — nol bila
 *   pelaporan BHP/USO nonaktif. Semua nilai uang skala 2.
 */
data class TaxObligationView(
    val from: LocalDate,
    val to: LocalDate,
    val ppnEnabled: Boolean,
    val ppnRate: BigDecimal,
    val ppnCollected: BigDecimal,
    val regulatoryEnabled: Boolean,
    val bhpRate: BigDecimal,
    val usoRate: BigDecimal,
    val regulatoryRevenueBase: BigDecimal,
    val bhpAmount: BigDecimal,
    val usoAmount: BigDecimal,
    val regulatoryObligation: BigDecimal,
)
