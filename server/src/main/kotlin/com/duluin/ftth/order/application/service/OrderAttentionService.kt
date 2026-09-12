package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.order.OrderApi
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.application.port.inbound.FlagOrderCommand
import com.duluin.ftth.order.application.port.inbound.OrderAttentionUseCase
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Pemasangan & pelepasan penanda portal. Lihat
 * [com.duluin.ftth.order.application.port.inbound.OrderAttentionUseCase] untuk alasan mengapa
 * belum ada satu pun pemicu otomatis.
 */
@Service
class OrderAttentionService(
    private val orders: OrderRepository,
    private val api: OrderApi,
    private val audit: OrderAuditStore,
    private val currentUser: CurrentUserProvider,
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

    private companion object {
        const val ORDER_FLAGGED = "ORDER_FLAGGED"
        const val ORDER_UNFLAGGED = "ORDER_UNFLAGGED"
    }
}
