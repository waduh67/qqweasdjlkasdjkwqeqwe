package com.duluin.ftth.simulator.radius

/**
 * Kendali sesi yang dipanggil [DaeResponder] saat menerima Disconnect/CoA. Diabstraksi jadi
 * antarmuka agar responder DAE (protokol murni, tanpa DB) bisa diuji terhadap kendali palsu,
 * sementara implementasi nyata [VirtualNasEngine] menyentuh radacct.
 */
interface NasSessionControl {

    /**
     * Tutup sesi hidup milik [username] (dibatasi [acctSessionId] bila diberi). Kembalikan true
     * bila ada ≥1 sesi yang ditutup → DAE ACK; false bila tak ada sesi cocok → DAE NAK 503
     * ("Session-Context-Not-Found"), yang app perlakukan sebagai "sudah tercapai".
     */
    fun disconnect(username: String, acctSessionId: String?): Boolean

    /**
     * Terapkan CoA (ubah kecepatan). Virtual-NAS tak benar-benar membentuk trafik, jadi cukup
     * mengakui bila [username] punya sesi hidup — cukup untuk membuat aksi CoA app SUKSES.
     */
    fun changeRate(username: String, acctSessionId: String?): Boolean
}
