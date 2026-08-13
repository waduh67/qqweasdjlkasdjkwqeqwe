package com.duluin.ftth.monitoring.application.port.inbound

import com.duluin.ftth.monitoring.domain.model.AlarmKind

/**
 * Ambang alarm milik tenant: lihat semua jenis beserta setelan yang berlaku, geser
 * ambangnya, atau kembalikan ke bawaan.
 *
 * Kenapa perlu disetel sendiri: jaringan tiap ISP tidak seragam. Yang jalurnya
 * panjang sampai kampung sebelah wajar hidup di −25 dBm dan akan tenggelam oleh
 * peringatan kalau memakai ambang bawaan; yang ONU-nya menumpuk dekat OLT justru
 * kelewat terang. Ambang yang salah bukan cuma berisik — operator berhenti
 * membaca alarm, lalu gangguan sungguhan lewat tanpa ada yang menengok.
 *
 * Daftarnya selalu memuat SEMUA jenis, termasuk yang belum pernah disetel tenant
 * ini (ditampilkan dengan nilai bawaan, `customised = false`). Layar setelan yang
 * kosong hanya karena tabelnya kosong akan menyesatkan: pemantauannya sebenarnya
 * jalan, cuma tak ada barisnya.
 */
interface ManageAlarmRuleUseCase {

    fun list(): List<AlarmRuleView>

    fun update(kind: AlarmKind, command: UpdateAlarmRuleCommand): AlarmRuleView

    /** Buang setelan tenant untuk satu jenis; kembali mengikuti bawaan sistem. */
    fun resetToDefault(kind: AlarmKind): AlarmRuleView
}

data class UpdateAlarmRuleCommand(
    val enabled: Boolean,
    val warningThreshold: Double?,
    val criticalThreshold: Double?,
)

data class AlarmRuleView(
    val kind: String,
    val description: String,
    val entityType: String,
    val enabled: Boolean,
    val warningThreshold: Double?,
    val criticalThreshold: Double?,
    val defaultWarningThreshold: Double?,
    val defaultCriticalThreshold: Double?,
    val defaultSeverity: String,
    /**
     * `LOWER_IS_WORSE` / `HIGHER_IS_WORSE`, atau `null` untuk jenis biner yang tak
     * punya ambang sama sekali (LOS, OLT tak terjangkau). Layar memakainya untuk
     * menulis arah perbandingannya alih-alih menebak dari tanda minus.
     */
    val direction: String?,
    val unit: String?,
    /** Sudah menyimpang dari bawaan? Penanda "ini hasil setelan orang, bukan pabrikan". */
    val customised: Boolean,
    /** Yang perlu diketahui sebelum menggeser ambangnya — batas fisik perangkat. */
    val guidance: String,
    /**
     * Berapa alarm jenis ini yang sedang terbuka. Ditaruh di layar setelan supaya
     * operator melihat akibat langsung dari yang hendak diubahnya — "1.200 terbuka"
     * biasanya berarti ambangnya yang keliru, bukan jaringannya yang rusak.
     */
    val openAlarmCount: Int,
)
