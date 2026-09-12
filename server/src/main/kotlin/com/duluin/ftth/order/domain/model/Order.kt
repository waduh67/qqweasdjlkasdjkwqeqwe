package com.duluin.ftth.order.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.order.*
import com.duluin.ftth.common.domain.UuidV7
import java.util.UUID

enum class OrderStatus { DRAFT, SUBMITTED, ACCEPTED, SCHEDULED, FULFILLING, FULFILLED, CANCELLED, REJECTED }

/**
 * Penanda yang MENIMPA tampilan status di portal tanpa mengubah status agregat.
 *
 * Dibuat ortogonal terhadap [OrderStatus] (keputusan V184): kalau ia jadi status agregat,
 * setiap mesin transisi — termasuk jalur saga fulfillment — harus tahu cara keluar dari sana,
 * dan setiap penanda baru melipatgandakan pasangan transisi yang harus dijaga.
 *
 * ATURAN BISNIS kapan penanda ini dipasang BELUM diputuskan pemilik produk. Tidak ada satu pun
 * otomasi yang memasangnya; untuk sekarang hanya operator, lewat `POST /api/orders/{id}/attention`.
 */
enum class OrderPortalFlag { WAITING_CUSTOMER, REQUIRES_ATTENTION }

@Suppress("LongParameterList")
class Order private constructor(
    val id: UUID,
    val tenantId: UUID,
    /**
     * Pemesan adalah TEPAT SATU dari [customerId] atau [leadId] — pelanggan yang sudah
     * terdaftar, atau calon pelanggan yang belum. Keduanya terisi berarti satu orang punya
     * dua identitas dan laporan menghitungnya dua kali; keduanya kosong berarti pesanan
     * yatim yang tak bisa ditindaklanjuti. Constraint yang sama ditegakkan di DB (V177).
     */
    val customerId: UUID?,
    val leadId: UUID?,
    /** Nomor yang bisa dibacakan lewat telepon, `ORD-YYMM-NNNN`. Dibuat di luar agregat (lihat OrderNumberGenerator). */
    val orderNumber: String,
    val lines: List<OrderLineCommand>,
    val serviceAddress: ServiceAddress,
    var appointment: Appointment?,
    var status: OrderStatus,
    var cancellationReason: String?,
    var rejectionReason: String?,
    var revision: Long,
    var lastActorId: UUID?,
    var lastOperation: OperationCommand,
    /** Lihat [OrderPortalFlag]. Default null = portal menampilkan status apa adanya. */
    var portalFlag: OrderPortalFlag? = null,
    var portalFlagReason: String? = null,
) {
    companion object {
        fun create(command: CreateOrderCommand, tenantId: UUID, actorId: UUID?, orderNumber: String): Order {
            if (command.lines.isEmpty()) throw ValidationException("Order harus memiliki line")
            command.lines.forEach {
                if (it.quantity <= 0 || it.description.isBlank()) throw ValidationException("Line order tidak valid")
            }
            if ((command.customerId == null) == (command.leadId == null)) {
                throw ValidationException("Order harus punya tepat satu pemesan: pelanggan atau calon pelanggan")
            }
            if (orderNumber.isBlank()) throw ValidationException("Nomor pesanan wajib diisi")
            if (command.serviceAddress.address.isBlank() || command.serviceAddress.city.isBlank() || command.serviceAddress.postalCode.isBlank()) {
                throw ValidationException("Alamat layanan tidak lengkap")
            }
            val latitude = command.serviceAddress.latitude
            val longitude = command.serviceAddress.longitude
            if ((latitude == null) != (longitude == null)) throw ValidationException("Koordinat harus berpasangan")
            if (latitude != null && longitude != null && (latitude !in -90.0..90.0 || longitude !in -180.0..180.0)) {
                throw ValidationException("Koordinat layanan tidak valid")
            }
            command.appointment?.let {
                if (!it.startsAt.isBefore(it.endsAt)) throw ValidationException("Appointment tidak valid")
            }
            return Order(
                UuidV7.generate(), tenantId, command.customerId, command.leadId, orderNumber,
                command.lines.toList(), command.serviceAddress,
                command.appointment, OrderStatus.DRAFT, null, null, 0, actorId, command.operation,
            )
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            customerId: UUID?,
            leadId: UUID?,
            orderNumber: String,
            lines: List<OrderLineCommand>,
            serviceAddress: ServiceAddress,
            appointment: Appointment?,
            status: OrderStatus,
            cancellationReason: String?,
            rejectionReason: String?,
            revision: Long,
            lastActorId: UUID?,
            lastOperation: OperationCommand,
            portalFlag: OrderPortalFlag? = null,
            portalFlagReason: String? = null,
        ) = Order(id, tenantId, customerId, leadId, orderNumber, lines, serviceAddress, appointment, status,
            cancellationReason, rejectionReason, revision, lastActorId, lastOperation, portalFlag, portalFlagReason)

        private const val MAX_FLAG_REASON = 300

        /** Ujung perjalanan pesanan: tak menunggu siapa pun lagi, jadi tak boleh bertanda. */
        private val TERMINAL = setOf(OrderStatus.FULFILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED)
    }

    fun transition(command: OrderTransitionCommand, actorId: UUID?) {
        if (command.expectedRevision != revision) throw ConflictException("Revision order sudah berubah")
        val target = when (command.transition) {
            OrderTransition.SUBMIT -> requireFrom(OrderStatus.SUBMITTED, OrderStatus.DRAFT)
            OrderTransition.ACCEPT -> requireFrom(OrderStatus.ACCEPTED, OrderStatus.SUBMITTED)
            OrderTransition.SCHEDULE -> requireFrom(OrderStatus.SCHEDULED, OrderStatus.ACCEPTED)
            OrderTransition.START_FULFILLING -> requireFrom(OrderStatus.FULFILLING, OrderStatus.SCHEDULED)
            /*
             * FULFILL diterima juga dari ACCEPTED dan SCHEDULED, bukan hanya FULFILLING.
             *
             * Sejak P5.4/P5.6 hidup pesanan SETELAH diterima dikemudikan oleh work order-nya,
             * bukan oleh operator yang menekan tombol di layar pesanan: approval WO PSB yang
             * memicu transisi ini. Operator TIDAK diwajibkan menekan SCHEDULE lalu
             * START_FULFILLING lebih dulu — dan memang sering tidak, karena penjadwalan
             * sesungguhnya terjadi di work order.
             *
             * Kalau tetap dipaksa lewat FULFILLING, efek `ORDER` di saga akan ditolak dan
             * berakhir sebagai `ORDER_EFFECT_REJECTED` yang menuntut rekonsiliasi manual:
             * pemasangan pelanggan sudah selesai dan sudah disetujui, tapi portalnya
             * selamanya berkata "sedang ditinjau". Kenyataan lapangan yang menang.
             */
            OrderTransition.FULFILL -> requireFrom(
                OrderStatus.FULFILLED, OrderStatus.FULFILLING, OrderStatus.SCHEDULED, OrderStatus.ACCEPTED,
            )
            OrderTransition.CANCEL -> requireFrom(OrderStatus.CANCELLED, OrderStatus.SUBMITTED, OrderStatus.ACCEPTED, OrderStatus.SCHEDULED)
            OrderTransition.REJECT -> requireFrom(OrderStatus.REJECTED, OrderStatus.SUBMITTED)
        }
        if (command.transition == OrderTransition.CANCEL && command.reason.isNullOrBlank()) throw ValidationException("Alasan pembatalan wajib diisi")
        if (command.transition == OrderTransition.REJECT && command.reason.isNullOrBlank()) throw ValidationException("Alasan penolakan wajib diisi")
        if (command.transition == OrderTransition.SCHEDULE && command.appointment == null) throw ValidationException("Appointment wajib diisi")
        status = target
        if (command.transition == OrderTransition.CANCEL) cancellationReason = command.reason
        if (command.transition == OrderTransition.REJECT) rejectionReason = command.reason
        if (command.appointment != null) appointment = command.appointment
        // Pesanan yang sudah sampai ujungnya tak lagi menunggu siapa pun. Penanda yang
        // tertinggal akan membuat portal terus berkata "menunggu Anda" pada pesanan yang
        // pemasangannya sudah selesai — dan pelanggan menelepon menanyakan apa lagi yang kurang.
        if (target in TERMINAL) clearFlag()
        revision += 1
        lastActorId = actorId
        lastOperation = command.operation
    }

    /**
     * Pasang penanda portal. Bukan transisi status: pesanan tetap di tempatnya dan tetap bisa
     * melanjutkan alurnya — yang berubah hanya KALIMAT yang dibaca pelanggan di halaman lacak.
     *
     * [reason] ikut tampil ke pelanggan, jadi ia harus ditulis untuk dibaca pelanggan
     * ("menunggu konfirmasi titik pemasangan"), bukan catatan internal.
     */
    fun flagForPortal(flag: OrderPortalFlag, reason: String?, actorId: UUID?, operation: OperationCommand) {
        if (status in TERMINAL || status == OrderStatus.DRAFT) {
            throw ConflictException("Pesanan berstatus ${status.name} tidak bisa diberi penanda portal")
        }
        val trimmed = reason?.trim()?.ifBlank { null }
        if (trimmed != null && trimmed.length > MAX_FLAG_REASON) {
            throw ValidationException("Alasan penanda maksimal $MAX_FLAG_REASON karakter")
        }
        portalFlag = flag
        portalFlagReason = trimmed
        bump(actorId, operation)
    }

    /** Lepas penanda; pesanan kembali menampilkan status sesungguhnya. */
    fun clearPortalFlag(actorId: UUID?, operation: OperationCommand) {
        if (portalFlag == null) throw ConflictException("Pesanan ini tidak sedang bertanda")
        clearFlag()
        bump(actorId, operation)
    }

    private fun clearFlag() {
        portalFlag = null
        portalFlagReason = null
    }

    private fun bump(actorId: UUID?, operation: OperationCommand) {
        revision += 1
        lastActorId = actorId
        lastOperation = operation
    }

    private fun requireFrom(target: OrderStatus, vararg expected: OrderStatus): OrderStatus {
        if (status !in expected) throw ConflictException("Transisi ${status.name} ke ${target.name} tidak diizinkan")
        return target
    }
}

