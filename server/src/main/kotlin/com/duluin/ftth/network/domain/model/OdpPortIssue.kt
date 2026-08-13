package com.duluin.ftth.network.domain.model

/**
 * Beda antara KERTAS dan SERAT pada sebuah port ODP.
 *
 * Dua catatan hidup berdampingan di sistem ini dan keduanya dibuat manusia di
 * waktu yang berbeda: pemasangan ONU dicatat petugas layanan ("pelanggan Budi,
 * ODP-XXX-01 port 3"), sedangkan sambungan serat dicatat teknisi yang membuka
 * kotaknya ("kaki 3 dilas ke core 5 kabel drop"). Selama keduanya cocok, tak ada
 * yang perlu diributkan.
 *
 * Yang mahal adalah saat keduanya berselisih, sebab selisihnya tak pernah
 * mengumumkan diri: sistem tetap menggambar peta yang rapi, tagihan tetap
 * terbit, dan yang pertama tahu adalah teknisi yang berdiri di depan kotak
 * sambil mencabut kaki satu per satu — atau pelanggan yang ikut mati saat
 * tetangganya diperbaiki. Menamai tiap bentuk selisihnya di sini membuatnya
 * bisa ditampilkan sebelum ada yang berangkat ke lokasi.
 */
enum class OdpPortIssue(val label: String, val detail: String) {

    /** Ada ONU di catatan, tak ada kaki yang menyalurkannya. */
    PORT_WITHOUT_FIBER(
        "Tercatat, seratnya belum",
        "Pelanggan tercatat di port ini tapi tak ada kaki splitter yang dilas untuknya. " +
            "Entah pemasangannya belum tuntas, entah teknisinya lupa membukukan sambungan — " +
            "yang pasti port ini akan terlihat penuh padahal fisiknya masih kosong.",
    ),

    /** Kaki sudah sampai ke rumah orang, catatan portnya kosong. */
    FIBER_WITHOUT_PORT(
        "Tersambung, tak tercatat",
        "Kaki splitter untuk port ini sudah dilas sampai ke rumah pelanggan, tapi tak ada ONU " +
            "yang tercatat di sini. Port yang sebenarnya terpakai akan ditawarkan lagi ke " +
            "pemasangan berikutnya, dan yang datang menemukan lubangnya sudah terisi.",
    ),

    /** Kertas bilang pelanggan A, serat sampai ke rumah pelanggan B. */
    PORT_MISMATCH(
        "Catatan & serat beda orang",
        "Catatan menyebut satu pelanggan, seratnya bermuara di rumah pelanggan lain. " +
            "Selama tak diluruskan, memutus port ini saat berhenti berlangganan justru " +
            "mematikan orang yang masih bayar.",
    ),

    /** Kaki diarahkan balik ke kabel yang menyuapi input modulnya sendiri. */
    LEG_BACKWARD(
        "Kaki berbalik ke penyuapnya",
        "Kaki ini dilas ke serat kabel yang justru MENYUAPI input splitternya. Cahaya yang " +
            "sudah dibagi pulang lewat serat tetangganya di selubung yang sama, jadi tak ada " +
            "yang terlayani — dan dua core habis percuma. Lepas sambungannya, lalu sambungkan " +
            "kaki ini ke core kabel drop menuju rumah pelanggan.",
    ),

    /** ONU terdaftar di kotak ini tanpa nomor port sama sekali. */
    PORT_UNRECORDED(
        "Belum dapat nomor port",
        "ONU terdaftar di ODP ini tapi tak menyebut port mana. Barangnya sudah di lokasi, " +
            "pembukuannya belum tuntas — dan selama begitu, ia tak ikut terhitung saat " +
            "kapasitas kotak ini dilaporkan.",
    ),
}
