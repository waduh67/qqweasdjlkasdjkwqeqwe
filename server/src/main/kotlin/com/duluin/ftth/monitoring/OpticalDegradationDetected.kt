package com.duluin.ftth.monitoring

import java.util.UUID

/**
 * Dipublikasikan monitoring saat pemindai prediktif menemukan sebuah ONU yang
 * redamannya memburuk konsisten melampaui ambang dalam jendela pengamatan —
 * sinyal dini kerusakan fisik (konektor kotor, serat tertekuk) yang muncul jauh
 * sebelum pelanggan merasakan gangguan.
 *
 * Module `workorder` mendengarkannya (setelah commit) untuk mengangkat work order
 * preventif ke pelanggan yang bersangkutan. Diletakkan di base package monitoring
 * — permukaan publiknya — karena hanya monitoring yang menerbitkannya (ia yang
 * memiliki deret metrik) dan consumer (`workorder`) memang boleh bergantung
 * padanya. Payload sengaja hanya fakta monitoring (ONU + tren); pemetaan ke
 * pelanggan adalah urusan workorder.
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