/**
 * Pemetaan status agregat → kosakata yang aman dibaca PELANGGAN. `null` = pesanan belum layak
 * tampil di portal: DRAFT masih milik operator dan belum tentu pernah benar-benar dipesan.
 *
 * Berdiri sendiri (bukan hanya di dalam [toPortalView]) karena ada DUA pembaca dengan bentuk
 * data berbeda: portal pelanggan yang memegang agregat penuh, dan halaman lacak publik yang
 * hanya memegang satu baris hasil query. Dua salinan aturan ini akan berbeda DIAM-DIAM begitu
 * status baru ditambahkan, dan salah satu dari keduanya akan membocorkan status internal.
 *
 * [flag] MENIMPA status bila ada: pelanggan yang pesanannya menunggu jawabannya sendiri perlu
 * membaca itu, bukan "sedang ditinjau" yang membuatnya menunggu balik.
 */
fun portalStatusOf(status: OrderStatus, flag: OrderPortalFlag?): PortalOrderStatus? {
    val base = when (status) {
        OrderStatus.DRAFT -> null
        OrderStatus.SUBMITTED -> PortalOrderStatus.RECEIVED
        OrderStatus.ACCEPTED -> PortalOrderStatus.REVIEWING
        OrderStatus.SCHEDULED -> PortalOrderStatus.SCHEDULED
        OrderStatus.FULFILLING -> PortalOrderStatus.IN_PROGRESS
        OrderStatus.FULFILLED -> PortalOrderStatus.COMPLETED
        OrderStatus.CANCELLED, OrderStatus.REJECTED -> PortalOrderStatus.CANCELLED
    } ?: return null
    return when (flag) {
        OrderPortalFlag.WAITING_CUSTOMER -> PortalOrderStatus.WAITING_CUSTOMER
        OrderPortalFlag.REQUIRES_ATTENTION -> PortalOrderStatus.REQUIRES_ATTENTION
        null -> base
    }
}

/**
 * Pandangan pesanan yang boleh dilihat PELANGGAN. `null` = pesanan belum layak tampil di
 * portal: DRAFT masih milik operator dan belum tentu pernah benar-benar dipesan.
 *
 * Tinggal di domain (bukan di adapter) karena portal dan konsol harus melihat pemetaan
 * status yang SAMA — dua salinan aturan ini akan berbeda diam-diam begitu status baru
 * ditambahkan, dan pelanggan akan melihat status yang tak pernah dimaksudkan bocor.
 */
fun Order.toPortalView(): PortalOrderView? {
    val portalStatus = portalStatusOf(status, portalFlag) ?: return null
    return PortalOrderView(
        id = id,
        orderNumber = orderNumber,
        status = portalStatus,
        lines = lines.map { OrderLineView(it.catalogItemId, it.description, it.quantity) },
        // Koordinat SENGAJA tidak ikut: portal hanya perlu memastikan alamatnya benar.
        serviceAddress = PortalServiceAddress(serviceAddress.address, serviceAddress.city, serviceAddress.postalCode),
        appointment = appointment,
        revision = revision,
    )
}
