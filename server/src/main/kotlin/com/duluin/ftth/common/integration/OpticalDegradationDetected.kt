package com.duluin.ftth.common.integration

import java.util.UUID

/**
 * Dipublikasikan monitoring saat pemindai prediktif menemukan sebuah ONU yang
 * redamannya memburuk konsisten melampaui ambang dalam jendela pengamatan —
 * sinyal dini kerusakan fisik (konektor kotor, serat tertekuk) yang muncul jauh
 * sebelum pelanggan merasakan gangguan.
 *
 * Module `workorder` mendengarkannya (setelah commit) untuk mengangkat work order
 * preventif ke pelanggan yang bersangkutan.
 *
 * Diletakkan di shared kernel `common` (bukan di base package monitoring) supaya
 * consumer `workorder` tidak perlu bergantung pada module `monitoring` — sama
 * seperti [com.duluin.ftth.common.audit.AuditTrailEvent]. Ini penting karena
 * monitoring kini justru bergantung pada `workorder` (menebak pemilik ONU liar
 * dari WO PSB terbuka); menaruh event di sini memutus ketergantungan dua-arah
 * yang akan menjadi siklus module. Payload sengaja hanya fakta monitoring (ONU +
 * tren); pemetaan ke pelanggan adalah urusan workorder.
 */
data class OpticalDegradationDetected(
    val tenantId: UUID,
    val onuId: UUID,
    /** Kemiringan redaman per hari; negatif berarti memburuk. */
    val trendDbPerDay: Double,
    val averageRxPowerDbm: Double?,
    val minRxPowerDbm: Double?,
    val samples: Int,
)
