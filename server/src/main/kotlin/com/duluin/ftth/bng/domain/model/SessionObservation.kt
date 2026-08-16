package com.duluin.ftth.bng.domain.model

import java.time.Instant

/**
 * Satu observasi sesi PPPoE untuk sebuah akun pada satu waktu — tipe netral yang
 * menjembatani dua sumber ke jalur serap yang SAMA ([BngSessionIngestService]):
 *  - server membaca `radacct` langsung dari radius-db platform (RADIUS-as-a-service), dan
 *  - jalur lama collector (event `BngSessionsReported`) yang dipetakan ke sini.
 *
 * [username] sudah BARE (tanpa prefix `{kodeTenant}:` — dikupas pembaca radacct) agar cocok
 * dengan [SubscriberAccess.username] yang disimpan polos. Octet KUMULATIF sejak sesi mulai,
 * mengikuti semantik RADIUS: [inOctets] = unggah pelanggan (Acct-Input), [outOctets] = unduh
 * (Acct-Output); laju Mbps dihitung consumer dari selisih antar-observasi.
 */
data class SessionObservation(
    val username: String,
    val online: Boolean,
    val nasIp: String?,
    val framedIp: String?,
    val sessionId: String?,
    val callingStationId: String?,
    val uptimeSeconds: Long?,
    val inOctets: Long?,
    val outOctets: Long?,
    /**
     * Kapan penghitung octet ini BENAR menurut NAS (`radacct.acctupdatetime`) — bukan kapan
     * kita membacanya. Dua jam yang berbeda, dan bedanya menentukan benar-tidaknya laju Mbps:
     * BRAS memperbarui `radacct` hanya setiap Interim-Update (bawaan Mikrotik 5 menit),
     * sedangkan poller membaca tiap 30 detik. Memakai waktu baca membuat sebagian besar
     * cuplikan kembar persis (laju 0) lalu satu cuplikan membagi pertambahan 5 menit dengan
     * jarak 30 detik (laju ~10× lipat) — grafik bergerigi yang tak pernah menunjukkan angka
     * sebenarnya. Dengan waktu NAS, cuplikan kembar runtuh jadi satu baris (index unik
     * `uq_accounting_record_point`) dan selisihnya membentang persis satu interim.
     *
     * Null bila sumbernya tak melaporkannya (jalur collector, atau router yang tak memasang
     * interim-update) → penyerap jatuh kembali ke waktu baca.
     */
    val countersAt: Instant? = null,
)
