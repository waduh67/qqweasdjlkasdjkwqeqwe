package com.duluin.ftth.incident.application.port.inbound

import java.util.UUID

/**
 * Pandangan insiden hasil korelasi alarm hidup.
 *
 * Module `incident` tidak menyimpan tabel sendiri (belum): ia menyusun jawaban
 * dari `monitoring` (alarm hidup), `network` (topologi hulu), dan `customer`
 * (ONU → pelanggan). Nilainya: mengubah banjir alarm sejenis menjadi sedikit
 * insiden ber-akar-masalah — 12 ONU LOS di bawah satu ODC adalah SATU insiden,
 * bukan 12 baris yang membuat operator menyerah membacanya.
 */
interface IncidentQuery {

    /** Insiden aktif, dikelompokkan menurut akar masalah, terparah lebih dulu. */
    fun activeIncidents(): List<IncidentView>
}

data class IncidentView(
    /**
     * Kunci stabil dari akar masalahnya, "<TIPE>:<uuid>". Satu akar = satu insiden;
     * dipakai UI untuk dedup dan (nanti) sebagai jangkar persistensi lifecycle.
     */
    val key: String,
    /** Tipe akar masalah: OLT, ODC, ODP, ONU, atau COLLECTOR. */
    val rootType: String,
    val rootId: UUID,
    val rootLabel: String,
    /** Keparahan tertinggi di antara anggotanya. */
    val severity: String,
    val title: String,
    val alarmCount: Int,
    val affectedCustomerCount: Int,
    val members: List<IncidentAlarm>,
)

/** Satu alarm hidup yang menjadi anggota sebuah insiden. */
data class IncidentAlarm(
    val entityType: String,
    val entityId: UUID,
    val kind: String,
    val severity: String,
    val label: String,
)
