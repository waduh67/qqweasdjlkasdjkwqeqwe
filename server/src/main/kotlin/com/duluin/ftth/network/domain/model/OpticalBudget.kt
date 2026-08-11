package com.duluin.ftth.network.domain.model

/**
 * Angka fisika untuk anggaran redaman jalur OLT→pelanggan.
 *
 * Semuanya konstanta komponen, bukan setelan per tenant: serat G.652.D punya
 * redaman yang sama di mana pun ia dipasang, dan kelas daya GPON ditetapkan
 * ITU-T G.984.2, bukan oleh ISP yang memakainya. Menaruhnya di domain — bukan di
 * tabel konfigurasi — menutup pintu "diperlonggar sedikit biar lolos", yang di
 * lapangan berarti pelanggan ujung yang los tiap kali hujan.
 */
object OpticalBudget {

    /**
     * Redaman serat per kilometer pada 1310 nm — panjang gelombang ARAH UNGGAH,
     * yang lebih rugi daripada 1490 nm arah unduh (0,21 dB/km). Anggaran dihitung
     * dari sisi yang paling boros supaya jalur yang lolos hitungan pasti lolos di
     * kedua arah, bukan cuma di arah yang enak.
     */
    const val FIBER_LOSS_DB_PER_KM = 0.35

    /**
     * Anggaran daya kelas B+ (ITU-T G.984.2): 28 dB antara port PON dan ONU.
     * Kelas C+ memberi 32 dB, tapi B+ yang dipakai kebanyakan OLT & ONU di pasar
     * Indonesia — memilih angka yang lebih besar berarti meloloskan jalur yang
     * gelap di perangkat yang benar-benar terpasang.
     */
    const val CLASS_B_PLUS_DB = 28.0

    /**
     * Sisa anggaran di bawah ini sudah dianggap mepet. Bukan batas gagal: jalur
     * dengan margin 2 dB memang menyala hari ini, dan padam begitu konektor
     * kotor, serat menua, atau satu sambungan darurat ditambahkan. Angka ini yang
     * membuat peringatan muncul SEBELUM pelanggannya menelepon.
     */
    const val WARN_MARGIN_DB = 3.0

    /** Rugi serat sepanjang [meters] meter. */
    fun fiberLoss(meters: Double): Double = meters / 1_000.0 * FIBER_LOSS_DB_PER_KM

    /** Sisa anggaran daya setelah jalur menghabiskan [lossDb]. */
    fun margin(lossDb: Double): Double = CLASS_B_PLUS_DB - lossDb
}

/**
 * Jenis satu langkah dalam penelusuran jalur — apa yang dilewati cahaya, bukan
 * di mana ia berada. Dipisah begini supaya layar bisa menggambar rantainya apa
 * adanya: serat, sambungan, splitter, port, ujung.
 */
enum class FiberHopKind(val label: String) {
    /** Titik awal: port PON di OLT. */
    PON_PORT("PON port"),

    /** Sehelai serat di dalam kabel — satu-satunya hop yang punya panjang. */
    FIBER("Serat"),

    /** Dua serat disatukan di dalam closure. */
    SPLICE("Sambungan"),

    /** Modul splitter: satu masukan dipecah, dan rugi sisipannya dibayar di sini. */
    SPLITTER("Splitter"),

    /** Lewat sebuah port ODF — belakang ke depan, dua adapter. */
    ODF_PORT("Port ODF"),

    /** Ujung jalur di sisi pelanggan. */
    ONU("ONU"),
}

/**
 * Kenapa penelusuran berhenti. Yang penting bukan "berhasil/gagal" melainkan
 * APA yang ditemukan di ujung — jalur yang buntu di tengah adalah temuan, bukan
 * kegagalan sistem, dan justru itu yang dicari orang saat melacak gangguan.
 */
enum class FiberTraceEnd(val label: String) {
    /** Sampai di port PON OLT — jalur utuh. */
    SOURCE("Sampai OLT"),

    /** Sampai di ONU pelanggan. */
    SUBSCRIBER("Sampai ONU pelanggan"),

    /** Titik terakhir tak tersambung ke apa pun: serat menganggur atau belum dipasang. */
    DEAD_END("Buntu — belum tersambung"),

    /**
     * Satu titik menawarkan lebih dari satu lanjutan ke arah yang sama, jadi
     * jalurnya tak tunggal. Datanya salah, dan menebak salah satu justru
     * menyembunyikan kesalahan itu.
     */
    AMBIGUOUS("Bercabang — data sambungan perlu diperiksa"),

    /**
     * Penelusuran berputar kembali ke titik yang sudah dilewati. Mustahil secara
     * fisik; tanda ada sambungan yang tercatat terbalik.
     */
    LOOP("Melingkar — ada sambungan yang salah catat"),

    /** Jalur lebih panjang dari batas yang masuk akal; dihentikan agar tak menggantung. */
    TOO_LONG("Terlalu panjang — dihentikan"),
}
