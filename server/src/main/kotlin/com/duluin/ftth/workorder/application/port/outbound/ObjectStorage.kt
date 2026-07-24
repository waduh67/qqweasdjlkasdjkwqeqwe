package com.duluin.ftth.workorder.application.port.outbound

/**
 * Port penyimpanan objek biner (bukti foto, tanda tangan). Diimplementasikan di
 * lapisan adapter memakai S3/MinIO — application hanya tahu put/get/delete
 * berkunci, tak tahu vendor storage-nya.
 *
 * Byte diedarkan sebagai [ByteArray]: berkas bukti berukuran wajar (foto ponsel),
 * dan boundary yang sederhana lebih aman daripada mengedarkan stream yang harus
 * dijaga siklus hidupnya lintas-lapisan.
 */
interface ObjectStorage {

    /** Menyimpan (menimpa bila ada) objek pada [key]. */
    fun put(key: String, contentType: String, bytes: ByteArray)

    /** Mengambil objek pada [key]; melempar bila tak ada. */
    fun get(key: String): StoredObject

    /** Menghapus objek pada [key]; idempotent (tak ada = tidak apa-apa). */
    fun delete(key: String)
}

/** Isi objek yang diambil dari storage. */
class StoredObject(
    val contentType: String,
    val bytes: ByteArray,
) {
    val size: Long get() = bytes.size.toLong()
}
