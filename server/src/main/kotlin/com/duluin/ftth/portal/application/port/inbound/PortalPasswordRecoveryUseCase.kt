package com.duluin.ftth.portal.application.port.inbound

/**
 * Pemulihan password portal ("lupa password") tanpa perlu menghubungi ISP.
 *
 * Dua langkah: minta kode, lalu tukar kode dengan password baru. Keduanya dipanggil oleh
 * orang yang BELUM masuk, jadi tak ada tenant context maupun identitas terpercaya — itulah
 * yang membentuk hampir semua keputusan desainnya.
 */
interface PortalPasswordRecoveryUseCase {

    /**
     * Kirim kode pemulihan ke kontak terdaftar pelanggan.
     *
     * TIDAK PERNAH memberi tahu pemanggil apakah identitasnya dikenal, ke mana kode dikirim,
     * atau apakah pengirimannya berhasil — bahkan tidak dalam bentuk tersamar seperti
     * "j***@gmail.com". Halaman ini terbuka untuk umum, dan jawaban yang membedakan
     * "dikenal" dari "tidak" akan mengubahnya jadi alat pesaing untuk memetakan basis
     * pelanggan sebuah ISP satu per satu.
     *
     * [tenantSlug] hanya menyaring bila memang diketahui (mis. dari tautan ISP); normalnya null.
     */
    fun requestReset(identifier: String, tenantSlug: String? = null)

    /**
     * Tukar kode dengan password baru. Berbeda dari [requestReset], di sini kegagalan
     * DILAPORKAN apa adanya (kode salah/kedaluwarsa) — pemanggilnya sudah memegang kode yang
     * hanya sampai ke pemilik kontak, dan tanpa umpan balik yang jujur pelanggan tak punya
     * cara tahu ia harus meminta kode baru.
     */
    fun completeReset(command: PortalResetPasswordCommand)
}

/**
 * @param identifier identitas yang sama seperti saat meminta kode — kode terikat padanya,
 *   sehingga kode yang bocor tak bisa dipakai atas nama orang lain.
 */
data class PortalResetPasswordCommand(
    val identifier: String,
    val code: String,
    val newPassword: String,
)
