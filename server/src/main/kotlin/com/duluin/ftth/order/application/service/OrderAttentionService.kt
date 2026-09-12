package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.order.OrderApi
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.application.port.inbound.FlagOrderCommand
import com.duluin.ftth.order.application.port.inbound.MarkUnreachableCommand
import com.duluin.ftth.order.application.port.inbound.OrderAttentionUseCase
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.Order
import com.duluin.ftth.order.domain.model.OrderPortalFlag
import com.duluin.ftth.order.domain.model.OrderPortalNarrative
import com.duluin.ftth.order.domain.model.OrderStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Pemasangan & pelepasan penanda portal oleh OPERATOR. Jalur otomatisnya ada di
 * [OrderPortalAutomationService].
 */
@Service
class OrderAttentionService(
    private val orders: OrderRepository,
    private val api: OrderApi,
    private val audit: OrderAuditStore,
    private val currentUser: CurrentUserProvider,
    /**
     * Berapa lama pesanan yang SUDAH DITERIMA boleh diam tanpa janji temu sebelum tombol
     * "tidak bisa dihubungi" boleh ditekan.
     *
     * Ada ambangnya SENGAJA. Tanpa itu, tombol ini akan ditekan pada pesanan yang baru diterima
     * lima menit lalu — dan pelanggan yang baru saja memesan membuka halaman lacaknya lalu
     * membaca bahwa dialah yang tidak bisa dihubungi. Sekali itu terjadi, kepercayaan pada
     * seluruh halaman lacak hilang.
     *
     * Bisa diatur karena rentang waktu yang wajar berbeda antar penyedia: yang menjadwalkan
     * pemasangan H+1 tidak sabar tiga hari.
     */
    @Value("\${ftth.order.unreachable-silence:P3D}") private val unreachableSilence: Duration,
) : OrderAttentionUseCase {

    @Transactional
    override fun flag(command: FlagOrderCommand): OrderView {
        val user = currentUser.current()
        val operation = command.operation
        require(operation.namespace.isNotBlank() && operation.key.isNotBlank() && operation.payloadHash.isNotBlank())

        val prior = orders.findOutcome(user.tenantId, operation.namespace, operation.key)
        if (prior != null) {
            if (prior.hash != operation.payloadHash) throw ConflictException("Operation key sudah dipakai untuk payload berbeda")
            return prior.value
        }

        val order = orders.find(command.orderId)?.takeIf { it.tenantId == user.tenantId }
            ?: throw NotFoundException("Order tidak ditemukan")
        val previousFlag = order.portalFlag?.name
        if (command.flag == null) order.clearPortalFlag(user.userId, operation)
        else order.flagForPortal(command.flag, command.reason, user.userId, operation)
        orders.save(order)

        /*
         * Riwayat ditulis di transaksi yang SAMA, alasan yang sama dengan transisi status:
         * penanda yang terpasang tanpa jejak adalah penanda yang tak bisa dipertanggungjawabkan
         * ketika pelanggan bertanya "kenapa pesanan saya berkata menunggu saya?".
         *
         * Outbox SENGAJA tidak diisi: status agregatnya tidak berpindah, dan menerbitkan
         * `OrderStateChanged` dengan `from` == `to` akan menipu setiap konsumen hilir yang
         * memicu pekerjaan berdasarkan perpindahan status.
         */
        audit.append(
            OrderAuditEntry(
                tenantId = order.tenantId,
                orderId = order.id,
                revision = order.revision,
                eventType = if (command.flag == null) ORDER_UNFLAGGED else ORDER_FLAGGED,
                // Kolom status dipakai untuk PENANDA-nya, bukan status pesanan: itulah yang
                // berubah di sini, dan itulah yang ingin dibaca kembali saat menelusuri riwayat.
                fromStatus = previousFlag,
                toStatus = order.portalFlag?.name ?: order.status.name,
                reason = command.reason?.trim()?.ifBlank { null },
                actorId = user.userId,
                operationNamespace = operation.namespace,
                operationKey = operation.key,
                payloadHash = operation.payloadHash,
                occurredAt = Instant.now(),
            ),
        )

        val view = api.find(order.id) ?: throw NotFoundException("Order tidak ditemukan")
        orders.saveOutcome(user.tenantId, operation.namespace, operation.key, operation.payloadHash, view)
        return view
    }

    @Transactional
    override fun markUnreachable(command: MarkUnreachableCommand): OrderView {
        val user = currentUser.current()
        val operation = command.operation
        require(operation.namespace.isNotBlank() && operation.key.isNotBlank() && operation.payloadHash.isNotBlank())

        val prior = orders.findOutcome(user.tenantId, operation.namespace, operation.key)
        if (prior != null) {
            if (prior.hash != operation.payloadHash) throw ConflictException("Operation key sudah dipakai untuk payload berbeda")
            return prior.value
        }

        val order = orders.find(command.orderId)?.takeIf { it.tenantId == user.tenantId }
            ?: throw NotFoundException("Order tidak ditemukan")

        /*
         * Tiga syarat, dan ketiganya menjawab pertanyaan yang sama: "apakah benar-benar TIDAK ADA
         * yang bisa kami lakukan selain menunggu pelanggan menghubungi kami?"
         *
         * Kalau pesanannya belum diterima, yang menghambat adalah peninjauan KAMI. Kalau janji
         * temunya sudah ada, pelanggan sudah berhasil dihubungi — menandainya "tak bisa
         * dihubungi" hanya akan membatalkan janji temu yang sudah dia catat di kalendernya.
         */
        if (order.status != OrderStatus.ACCEPTED) {
            throw ConflictException("Hanya pesanan berstatus ACCEPTED yang bisa ditandai tidak bisa dihubungi")
        }
        if (order.appointment != null) {
            throw ConflictException("Pesanan ini sudah punya janji temu, jadi pelanggannya sudah berhasil dihubungi")
        }
        acceptedAt(order)?.let { since ->
            val silent = Duration.between(since, Instant.now())
            if (silent < unreachableSilence) {
                throw ConflictException(
                    "Pesanan ini baru diterima ${silent.toHours()} jam lalu; " +
                        "tunggu minimal ${unreachableSilence.toDays()} hari sebelum menandainya tidak bisa dihubungi",
                )
            }
        }

        order.flagForPortal(OrderPortalFlag.WAITING_CUSTOMER, OrderPortalNarrative.CUSTOMER_UNREACHABLE.sentence, user.userId, operation)
        orders.save(order)

        audit.append(
            OrderAuditEntry(
                tenantId = order.tenantId,
                orderId = order.id,
                revision = order.revision,
                eventType = ORDER_FLAGGED,
                fromStatus = null,
                toStatus = order.portalFlag?.name ?: order.status.name,
                // Catatan operator masuk RIWAYAT, bukan portal. Yang dibaca pelanggan adalah
                // kalimat baku yang baru saja dipasang di atas.
                reason = command.note?.trim()?.ifBlank { null } ?: UNREACHABLE_AUDIT_NOTE,
                actorId = user.userId,
                operationNamespace = operation.namespace,
                operationKey = operation.key,
                payloadHash = operation.payloadHash,
                occurredAt = Instant.now(),
            ),
        )

        val view = api.find(order.id) ?: throw NotFoundException("Order tidak ditemukan")
        orders.saveOutcome(user.tenantId, operation.namespace, operation.key, operation.payloadHash, view)
        return view
    }

    /**
     * Kapan pesanan ini DITERIMA, dibaca dari riwayatnya. `null` = tak ada jejak penerimaan sama
     * sekali — pesanan lama dari sebelum V178 menghidupkan `order_audit`. Untuk baris seperti itu
     * ambang waktunya TIDAK ditegakkan: menolak semua pesanan lama selamanya jauh lebih merusak
     * daripada sesekali mengizinkan penandaan yang terlalu cepat pada data warisan.
     */
    private fun acceptedAt(order: Order): Instant? =
        audit.timeline(order.tenantId, order.id).lastOrNull { it.toStatus == OrderStatus.ACCEPTED.name }?.occurredAt

    private companion object {
        const val ORDER_FLAGGED = "ORDER_FLAGGED"
        const val ORDER_UNFLAGGED = "ORDER_UNFLAGGED"
        const val UNREACHABLE_AUDIT_NOTE = "Operator menandai pelanggan tidak bisa dihubungi"
    }
}
