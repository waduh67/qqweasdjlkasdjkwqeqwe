package com.duluin.ftth.order.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import java.time.Instant
import java.util.UUID

/**
 * Keadaan satu berkas impor. SENGAJA hanya dua: sebuah berkas sudah diurai (PREVIEWED) atau
 * sudah dieksekusi (COMMITTED). Tak ada "sedang berjalan" karena tak ada yang bisa dilakukan
 * operator terhadap keadaan itu selain menunggu — dan keadaan menunggu yang macet (proses mati
 * di tengah) akan mengunci berkasnya selamanya tanpa satu pun tombol untuk membukanya.
 */
enum class OrderImportBatchStatus { PREVIEWED, COMMITTED }

/**
 * Keadaan satu BARIS berkas. Tiga yang pertama lahir saat praview, dua terakhir saat commit:
 *  - [ACCEPTED]  : lolos validasi, akan dibuatkan pesanan saat commit ditekan.
 *  - [REJECTED]  : datanya salah; [OrderImportRow.message] menjelaskan apa yang harus diperbaiki.
 *  - [DUPLICATE] : datanya benar tapi orang+paket+alamat ini sudah pernah diimpor.
 *  - [CREATED]   : pesanannya benar-benar ada; [OrderImportRow.orderId] menunjuknya.
 *  - [FAILED]    : lolos praview tapi gagal saat dieksekusi (mis. paketnya dinonaktifkan di
 *                  antara praview dan commit). Baris lain di berkas yang sama TIDAK ikut gagal.
 */
enum class OrderImportRowStatus { ACCEPTED, REJECTED, DUPLICATE, CREATED, FAILED }

/**
 * Satu berkas CSV yang pernah diunggah — sekaligus jejak audit dan kunci idempotensi.
 *
 * [contentHash] adalah janji "unggah ulang berkas yang sama TIDAK melahirkan 500 pesanan
 * kembar". Operator yang ragu apakah unggahannya tadi berhasil PASTI mencoba lagi; itu perilaku
 * normal manusia di depan layar yang menggantung, bukan kasus tepi.
 */
@Suppress("LongParameterList")
class OrderImportBatch private constructor(
    val id: UUID,
    val tenantId: UUID,
    val fileName: String,
    val contentHash: String,
    val byteSize: Long,
    /** Pemisah yang BENAR-BENAR dipakai saat mengurai; Excel berlokal Indonesia menulis ';'. */
    val delimiter: Char,
    status: OrderImportBatchStatus,
    totalRows: Int,
    acceptedRows: Int,
    rejectedRows: Int,
    createdRows: Int,
    failedRows: Int,
    /** Pelaku impor. Tanpa ini, ratusan pesanan yang muncul serentak tak punya penanggung jawab. */
    val importedBy: UUID?,
    committedAt: Instant?,
    val createdAt: Instant,
) {
    var status: OrderImportBatchStatus = status
        private set
    var totalRows: Int = totalRows
        private set
    var acceptedRows: Int = acceptedRows
        private set
    var rejectedRows: Int = rejectedRows
        private set
    var createdRows: Int = createdRows
        private set
    var failedRows: Int = failedRows
        private set
    var committedAt: Instant? = committedAt
        private set

    companion object {
        const val MAX_FILE_NAME = 255

        fun preview(
            tenantId: UUID,
            fileName: String,
            contentHash: String,
            byteSize: Long,
            delimiter: Char,
            importedBy: UUID?,
            now: Instant = Instant.now(),
        ) = OrderImportBatch(
            id = UuidV7.generate(),
            tenantId = tenantId,
            // Nama berkas datang dari browser dan bisa sepanjang apa pun; kolomnya varchar(255).
            // Dipotong, BUKAN ditolak: nama berkas tak pernah jadi alasan sah menolak 500 baris.
            fileName = fileName.trim().ifBlank { "import.csv" }.take(MAX_FILE_NAME),
            contentHash = contentHash,
            byteSize = byteSize,
            delimiter = delimiter,
            status = OrderImportBatchStatus.PREVIEWED,
            totalRows = 0,
            acceptedRows = 0,
            rejectedRows = 0,
            createdRows = 0,
            failedRows = 0,
            importedBy = importedBy,
            committedAt = null,
            createdAt = now,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            fileName: String,
            contentHash: String,
            byteSize: Long,
            delimiter: Char,
            status: OrderImportBatchStatus,
            totalRows: Int,
            acceptedRows: Int,
            rejectedRows: Int,
            createdRows: Int,
            failedRows: Int,
            importedBy: UUID?,
            committedAt: Instant?,
            createdAt: Instant,
        ) = OrderImportBatch(
            id, tenantId, fileName, contentHash, byteSize, delimiter, status, totalRows,
            acceptedRows, rejectedRows, createdRows, failedRows, importedBy, committedAt, createdAt,
        )
    }

    /**
     * Cacah hasil praview. Dipisah dari [preview] karena baris-barisnya baru bisa divonis
     * SETELAH batch punya id — dan id itulah yang setiap barisnya tunjuk.
     */
    fun summarizePreview(totalRows: Int, acceptedRows: Int, rejectedRows: Int) {
        this.totalRows = totalRows
        this.acceptedRows = acceptedRows
        this.rejectedRows = rejectedRows
    }

    /**
     * Menutup batch setelah seluruh baris dieksekusi. [createdRows] SENGAJA dihitung ulang dari
     * baris-barisnya, bukan dari [acceptedRows]: baris yang lolos praview masih bisa gagal saat
     * dijalankan, dan batch yang mengaku "500 dibuat" padahal 37 gagal adalah laporan yang bohong.
     */
    fun markCommitted(createdRows: Int, failedRows: Int, now: Instant = Instant.now()) {
        if (status == OrderImportBatchStatus.COMMITTED) {
            throw ConflictException("Impor ini sudah pernah dijalankan")
        }
        this.createdRows = createdRows
        this.failedRows = failedRows
        this.status = OrderImportBatchStatus.COMMITTED
        this.committedAt = now
    }
}

