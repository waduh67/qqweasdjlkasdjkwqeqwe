package com.duluin.ftth.bng.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Koneksi ke `radius-db` platform yang dipegang SERVER (RADIUS-as-a-service): satu
 * FreeRADIUS/Postgres pusat, bukan per-tenant. Berbeda dari model lama yang menaruh URL
 * JDBC di tiap baris `nas` (bocor ke tenant) — di sini kredensialnya rahasia platform,
 * di-inject lewat environment di prod.
 *
 * [url] KOSONG = provisioning RADIUS server-side nonaktif. Ini sengaja: dev/test boleh
 * boot tanpa radius-db (aksi provisioning menumpuk PENDING, dijalankan begitu radius-db
 * dikonfigurasi) — tak ada datasource kedua yang wajib untuk menjalankan aplikasi.
 */
@ConfigurationProperties(prefix = "ftth.radius")
data class RadiusProperties(
    /** URL JDBC radius-db platform. Kosong → provisioning server-side dimatikan. */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** Sakelar eksplisit — matikan provisioning walau url terisi (mis. saat migrasi). */
    val enabled: Boolean = true,
    /**
     * Alamat publik FreeRADIUS pusat yang TENANT arahkan router-nya (nilai `address=` di
     * `/radius` Mikrotik) — BUKAN [url] JDBC. KOSONG = UI menandai "belum dikonfigurasi
     * platform" alih-alih menebak host. Isi IP/host publik VPS via env di prod.
     */
    val publicHost: String = "",
    /** Port auth RADIUS yang router tembak (RFC 2865). */
    val authPort: Int = 1812,
    /** Port accounting RADIUS yang router tembak (RFC 2866). */
    val acctPort: Int = 1813,
    /** Port DAE/CoA (RFC 5176) yang SERVER tembak balik ke BRAS. */
    val coaPort: Int = 3799,
    /**
     * Sisa kecepatan pelanggan TERISOLIR (format Mikrotik-Rate-Limit `up/down`). Sengaja
     * bukan nol: isolir di sini "halaman tagihan", bukan kabel dicabut — pelanggan harus
     * masih sanggup memuat halaman itu dan menekan tombol bayar. Terlalu kecil (mis. 64k)
     * membuat halaman gagal termuat dan pelanggan menelepon; terlalu besar membuat isolir
     * terasa seperti internet yang cuma lemot.
     */
    val isolirRateLimit: String = "1M/1M",
    /**
     * Nama address-list Mikrotik yang IP pelanggan terisolir dimasukkan ke dalamnya (VSA
     * `Mikrotik-Address-List`). Router memakai daftar ini sebagai satu-satunya pegangan untuk
     * melempar mereka ke halaman tagihan dan memblokir sisanya — nilai di sini WAJIB sama
     * dengan yang tertulis di aturan firewall router (lihat kartu panduan isolir di UI).
     */
    val isolirAddressList: String = "isolir",
    /** Ukuran pool koneksi radius-db (provisioning jarang, kecil sudah cukup). */
    val maxPoolSize: Int = 5,
    /** Selang worker mengklaim aksi provisioning tertunda dari antrean. */
    val dispatchInterval: Duration = Duration.ofSeconds(10),
    /** Selang poller membaca sesi hidup `radacct` platform per tenant (jalur-baca server-side). */
    val sessionPollInterval: Duration = Duration.ofSeconds(30),
    /** Berapa aksi diklaim per putaran per-tenant — batas agar satu tenant tak memonopoli. */
    val batchSize: Int = 100,
    /**
     * Batas usia aksi yang gagal transien (mis. radius-db sesaat mati) sebelum menyerah.
     * Selama masih di bawah ini, aksi tetap PENDING dan diulang putaran berikutnya
     * (degradasi anggun); melewatinya → ditandai FAILED agar tak mengulang selamanya.
     */
    val maxRetry: Duration = Duration.ofHours(1),
)
