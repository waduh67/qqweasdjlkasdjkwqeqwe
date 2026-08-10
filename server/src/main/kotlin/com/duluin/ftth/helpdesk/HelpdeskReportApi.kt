package com.duluin.ftth.helpdesk

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Kontrak publik module helpdesk untuk PELAPORAN se-tenant (modul `reporting`).
 *
 * Sengaja terpisah dari [HelpdeskApi]: kontrak itu punya invarian keamanan "semua metode
 * ter-scope ke satu pelanggan" karena dipakai portal self-service, dan menyelipkan metode
 * se-tenant di sana akan menghapus jaminan itu dari pembacanya. Di sini kebalikannya yang
 * berlaku — angkanya memang lintas-pelanggan, dan pemanggilnya digating izin manajerial
 * `reporting.report.view` di controller.
 */
interface HelpdeskReportApi {

    /**
     * Kinerja meja bantuan untuk rentang [from]..[to] (inklusif, zona server). Helpdesk tetap
     * satu-satunya yang menyentuh tabel tiket (RLS per tenant aktif).
     */
    fun supportReport(from: LocalDate, to: LocalDate): HelpdeskSupportReport
}

/**
 * Kinerja meja bantuan satu tenant pada satu rentang.
 *
 * [openedCount]/[openedByCategory] = beban MASUK (by `openedAt`), potret keluhan apa yang
 * sedang banyak. [resolvedCount] = yang TUNTAS di rentang (by `resolvedAt`) — sengaja bukan
 * himpunan yang sama, karena tiket boleh melewati batas periode.
 *
 * [avgFirstResponseHours] dihitung atas tiket yang dibuka di rentang DAN sudah dijawab; yang
 * belum dijawab tak punya angka untuk dirata-ratakan (dan terhitung di [responseBreachedCount]
 * bila tenggatnya sudah lewat). [avgResolutionHours] = MTTR atas tiket yang tuntas di rentang.
 * Keduanya `null` bila tak ada data — bukan nol.
 *
 * [responseBreachedCount] = tiket rentang ini yang jawaban pertamanya telat (atau belum ada
 * padahal tenggat sudah lewat). [resolutionBreachedCount] = tiket yang tuntas melewati tenggat
 * penyelesaian. [slaCompliancePercent] = porsi tiket tuntas yang TEPAT waktu (skala 2); `null`
 * bila tak ada tiket tuntas.
 */
data class HelpdeskSupportReport(
    val openedCount: Int,
    val resolvedCount: Int,
    val openedByCategory: Map<String, Int>,
    val avgFirstResponseHours: Double?,
    val avgResolutionHours: Double?,
    val responseBreachedCount: Int,
    val resolutionBreachedCount: Int,
    val slaCompliancePercent: BigDecimal?,
)
