package com.duluin.ftth.helpdesk.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Jenis keluhan yang bisa dipilih pelanggan saat melapor. Sengaja sedikit dan berbahasa
 * pelanggan (bukan istilah jaringan) — daftar panjang membuat orang asal pilih, dan yang
 * menentukan penanganan tetap isi laporannya, bukan kategorinya.
 *
 * [GANTI_PAKET] bukan keluhan melainkan PERMINTAAN: pelanggan ingin naik/turun paket. Ia
 * ikut jadi tiket karena butuh perkakas yang sama persis (penanggung jawab, tenggat, utas
 * balasan, eskalasi ke work order bila perlu ganti perangkat) — tapi berkategori sendiri
 * agar bisa disaring & dilaporkan terpisah dari gangguan.
 */
enum class TicketCategory { KONEKSI_PUTUS, KONEKSI_LAMBAT, PERANGKAT, TAGIHAN, LAINNYA, GANTI_PAKET }

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
 * Prioritas penanganan tiket — sekaligus yang menentukan janji waktunya (lihat [TicketSla])
 * dan prioritas work order bila dieskalasi. Namanya sengaja seragam dengan
 * `WorkOrderPriority` module workorder, tapi tetap tipe milik helpdesk: penyeberangannya
 * lewat kontrak publik (nama enum), bukan import lintas-module.
 */
enum class TicketPriority { LOW, NORMAL, HIGH, URGENT }

/**
 * Janji waktu penanganan per prioritas: berapa lama sampai keluhan DIBALAS, dan berapa lama
 * sampai DINYATAKAN SELESAI.
 *
 * Dihitung dengan jam dinding 24/7, bukan jam kerja. Gangguan internet tak mengenal jam
 * kantor — SLA yang berhenti berdetak jam 5 sore akan memberi lampu hijau palsu pada keluhan
 * yang masuk Jumat malam dan baru disentuh Senin.
 *
 * Angkanya konstanta sistem, belum bisa disetel per tenant. Itu keputusan sadar: SLA yang
 * bisa diatur sendiri cenderung dilonggarkan sampai tak pernah terlewat, dan angka yang tak
 * pernah terlewat tak memberi tahu apa-apa. Kalau nanti benar-benar perlu per-tenant, tempat
 * berubahnya cuma di sini.
 */
object TicketSla {

    /** prioritas → (tenggat balasan, tenggat penyelesaian) sejak bola ada di tangan operator. */
    private val POLICY: Map<TicketPriority, Pair<Duration, Duration>> = mapOf(
        TicketPriority.URGENT to (Duration.ofMinutes(30) to Duration.ofHours(4)),
        TicketPriority.HIGH to (Duration.ofHours(1) to Duration.ofHours(8)),
        TicketPriority.NORMAL to (Duration.ofHours(4) to Duration.ofHours(24)),
        TicketPriority.LOW to (Duration.ofHours(8) to Duration.ofHours(72)),
    )

    fun responseWindow(priority: TicketPriority): Duration = POLICY.getValue(priority).first

    fun resolutionWindow(priority: TicketPriority): Duration = POLICY.getValue(priority).second

    fun responseDue(from: Instant, priority: TicketPriority): Instant = from + responseWindow(priority)

