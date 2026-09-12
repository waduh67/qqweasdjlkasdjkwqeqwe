package com.duluin.ftth.order.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Impor massal pesanan / calon pelanggan dari CSV.
 *
 * Alurnya SENGAJA dua langkah — [preview] lalu [commit] — bukan satu unggahan yang langsung
 * menulis. Impor 500 baris yang menulis separuh lalu mati di tengah adalah bencana yang tak
 * bisa dibatalkan: tak ada tombol "undo" untuk 237 pesanan yang terlanjur masuk antrean
 * operator, dan tak ada cara mengetahui baris ke berapa ia berhenti. [preview] menulis HANYA
 * hasil urainya; tak satu pun pesanan atau calon pelanggan lahir sebelum [commit] ditekan.
 */
interface OrderImportUseCase {

    /**
     * Mengurai + memvalidasi berkas, menyimpan hasil urainya, dan TIDAK membuat apa pun.
     *
     * Mengunggah ulang byte yang PERSIS SAMA memulangkan batch yang sudah ada beserta
     * statusnya saat ini — bukan batch kedua.
     */
    fun preview(fileName: String, bytes: ByteArray): OrderImportBatchDetailView

    /** Membaca ulang satu praview (operator me-refresh halaman atau membukanya dari tab lain). */
    fun find(batchId: UUID): OrderImportBatchDetailView

    /**
     * Mengeksekusi baris-baris yang [OrderImportRowStatusView.ACCEPTED]. Setiap baris berjalan
     * di transaksinya SENDIRI: satu baris yang gagal tidak menggagalkan 499 baris lain.
     *
     * Memanggil ulang batch yang sudah COMMITTED adalah no-op yang memulangkan hasil yang sama —
     * bukan 409. Tombol "Jalankan" yang ditekan dua kali karena responsnya lambat adalah
     * perilaku normal, dan menghukumnya dengan galat hanya membuat operator mengunggah ulang.
     */
    fun commit(batchId: UUID): OrderImportBatchDetailView

    /** Riwayat impor terbaru — permukaan audit "500 pesanan ini datang dari mana?". */
    fun history(limit: Int): List<OrderImportBatchSummaryView>

    /** Isi berkas contoh (header + satu baris teladan) supaya operator mulai dari format yang benar. */
    fun template(): String
}

/** Cerminan `OrderImportBatchStatus` untuk klien; nilainya identik dengan enum domain. */
enum class OrderImportBatchStatusView { PREVIEWED, COMMITTED }

/** Cerminan `OrderImportRowStatus` untuk klien; nilainya identik dengan enum domain. */
enum class OrderImportRowStatusView { ACCEPTED, REJECTED, DUPLICATE, CREATED, FAILED }

/**
 * Ringkasan satu berkas impor untuk layar riwayat.
 *
 * [importedBy] sengaja UUID polos, tanpa nama: modul `order` tidak boleh menarik `iam` hanya
 * untuk menghias satu kolom, dan klien sudah punya daftar pengguna untuk memetakannya.
 */
@Suppress("LongParameterList")
data class OrderImportBatchSummaryView(
    val id: UUID,
    val fileName: String,
    val status: OrderImportBatchStatusView,
    val delimiter: String,
    val byteSize: Long,
    val totalRows: Int,
    val acceptedRows: Int,
    val rejectedRows: Int,
    val createdRows: Int,
    val failedRows: Int,
    val importedBy: UUID?,
    val createdAt: Instant,
    val committedAt: Instant?,
)

/** Praview lengkap: ringkasannya + vonis per baris, berurutan seperti di berkas aslinya. */
data class OrderImportBatchDetailView(
    val batch: OrderImportBatchSummaryView,
    val rows: List<OrderImportRowView>,
)

/**
 * Vonis satu baris. [message] adalah kalimat untuk MANUSIA dalam bahasa Indonesia — yang
 * membacanya operator penjualan, bukan pengembang.
 */
@Suppress("LongParameterList")
data class OrderImportRowView(
    val id: UUID,
    val lineNumber: Int,
    val status: OrderImportRowStatusView,
    val message: String?,
    val name: String?,
    val phone: String?,
    val email: String?,
    val planId: UUID?,
    val address: String?,
    val city: String?,
    val postalCode: String?,
    val notes: String?,
    val orderId: UUID?,
    val orderNumber: String?,
)

/**
 * Batas keras impor, ditaruh di kontrak supaya controller bisa menolak SEBELUM seluruh berkas
 * dibaca ke memori (`MultipartFile.size` sudah tersedia lebih dulu).
 */
object OrderImportLimits {
    /**
     * 2 MiB. CSV 1000 baris berisi nama+alamat Indonesia jarang melewati 200 KiB, jadi angka ini
     * sepuluh kali lipat kebutuhan wajar. Batasnya ada bukan untuk berhemat, tapi supaya berkas
     * salah pilih (mis. .xlsx 40 MB) ditolak dengan satu kalimat alih-alih menghabiskan heap
     * dan menjatuhkan proses yang sedang melayani semua tenant lain.
     */
    const val MAX_FILE_BYTES: Long = 2L * 1024 * 1024

    /**
     * 1000 baris data. Batas ini menjaga SATU permintaan HTTP tetap selesai dalam waktu yang
     * masuk akal: commit menjalankan satu transaksi per baris, dan 10.000 baris berarti 10.000
     * transaksi di dalam satu koneksi yang menggantung sampai proxy memutusnya di tengah jalan.
     */
    const val MAX_DATA_ROWS: Int = 1000
}
