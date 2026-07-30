package com.duluin.ftth.bng.domain.model

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
)