/**
 * Satu BARIS berkas. Per baris, bukan satu ringkasan JSON, karena satu baris rusak tidak boleh
 * menggagalkan 499 baris lain — tapi operator WAJIB melihat baris KE BERAPA yang gagal dan
 * KENAPA, dalam kalimat yang bisa dibaca orang non-teknis.
 *
 * [lineNumber] adalah nomor baris di BERKAS ASLI (header = 1), bukan indeks setelah penyaringan:
 * operator memperbaiki berkasnya di Excel, dan Excel menomori dari 1.
 */
@Suppress("LongParameterList")
class OrderImportRow private constructor(
    val id: UUID,
    val tenantId: UUID,
    val batchId: UUID,
    val lineNumber: Int,
    /** Kunci idempotensi TINGKAT BARIS; dipakai apa adanya sebagai `operation_key`. */
    val fingerprint: String?,
    status: OrderImportRowStatus,
    message: String?,
    val name: String?,
    val phone: String?,
    val email: String?,
    val planId: UUID?,
    val address: String?,
    val city: String?,
    val postalCode: String?,
    val notes: String?,
    orderId: UUID?,
    leadId: UUID?,
    orderNumber: String?,
    val createdAt: Instant,
) {
    var status: OrderImportRowStatus = status
        private set
    var message: String? = message
        private set
    var orderId: UUID? = orderId
        private set
    var leadId: UUID? = leadId
        private set
    var orderNumber: String? = orderNumber
        private set

    companion object {
        const val MAX_MESSAGE = 500

        @Suppress("LongParameterList")
        fun preview(
            tenantId: UUID,
            batchId: UUID,
            lineNumber: Int,
            status: OrderImportRowStatus,
            message: String? = null,
            fingerprint: String? = null,
            name: String? = null,
            phone: String? = null,
            email: String? = null,
            planId: UUID? = null,
            address: String? = null,
            city: String? = null,
            postalCode: String? = null,
            notes: String? = null,
            now: Instant = Instant.now(),
        ) = OrderImportRow(
            id = UuidV7.generate(),
            tenantId = tenantId,
            batchId = batchId,
            lineNumber = lineNumber,
            fingerprint = fingerprint,
            status = status,
            message = message?.take(MAX_MESSAGE),
            name = name, phone = phone, email = email, planId = planId,
            address = address, city = city, postalCode = postalCode, notes = notes,
            orderId = null, leadId = null, orderNumber = null,
            createdAt = now,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            batchId: UUID,
            lineNumber: Int,
            fingerprint: String?,
            status: OrderImportRowStatus,
            message: String?,
            name: String?,
            phone: String?,
            email: String?,
            planId: UUID?,
            address: String?,
            city: String?,
            postalCode: String?,
            notes: String?,
            orderId: UUID?,
            leadId: UUID?,
            orderNumber: String?,
            createdAt: Instant,
        ) = OrderImportRow(
            id, tenantId, batchId, lineNumber, fingerprint, status, message, name, phone, email,
            planId, address, city, postalCode, notes, orderId, leadId, orderNumber, createdAt,
        )
    }

    /**
     * Pesanannya sudah ada. [orderId] WAJIB — tabelnya menegakkan hal yang sama lewat CHECK,
     * karena batch setengah jadi yang barisnya CREATED tanpa pesanan tak bisa dibedakan dari
     * batch yang gagal seluruhnya.
     */
    fun markCreated(orderId: UUID, leadId: UUID?, orderNumber: String?) {
        this.status = OrderImportRowStatus.CREATED
        this.orderId = orderId
        this.leadId = leadId
        this.orderNumber = orderNumber
        this.message = null
    }

    /** [reason] WAJIB berupa kalimat untuk MANUSIA; penolakan tanpa alasan membuat fitur ini tak terpakai. */
    fun markFailed(reason: String) {
        this.status = OrderImportRowStatus.FAILED
        this.message = reason.ifBlank { "Baris ini gagal dieksekusi" }.take(MAX_MESSAGE)
    }
}
