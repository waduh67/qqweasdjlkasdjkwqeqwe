package com.duluin.ftth.helpdesk.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Jenis keluhan yang bisa dipilih pelanggan saat melapor. Sengaja sedikit dan berbahasa
 * pelanggan (bukan istilah jaringan) — daftar panjang membuat orang asal pilih, dan yang
 * menentukan penanganan tetap isi laporannya, bukan kategorinya.
 */
enum class TicketCategory { KONEKSI_PUTUS, KONEKSI_LAMBAT, PERANGKAT, TAGIHAN, LAINNYA }

/**
 * Alur tiket: `OPEN → IN_PROGRESS → RESOLVED → CLOSED`. [RESOLVED] artinya operator
 * menyatakan selesai tapi pelanggan belum mengonfirmasi — membalas dari portal membuka
 * tiketnya kembali ("masih rusak"). [CLOSED] terminal: utasnya tak bisa dibalas lagi.
 */
enum class TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    ;

    val open: Boolean get() = this != CLOSED
}

/**
 * Penulis satu pesan di utas. [SYSTEM] untuk jejak otomatis (perubahan status, eskalasi ke
 * work order) supaya riwayatnya terbaca utuh tanpa tabel timeline terpisah.
 */
enum class TicketAuthor { CUSTOMER, OPERATOR, SYSTEM }

/**
 * Prioritas pengerjaan lapangan ketika tiket dieskalasi jadi work order. Namanya sengaja
 * seragam dengan `WorkOrderPriority` module workorder, tapi tetap tipe milik helpdesk:
 * penyeberangannya lewat kontrak publik (nama enum), bukan import lintas-module.
 */
enum class TicketPriority { LOW, NORMAL, HIGH, URGENT }

/** Satu pesan di utas tiket — dari pelanggan, operator, atau catatan sistem. */
class TicketMessage private constructor(
    val id: UUID,
    val tenantId: UUID,
    val ticketId: UUID,
    val author: TicketAuthor,
    /** Pengguna/pelanggan penulisnya; null untuk pesan [TicketAuthor.SYSTEM]. */
    val authorId: UUID?,
    val authorName: String,
    val body: String,
    val at: Instant,
) {
    companion object {
        @Suppress("LongParameterList")
        fun of(
            tenantId: UUID,
            ticketId: UUID,
            author: TicketAuthor,
            authorId: UUID?,
            authorName: String,
            body: String,
            at: Instant,
        ) = TicketMessage(UuidV7.generate(), tenantId, ticketId, author, authorId, authorName, body, at)

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            ticketId: UUID,
            author: TicketAuthor,
            authorId: UUID?,
            authorName: String,
            body: String,
            at: Instant,
        ) = TicketMessage(id, tenantId, ticketId, author, authorId, authorName, body, at)
    }
}

