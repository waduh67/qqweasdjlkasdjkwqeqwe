package com.duluin.ftth.simulator.radius

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Setelan BRAS/RADIUS tiruan — sebuah "virtual NAS" yang menutup celah lab: tanpa BRAS
 * hidup, DAE tak menjatuhkan sesi dan sesi hidup tak pernah muncul di `radacct`.
 *
 * Dua tanggung jawab, dua kanal:
 *  - **Data-plane (JDBC → radius-db):** memateri baris `radacct` untuk tiap user ber-otorisasi
 *    (ada `Cleartext-Password` di `radcheck`) lalu menumbuhkan octet-nya seiring waktu, agar
 *    panel sesi & grafik trafik terisi.
 *  - **Kontrol (DAE UDP 3799):** menjawab Disconnect/CoA yang server tembak saat isolir/Reset
 *    Login/ubah kecepatan — Disconnect menutup sesi (aksi jadi SUKSES, bukan FAILED).
 *
 * [url] kosong = sim BRAS nonaktif (mirror [com.duluin.ftth.bng.config.RadiusProperties]): lab
 * yang cuma ingin OLT boleh boot tanpa radius-db.
 */
@ConfigurationProperties(prefix = "ftth.sim.radius")
data class RadiusSimProperties(
    val enabled: Boolean = true,
    /** URL JDBC radius-db. Kosong → sim BRAS nonaktif. */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** IP yang ditulis ke `radacct.nasipaddress` — identitas NAS tiruan ini. */
    val nasIp: String = "10.0.0.1",
    val daeBindAddress: String = "0.0.0.0",
    /** Port DAE (RFC 5176). Server menembak ke sini untuk memutus/ubah sesi. */
    val daePort: Int = 3799,
    /**
     * Shared secret DAE. HARUS sama dengan `coaSecret` NAS yang didaftarkan di app, jika tidak
     * request ditolak (authenticator tak cocok) — persis seperti BRAS sungguhan.
     */
    val daeSecret: String = "testing123",
    /** Selang virtual-NAS merekonsiliasi sesi & menumbuhkan octet. */
    val tickInterval: Duration = Duration.ofSeconds(10),
    /**
     * Jeda "dial ulang": setelah sesi diputus DAE, user offline selama ini sebelum sim
     * menyambungkannya lagi — meniru CPE yang redial. Membuat Pulihkan (restore) otomatis
     * terlihat kembali online, dan lab tak terkuras habis oleh tiap Disconnect.
     */
    val reconnectAfter: Duration = Duration.ofSeconds(45),
)
