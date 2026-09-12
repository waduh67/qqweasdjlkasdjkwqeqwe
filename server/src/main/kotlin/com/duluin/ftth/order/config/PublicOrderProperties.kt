package com.duluin.ftth.order.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Setelan pintu pemesanan publik (tanpa JWT).
 *
 * Angka-angka rem di sini SENGAJA terpisah dari `ftth.throttle`: yang di sana menjaga endpoint
 * yang menerima RAHASIA (masuk, kode pemulihan) dan dihitung di memori, sedangkan yang di sini
 * menjaga ANTREAN KERJA operator dan dihitung di database. Menyatukannya berarti menyetel
 * ulang batas login ikut mengubah batas pemesanan — dua hal yang tak pernah punya alasan sama.
 */
@ConfigurationProperties(prefix = "ftth.order.public")
data class PublicOrderProperties(
    /** Matikan hanya untuk uji beban. Di produksi biarkan menyala. */
    val throttleEnabled: Boolean = true,
    /**
     * Pesanan baru per alamat IP. Longgar untuk orang sungguhan (satu orang memesan sekali,
     * paling banter mengulang karena salah isi), ketat untuk skrip.
     */
    val submitPerIp: Quota = Quota(5, Duration.ofHours(1)),
    /**
     * Pesanan baru per TENANT, tanpa memandang asal IP. Inilah satu-satunya rem yang masih
     * berlaku saat penyerang memakai kolam IP (botnet, proxy residensial) sehingga batas per-IP
     * tak pernah tersentuh. Angkanya ditaruh di atas laju wajar ISP kecil-menengah supaya
     * kampanye promo tidak ikut tertahan; kalau tersentuh, itu memang layak diperiksa manusia.
     */
    val submitPerTenant: Quota = Quota(120, Duration.ofHours(1)),
    /** Penelusuran status per IP. Lebih longgar: pelanggan yang cemas memang me-refresh. */
    val trackPerIp: Quota = Quota(30, Duration.ofHours(1)),
    /**
     * Baris rem yang jendelanya sudah lewat dihapus setelah selang ini. Bukan nol supaya
     * penyapuan tak pernah menghapus jendela yang masih dipakai permintaan berjalan.
     */
    val throttleRetention: Duration = Duration.ofHours(6),
) {
    data class Quota(val limit: Int, val window: Duration) {
        init {
            require(limit > 0) { "limit throttle pesanan publik harus > 0" }
            require(!window.isZero && !window.isNegative) { "window throttle pesanan publik harus > 0" }
        }
    }
}