/**
 * Tiket helpdesk: satu keluhan yang dilaporkan pelanggan dari portal, ditangani operator
 * lewat utas percakapan, dan — bila perlu kunjungan — dieskalasi jadi work order.
 *
 * Pelanggan dan operator melihat AGREGAT YANG SAMA; yang berbeda hanya pintu masuknya
 * (`HelpdeskApi` untuk portal, use case operator untuk konsol). Dengan begitu status yang
 * dibaca pelanggan mustahil menyimpang dari yang dikerjakan operator.
 *
 * Nama pelanggan disalin saat tiket dibuka agar antrean operator terbaca tanpa join
 * lintas-module; `workOrderCode` juga snapshot, karena kode WO tak pernah berubah.
 * Pesan baru ditahan sebagai [pendingMessages] lalu dipersistensi bersama agregatnya —
 * pola yang sama dengan timeline insiden & work order.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class Ticket private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    val customerId: UUID,
    val customerName: String,
    val category: TicketCategory,
    val subject: String,
    /** Laporan awal pelanggan — pesan pertama utas, disimpan di tiketnya sendiri. */
    val description: String,
    status: TicketStatus,
    workOrderId: UUID?,
    workOrderCode: String?,
    val openedAt: Instant,
    lastActivityAt: Instant,
    resolvedAt: Instant?,
    closedAt: Instant?,
) {
    var status: TicketStatus = status
        private set
    var workOrderId: UUID? = workOrderId
        private set
    var workOrderCode: String? = workOrderCode
        private set

    /** Kapan tiket terakhir bergerak (pesan/status) — urutan antrean operator memakainya. */
    var lastActivityAt: Instant = lastActivityAt
        private set
    var resolvedAt: Instant? = resolvedAt
        private set
    var closedAt: Instant? = closedAt
        private set

    private val _pendingMessages = mutableListOf<TicketMessage>()

    /** Pesan baru yang belum tersimpan; dikosongkan repository setelah ditulis. */
    fun pendingMessages(): List<TicketMessage> = _pendingMessages.toList()

    fun clearPendingMessages() = _pendingMessages.clear()

    /** Pelanggan membalas: menambah pesan dan — bila tiket sempat dinyatakan selesai — membukanya lagi. */
    fun replyByCustomer(body: String, at: Instant) {
        requireStillOpen()
        append(TicketAuthor.CUSTOMER, customerId, customerName, body, at)
        if (status == TicketStatus.RESOLVED) {
            // Pelanggan membalas tiket yang dinyatakan selesai = masalahnya belum beres.
            status = TicketStatus.OPEN
            resolvedAt = null
            append(TicketAuthor.SYSTEM, null, SYSTEM_NAME, "Dibuka kembali karena pelanggan membalas.", at)
        }
    }

    /** Operator membalas: tiket yang masih di antrean otomatis berpindah ke "sedang ditangani". */
    fun replyByOperator(actorId: UUID, actorName: String, body: String, at: Instant) {
        requireStillOpen()
        append(TicketAuthor.OPERATOR, actorId, actorName, body, at)
        if (status == TicketStatus.OPEN) status = TicketStatus.IN_PROGRESS
    }

    /** Operator memindahkan status; transisi ilegal ditolak di domain. */
    fun changeStatus(target: TicketStatus, actorId: UUID?, actorName: String, at: Instant) {
        if (target == status) return
        if (target !in (ALLOWED[status] ?: emptySet())) {
            throw ConflictException("Tiket berstatus ${status.name} tak bisa dipindah ke ${target.name}")
        }
        status = target
        resolvedAt = if (target == TicketStatus.RESOLVED) at else null
        closedAt = if (target == TicketStatus.CLOSED) at else null
        append(TicketAuthor.SYSTEM, actorId, actorName, "Status diubah menjadi ${LABEL[target]}.", at)
    }

    /** Pelanggan menutup sendiri tiketnya ("sudah beres, terima kasih"). */
    fun closeByCustomer(at: Instant) {
        requireStillOpen()
        status = TicketStatus.CLOSED
        closedAt = at
        append(TicketAuthor.SYSTEM, customerId, customerName, "Ditutup oleh pelanggan.", at)
    }

    /**
     * Menautkan work order hasil eskalasi. Sekali saja: tiket yang sudah punya WO harus
     * ditangani lewat WO itu, bukan dengan menerbitkan WO kedua untuk keluhan yang sama.
     */
    fun attachWorkOrder(id: UUID, code: String, actorId: UUID?, actorName: String, at: Instant) {
        requireStillOpen()
        workOrderCode?.let { throw ConflictException("Tiket sudah dieskalasi ke work order $it") }
        workOrderId = id
        workOrderCode = code
        if (status == TicketStatus.OPEN) status = TicketStatus.IN_PROGRESS
        append(TicketAuthor.SYSTEM, actorId, actorName, "Dieskalasi ke work order $code.", at)
    }

    private fun requireStillOpen() {
        if (!status.open) throw ConflictException("Tiket $code sudah ditutup")
    }

    private fun append(author: TicketAuthor, authorId: UUID?, authorName: String, body: String, at: Instant) {
        val text = body.trim()
        if (text.isEmpty()) throw ValidationException("Pesan tidak boleh kosong")
        if (text.length > MAX_BODY) throw ValidationException("Pesan maksimal $MAX_BODY karakter")
        _pendingMessages += TicketMessage.of(tenantId, id, author, authorId, authorName, text, at)
        lastActivityAt = at
    }

    companion object {
        const val MAX_SUBJECT = 150
        const val MAX_BODY = 2000
        private const val SYSTEM_NAME = "Sistem"

        private val ALLOWED: Map<TicketStatus, Set<TicketStatus>> = mapOf(
            TicketStatus.OPEN to setOf(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CLOSED),
            TicketStatus.IN_PROGRESS to setOf(TicketStatus.OPEN, TicketStatus.RESOLVED, TicketStatus.CLOSED),
            // Tiket yang dinyatakan selesai masih bisa dibuka lagi bila keluhannya kambuh.
            TicketStatus.RESOLVED to setOf(TicketStatus.OPEN, TicketStatus.CLOSED),
            TicketStatus.CLOSED to emptySet(),
        )

        private val LABEL: Map<TicketStatus, String> = mapOf(
            TicketStatus.OPEN to "menunggu ditangani",
            TicketStatus.IN_PROGRESS to "sedang ditangani",
            TicketStatus.RESOLVED to "selesai",
            TicketStatus.CLOSED to "ditutup",
        )

        /** Kode ringkas & stabil dari id; cukup unik untuk disebut di WhatsApp/telepon. */
        private fun deriveCode(id: UUID): String = "TKT-" + id.toString().takeLast(8).uppercase()

        fun open(
            tenantId: UUID,
            customerId: UUID,
            customerName: String,
            category: TicketCategory,
            subject: String,
            description: String,
            at: Instant,
        ): Ticket {
            val judul = subject.trim()
            val isi = description.trim()
            if (judul.isEmpty()) throw ValidationException("Judul laporan tidak boleh kosong")
            if (judul.length > MAX_SUBJECT) throw ValidationException("Judul maksimal $MAX_SUBJECT karakter")
            if (isi.isEmpty()) throw ValidationException("Ceritakan keluhannya lebih dulu")
            if (isi.length > MAX_BODY) throw ValidationException("Keluhan maksimal $MAX_BODY karakter")
            val id = UuidV7.generate()
            return Ticket(
                id = id,
                tenantId = tenantId,
                code = deriveCode(id),
                customerId = customerId,
                customerName = customerName,
                category = category,
                subject = judul,
                description = isi,
                status = TicketStatus.OPEN,
                workOrderId = null,
                workOrderCode = null,
                openedAt = at,
                lastActivityAt = at,
                resolvedAt = null,
                closedAt = null,
            )
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            customerId: UUID,
            customerName: String,
            category: TicketCategory,
            subject: String,
            description: String,
            status: TicketStatus,
            workOrderId: UUID?,
            workOrderCode: String?,
            openedAt: Instant,
            lastActivityAt: Instant,
            resolvedAt: Instant?,
            closedAt: Instant?,
        ) = Ticket(
            id, tenantId, code, customerId, customerName, category, subject, description,
            status, workOrderId, workOrderCode, openedAt, lastActivityAt, resolvedAt, closedAt,
        )
    }
}
