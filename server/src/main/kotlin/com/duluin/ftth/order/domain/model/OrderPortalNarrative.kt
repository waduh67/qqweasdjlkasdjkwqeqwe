package com.duluin.ftth.order.domain.model

/**
 * Kalimat yang DIBACA PELANGGAN di halaman lacak ketika penanda portal dipasang OTOMATIS.
 *
 * KENAPA sistem yang menulis kalimatnya, bukan operator atau teknisi:
 *
 * 1. Catatan lapangan ditulis untuk rekan sekantor, bukan untuk pelanggan. "gak ada orang,
 *    tetangga bilang mudik", "titik ditolak, ybs minta di kamar tidur lt.2 — susah" adalah
 *    kalimat yang benar dan berguna secara internal, dan sekaligus kalimat yang tak boleh
 *    dibaca pelanggan yang bersangkutan.
 *
 * 2. Pemicu otomatis TIDAK PUNYA operator yang bisa dimintai kalimat. Kunjungan yang gagal dan
 *    saga yang macet terjadi di jalur mesin; kalau kalimatnya harus datang dari manusia, jalur
 *    itu akan diam-diam memasang penanda tanpa penjelasan sama sekali — dan penanda tanpa
 *    penjelasan justru membuat pelanggan menelepon, persis yang ingin dihindari.
 *
 * 3. Seragam. Dua pelanggan dengan sebab yang sama harus membaca kalimat yang sama.
 *
 * Setiap kalimat WAJIB memuat langkah berikutnya yang harus diambil pelanggan. Penanda
 * "menunggu Anda" tanpa memberi tahu apa yang ditunggu adalah jalan buntu.
 *
 * Panjang setiap kalimat WAJIB <= 300 karakter (`order_record.portal_flag_reason`).
 */
enum class OrderPortalNarrative(val sentence: String) {
    VISIT_CUSTOMER_NOT_PRESENT(
        "Teknisi kami sudah tiba di lokasi, tetapi tidak ada yang dapat ditemui. " +
            "Mohon hubungi kami untuk mengatur ulang jadwal kunjungan.",
    ),
    VISIT_PREMISE_LOCKED(
        "Teknisi kami sudah tiba di lokasi, tetapi bangunan dalam keadaan terkunci. " +
            "Mohon hubungi kami untuk mengatur ulang jadwal kunjungan.",
    ),
    VISIT_INSTALL_POINT_REJECTED(
        "Pemasangan tertunda karena titik pemasangan yang kami usulkan belum disetujui. " +
            "Mohon hubungi kami untuk menentukan titik pemasangan yang sesuai.",
    ),
    VISIT_RESCHEDULED_BY_CUSTOMER(
        "Kunjungan ditunda atas permintaan Anda. " +
            "Mohon hubungi kami untuk menentukan jadwal kunjungan berikutnya.",
    ),

    /**
     * Dipakai tombol operator "pelanggan tidak bisa dihubungi". SENGAJA tidak menyebut berapa
     * kali dan lewat apa: operator yang menekan tombolnya belum tentu yang menelepon, dan
     * kalimat yang mengklaim lebih dari yang diketahui akan dibantah pelanggan.
     */
    CUSTOMER_UNREACHABLE(
        "Kami belum berhasil menghubungi Anda untuk mengatur jadwal pemasangan. " +
            "Mohon hubungi kami agar pemasangan dapat segera dijadwalkan.",
    ),

    /**
     * Dipakai saat saga fulfillment mendarat di REQUIRES_RECONCILIATION. Kalimatnya SENGAJA
     * tidak menyebut kegagalan teknis apa pun: pelanggan tidak bisa berbuat apa-apa dengan
     * "efek ORDER ditolak", dan menyebutnya hanya menambah kecemasan tanpa menambah informasi.
     * Yang ia perlu tahu hanya: ini sedang ditangani, dan tidak ada yang perlu ia lakukan.
     */
    FULFILLMENT_NEEDS_REVIEW(
        "Pesanan Anda sedang kami periksa kembali oleh tim kami. " +
            "Tidak ada yang perlu Anda lakukan; kami akan menghubungi Anda begitu ada perkembangan.",
    ),
    ;

    init {
        // Companion object SENGAJA tidak dipakai di sini: entri enum dikonstruksi SEBELUM
        // companion-nya diinisialisasi, dan kompilator menolaknya ("Companion object ... is
        // uninitialized here"). Konstanta top-level private aman karena ia milik file, bukan kelas.
        require(sentence.length <= MAX_NARRATIVE_SENTENCE) { "Narasi portal maksimal $MAX_NARRATIVE_SENTENCE karakter" }
    }
}

/** Sepadan dengan `order_record.portal_flag_reason varchar(300)` dan `Order.MAX_FLAG_REASON`. */
private const val MAX_NARRATIVE_SENTENCE = 300
