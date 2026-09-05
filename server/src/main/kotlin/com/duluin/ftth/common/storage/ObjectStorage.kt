package com.duluin.ftth.common.storage

/**
 * Port penyimpanan objek biner (bukti foto/tanda tangan work-order, gambar QRIS billing).
 * Diimplementasikan di lapisan adapter memakai S3/MinIO — application hanya tahu
 * put/get/delete berkunci, tak tahu vendor storage-nya. Listing and deletion
 * are explicitly tenant-scoped so reconciliation cannot accidentally sweep a
 * neighboring tenant's prefix.
 *
 * Byte diedarkan sebagai [ByteArray]: berkas berukuran wajar (foto ponsel, gambar QRIS),
 * dan boundary yang sederhana lebih aman daripada mengedarkan stream yang harus dijaga
 * siklus hidupnya lintas-lapisan.
 */
interface ObjectStorage {

    fun put(key: String, contentType: String, bytes: ByteArray)

    /** Mengambil objek pada [key]; melempar bila tak ada. */
    fun get(key: String): StoredObject

    fun head(tenantId: String, key: String): StoredObjectMetadata {
        require(key.startsWith("$tenantId/")) { "Cross-tenant storage access" }
        val value = get(key)
        return StoredObjectMetadata(key, value.contentType, value.size, null, value.etag, value.version)
    }

    fun list(tenantId: String, prefix: String, pageToken: String? = null, pageSize: Int = 100): ObjectPage =
        throw UnsupportedOperationException("Object listing is not supported by this storage adapter")

    fun deleteIfMatch(tenantId: String, key: String, guard: DeleteGuard): Boolean = false

    fun delete(key: String)
}

/** Isi objek yang diambil dari storage. */
class StoredObject(
    val contentType: String,
    val bytes: ByteArray,
    val etag: String? = null,
    val version: String? = null,
) {
    val size: Long get() = bytes.size.toLong()
}

data class StoredObjectMetadata(
    val key: String,
    val contentType: String,
    val size: Long,
    val sha256: String?,
    val etag: String?,
    val version: String?,
)

data class ObjectPage(val objects: List<StoredObjectMetadata>, val nextToken: String?)

data class DeleteGuard(val etag: String? = null, val version: String? = null)
