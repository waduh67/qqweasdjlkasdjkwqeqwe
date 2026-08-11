package com.duluin.ftth.network

import java.util.UUID

/**
 * Tiket pekerjaan lapangan, dilihat dari sudut pandang serat.
 *
 * Sebuah sambungan menyimpan work order tempat ia dibukukan, jadi module network
 * perlu dua hal dari module workorder: memastikan nomor tiketnya benar-benar ada
 * sebelum seratnya tercatat tersambung, dan menempelkan sebaris jejak ke
 * linimasa tiketnya.
 *
 * Arah dependensinya dibalik — persis alasan yang sama dengan [OdpUsageProbe].
 * Network tak boleh bergantung pada workorder, sebab workorder sudah bergantung
 * pada customer dan customer pada network; menambah panah network→workorder
 * menutup lingkaran itu, dan Spring Modulith menolaknya. Maka networklah yang
 * MENDEKLARASIKAN apa yang ia butuhkan, dan workorder yang mengisinya.
 *
 * Sengaja sesempit ini: network tak perlu tahu lifecycle tiket, penugasan
 * teknisi, atau persetujuan penyelia. Yang ia butuhkan cuma "tiket ini ada?"
 * dan "catat ini di sana".
 */
interface SpliceWorkOrderPort {

    /** `null` bila tiketnya tak ada — pemanggilnya yang memutuskan itu galat atau bukan. */
    fun findWorkOrder(id: UUID): SpliceWorkOrderRef?

    /**
     * Menempelkan satu baris peristiwa lapangan ke linimasa tiket.
     *
     * Tak melempar apa pun bila tiketnya keburu lenyap: pekerjaan seratnya sudah
     * tersimpan dan itulah yang bernilai. Menggagalkan transaksinya demi sebaris
     * catatan justru menghapus yang penting.
     */
    fun noteSpliceActivity(workOrderId: UUID, message: String, actorId: UUID?)
}

/** Sekadar cukup untuk menyebut tiketnya di layar sambungan: kodenya. */
data class SpliceWorkOrderRef(
    val id: UUID,
    val code: String,
    val title: String,
    val open: Boolean,
)
