package com.duluin.ftth.notification.application.service

/**
 * Contoh isi surat untuk pratinjau layar setelan dan untuk tombol "kirim email uji".
 *
 * Dipakai BERSAMA sisi platform dan sisi tenant supaya keduanya menilai bungkus yang sama;
 * pratinjau yang isinya berbeda-beda membuat orang membandingkan hal yang salah saat
 * mencocokkan warna atau posisi logo.
 *
 * Isinya sengaja menyerupai pemberitahuan sungguhan — ada paragraf, ada baris rincian, ada
 * satu URL — karena justru di situlah cacat bungkus terlihat: tautan yang tak terbaca di atas
 * warna aksen gelap, atau footer yang menempel ke badan pesan.
 */
internal object EmailPreviewSample {

    const val SUBJECT = "Contoh pemberitahuan email"

    const val TEST_SUBJECT = "Email uji dari NetOps Console"

    val BODY = """
        Halo Budi Santoso,

        Ini contoh tampilan email pemberitahuan yang diterima pelanggan Anda. Isi
        pesannya dirangkai otomatis oleh sistem sesuai peristiwa yang terjadi; yang
        disetel di layar ini adalah bungkusnya — logo, warna aksen, tanda tangan, dan
        footer di bawah.

        Nomor tagihan : INV-2026-000123
        Jatuh tempo   : 20 Agustus 2026
        Jumlah        : Rp 250.000

        Pembayaran bisa dilakukan lewat tautan berikut:
        https://contoh.example.com/bayar/INV-2026-000123
    """.trimIndent()

    val TEST_BODY = """
        Email ini dikirim dari layar setelan email untuk menguji sambungan SMTP.

        Kalau Anda membacanya, berarti server SMTP menerima dan meneruskan surat dari
        aplikasi dengan benar. Periksa juga apakah surat ini masuk ke kotak masuk atau
        ke folder spam — alamat pengirim yang domainnya tidak mengizinkan relay ini
        lewat SPF/DKIM biasanya berakhir di spam meski pengirimannya sendiri berhasil.

        Nomor tagihan : INV-2026-000123
        Jatuh tempo   : 20 Agustus 2026
        Jumlah        : Rp 250.000

        Contoh tautan: https://contoh.example.com/bayar/INV-2026-000123
    """.trimIndent()
}
