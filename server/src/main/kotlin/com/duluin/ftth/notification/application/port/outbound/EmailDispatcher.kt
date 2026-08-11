package com.duluin.ftth.notification.application.port.outbound

/**
 * Satu email yang sudah SIAP BERANGKAT: identitas pengirim sudah diresolusi, badan sudah
 * dirakit dalam dua bentuk. Dispatcher tinggal menaruhnya di transport.
 *
 * Bentuk objek, bukan deretan parameter, karena sejak setelan email bisa disetel platform
 * dan ditimpa tenant, yang berpindah tangan bukan lagi sekadar "nama pengirim" melainkan
 * seluruh identitas surat — alamat, nama, alamat balasan — dan tiap penambahan berikutnya
 * akan memaksa mengubah tanda tangan setiap pemanggil.
 */
data class OutboundEmail(
    val to: String,
    val subject: String,
    /**
     * Bagian teks polos. Bukan sekadar cadangan sopan-santun: sebagian klien (dan tak sedikit
     * filter spam) memperlakukan email HTML-tanpa-teks sebagai mencurigakan, dan pesan
     * transaksional seperti kode pemulihan harus tetap terbaca di klien paling sederhana.
     */
    val textBody: String,
    /** Bagian HTML berlogo. Klien yang memblokir gambar tetap membaca isinya utuh. */
    val htmlBody: String,
    /** Nama tampilan pengirim; null = pakai nama bawaan dari konfigurasi. */
    val fromName: String?,
    /** Alamat pengirim; null = pakai alamat bawaan dari konfigurasi. */
    val fromAddress: String?,
    /**
     * Alamat balasan. Diisi saat tenant memakai alamatnya sendiri, supaya balasan pelanggan
     * mendarat di ISP-nya walau relay-nya milik platform.
     */
    val replyTo: String?,
)

/**
 * Port keluar untuk mengirim satu email lewat SMTP platform.
 *
 * Berbeda tajam dari [MessageDispatcher] yang WhatsApp-nya BAWA-SENDIRI per tenant: relay
 * SMTP di sini milik PLATFORM, satu untuk semua tenant. Alasannya praktis — nomor WhatsApp
 * adalah identitas pengirim yang dilihat pelanggan (harus nomor ISP-nya sendiri), sedangkan
 * meminta tiap ISP kecil menyiapkan relay sendiri hanya akan membuat pemulihan password
 * mereka tak pernah aktif. Yang boleh disetel tenant hanyalah IDENTITAS di atas relay itu
 * (nama, alamat, tampilan), bukan relaynya.
 *
 * Karena itu port ini tak menerima gateway apa pun: sambungannya tinggal di adapter, yang
 * membacanya dari setelan platform di DB dengan `spring.mail.*` sebagai cadangan. Kegagalan
 * dilaporkan sebagai [DeliveryOutcome], bukan lemparan, agar pemanggil bisa mencoba kanal
 * lain tanpa membatalkan transaksinya.
 */
interface EmailDispatcher {
    fun send(message: OutboundEmail): DeliveryOutcome
}