    fun resolutionDue(from: Instant, priority: TicketPriority): Instant = from + resolutionWindow(priority)
}

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
 *
 * **Kepemilikan & janji waktu.** Tiket punya satu penanggung jawab ([assigneeId]) dan dua
 * tenggat ([responseDueAt], [resolutionDueAt]) yang diturunkan dari [priority] lewat
 * [TicketSla]. Tanpa penanggung jawab, tiket di antrean bersama adalah milik semua orang
 * sekaligus tak seorang pun; tanpa tenggat, "sedang ditangani" bisa berarti tiga minggu.
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
    priority: TicketPriority,
    assigneeId: UUID?,
    assigneeName: String?,
    workOrderId: UUID?,
    workOrderCode: String?,
    val openedAt: Instant,
    lastActivityAt: Instant,
    firstResponseAt: Instant?,
    responseDueAt: Instant?,
    resolutionDueAt: Instant,
    slaAlertedAt: Instant?,
    resolvedAt: Instant?,
    closedAt: Instant?,
) {
    var status: TicketStatus = status
        private set
    var priority: TicketPriority = priority
        private set

    /** Operator yang memegang tiket ini; null = masih di antrean bersama. */
    var assigneeId: UUID? = assigneeId
        private set

    /** Salinan nama saat ditugaskan — antrean terbaca tanpa resolusi id ke module iam. */
    var assigneeName: String? = assigneeName
        private set

    var workOrderId: UUID? = workOrderId
        private set
    var workOrderCode: String? = workOrderCode
        private set

    /** Kapan tiket terakhir bergerak (pesan/status) — urutan antrean operator memakainya. */
    var lastActivityAt: Instant = lastActivityAt
        private set

    /** Balasan operator PERTAMA sepanjang umur tiket; murni metrik laporan, tak pernah direset. */
    var firstResponseAt: Instant? = firstResponseAt
        private set

    /**
     * Tenggat balasan yang sedang ditunggu pelanggan, atau null bila bolanya BUKAN di tangan
     * operator (sudah dibalas / tiket ditutup).
     *
     * Sengaja bukan "tenggat balasan pertama": pelanggan yang membalas lagi setelah dijawab
     * kembali menunggu, dan menghitung SLA sekali di awal saja membuat tiket yang digantung
     * di balasan kedua terlihat sehat.
     */
    var responseDueAt: Instant? = responseDueAt
        private set

    /** Tenggat tiket dinyatakan selesai; dihitung ulang saat prioritas berubah atau tiket dibuka lagi. */
    var resolutionDueAt: Instant = resolutionDueAt
        private set

    /** Kapan penjaga SLA terakhir meneriakkan tiket ini — rem supaya tak berteriak tiap ronde. */
    var slaAlertedAt: Instant? = slaAlertedAt
        private set

    var resolvedAt: Instant? = resolvedAt
        private set
    var closedAt: Instant? = closedAt
        private set

    /** Operator belum membalas padahal tenggatnya lewat — yang paling menyakitkan buat pelanggan. */
    fun responseOverdue(now: Instant): Boolean =
        status.open && responseDueAt?.isBefore(now) == true

    /** Tiket belum dinyatakan selesai padahal tenggatnya lewat. */
    fun resolutionOverdue(now: Instant): Boolean =
        status.open && resolvedAt == null && resolutionDueAt.isBefore(now)

    fun slaOverdue(now: Instant): Boolean = responseOverdue(now) || resolutionOverdue(now)

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
            resolutionDueAt = TicketSla.resolutionDue(at, priority)
        }
        // Bolanya balik ke operator: jam balasan mulai lagi dari sekarang.
        responseDueAt = TicketSla.responseDue(at, priority)
        slaAlertedAt = null
    }

    /** Operator membalas: tiket yang masih di antrean otomatis berpindah ke "sedang ditangani". */
    fun replyByOperator(actorId: UUID, actorName: String, body: String, at: Instant) {
        requireStillOpen()
        append(TicketAuthor.OPERATOR, actorId, actorName, body, at)
        if (status == TicketStatus.OPEN) status = TicketStatus.IN_PROGRESS
        if (firstResponseAt == null) firstResponseAt = at
        // Pelanggan tak lagi menunggu jawaban; yang tersisa cuma tenggat penyelesaian.
        responseDueAt = null
        // Peringatan yang lalu sudah dijawab dengan tindakan. Kalau penandanya dibiarkan,
        // tiket yang telat balasan lalu dibalas tak akan pernah diteriakkan lagi meski
        // tenggat penyelesaiannya kemudian lewat juga.
        slaAlertedAt = null
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
        when (target) {
            // Dikembalikan ke antrean = ronde baru: kedua jam dimulai ulang, dan penjaga SLA
            // boleh berteriak lagi. Tanpa ini, tiket yang dibuka lagi lahir dalam keadaan
            // sudah telat — tenggatnya masih milik ronde yang sudah lewat.
            TicketStatus.OPEN -> {
                responseDueAt = TicketSla.responseDue(at, priority)
                resolutionDueAt = TicketSla.resolutionDue(at, priority)
                slaAlertedAt = null
            }
            // Operator sudah bertindak, walau tanpa balasan tertulis.
            else -> responseDueAt = null
        }
        append(TicketAuthor.SYSTEM, actorId, actorName, "Status diubah menjadi ${LABEL[target]}.", at)
    }

    /**
     * Mengubah prioritas — sekaligus menggeser kedua tenggatnya, dihitung ulang dari titik
     * yang sama dengan tenggat lama supaya tiket yang dinaikkan ke URGENT langsung terlihat
     * telat kalau memang sudah lama menganggur.
     */
    fun changePriority(target: TicketPriority, at: Instant) {
        requireStillOpen()
        if (target == priority) return
        val sebelumnya = priority
        priority = target
        // Tenggat balasan digeser relatif (jam mulainya dipertahankan), bukan dihitung ulang
        // dari `at` — kalau tidak, menaikkan prioritas justru MEMPERPANJANG waktu yang tersisa.
        responseDueAt = responseDueAt
            ?.minus(TicketSla.responseWindow(sebelumnya))
            ?.plus(TicketSla.responseWindow(target))
        resolutionDueAt = TicketSla.resolutionDue(openedAt, target)
        slaAlertedAt = null
        lastActivityAt = at
    }

    /**
     * Menyerahkan tiket ke seorang operator (null = mengembalikannya ke antrean bersama).
     *
     * Sengaja TIDAK mengubah status: memegang tiket belum berarti mengerjakannya, dan
     * "sedang ditangani" yang muncul otomatis saat penugasan membuat status itu berhenti
     * berarti apa-apa. Yang memindahkan status tetap balasan operator.
     *
     * Juga sengaja tidak menulis pesan ke utas: utasnya dibaca pelanggan, dan pembagian
     * kerja internal bukan urusan mereka. Jejaknya masuk ke audit.
     */
    fun assignTo(userId: UUID?, userName: String?, at: Instant) {
        requireStillOpen()
        if (userId != null && userName.isNullOrBlank()) {
            throw ValidationException("Nama penanggung jawab tidak boleh kosong")
        }
        assigneeId = userId
        assigneeName = userName?.takeIf { userId != null }
        lastActivityAt = at
    }

    /** Ditandai penjaga SLA supaya ronde berikutnya tak meneriakkan tiket yang sama. */
    fun markSlaAlerted(at: Instant) {
        slaAlertedAt = at
    }

    /** Pelanggan menutup sendiri tiketnya ("sudah beres, terima kasih"). */
    fun closeByCustomer(at: Instant) {
        requireStillOpen()
        status = TicketStatus.CLOSED
        closedAt = at
        responseDueAt = null
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
            // Pelanggan tak memilih prioritas sendiri — semua laporan lahir NORMAL, dan
            // operatorlah yang menaikkannya. Kalau pelapor yang menentukan, dalam sebulan
            // semua tiket jadi URGENT dan prioritas berhenti membedakan apa pun.
            val priority = TicketPriority.NORMAL
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
                priority = priority,
                assigneeId = null,
                assigneeName = null,
                workOrderId = null,
                workOrderCode = null,
                openedAt = at,
                lastActivityAt = at,
                firstResponseAt = null,
                responseDueAt = TicketSla.responseDue(at, priority),
                resolutionDueAt = TicketSla.resolutionDue(at, priority),
                slaAlertedAt = null,
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
            priority: TicketPriority,
            assigneeId: UUID?,
            assigneeName: String?,
            workOrderId: UUID?,
            workOrderCode: String?,
            openedAt: Instant,
            lastActivityAt: Instant,
            firstResponseAt: Instant?,
            responseDueAt: Instant?,
            resolutionDueAt: Instant,
            slaAlertedAt: Instant?,
            resolvedAt: Instant?,
            closedAt: Instant?,
        ) = Ticket(
            id, tenantId, code, customerId, customerName, category, subject, description,
            status, priority, assigneeId, assigneeName, workOrderId, workOrderCode,
            openedAt, lastActivityAt, firstResponseAt, responseDueAt, resolutionDueAt,
            slaAlertedAt, resolvedAt, closedAt,
        )
    }
}
