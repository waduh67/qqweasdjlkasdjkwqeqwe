package com.duluin.ftth.incident

import java.util.UUID

/**
 * Diterbitkan module incident saat sebuah insiden BARU dibuka oleh korelasi otomatis
 * (bukan saat insiden yang sudah ada diperbarui). Diletakkan di base package sebagai
 * permukaan publik incident supaya module lain — khususnya `notification`, yang
 * menyiarkan pemberitahuan gangguan ke pelanggan terdampak — bisa bereaksi tanpa
 * bergantung pada internal incident dan tanpa ketergantungan balik.
 *
 * Membawa cukup konteks untuk menyusun pesan ([title]/[rootLabel]); daftar pelanggan
 * terdampak dihitung ulang konsumen lewat [IncidentApi.affectedContacts] agar insiden
 * tak perlu menyimpan id pelanggan. Penerbitan in-JVM; konsumen mendengarkan pada fase
 * AFTER_COMMIT agar hanya melihat insiden yang benar-benar ter-commit.
 */
data class IncidentOpened(
    val tenantId: UUID,
    val incidentId: UUID,
    val title: String,
    val rootLabel: String,
    /** Nama [com.duluin.ftth.incident.domain.model.IncidentSeverity], mis. "CRITICAL". */
    val severity: String,
    val affectedCustomerCount: Int,
)
