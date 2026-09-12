package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.order.OperationCommand
import com.duluin.ftth.order.application.port.inbound.OrderPortalAutomationUseCase
import com.duluin.ftth.order.application.port.inbound.ReleaseSystemFlagCommand
import com.duluin.ftth.order.application.port.inbound.SystemFlagCommand
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderAuditStore
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.Order
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * Satu-satunya pintu bagi OTOMASI untuk menyentuh penanda portal.
 *
 * Semua jalur di sini WAJIB tidak melempar untuk keadaan yang wajar — lihat
 * [OrderPortalAutomationUseCase] untuk alasannya, dan jangan membalikkannya "supaya kesalahan
 * terlihat": yang akan terlihat bukan kesalahannya, melainkan UnexpectedRollbackException di
 * pemanggil yang sama sekali tak berhubungan.
 */
@Service
class OrderPortalAutomationService(
    private val orders: OrderRepository,
    private val audit: OrderAuditStore,
) : OrderPortalAutomationUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun applySystemFlag(command: SystemFlagCommand): Boolean =
        TenantContext.runAs(command.tenantId) {
            val operation = operationOf(command.source, command.reference, command.flag.name, command.narrative.name)
            val order = load(command.tenantId, command.orderId, command.source) ?: return@runAs false
            if (!order.applySystemFlag(command.flag, command.narrative.sentence, command.actorId, operation)) {
                return@runAs false
            }
            orders.save(order)
            record(order, ORDER_FLAGGED, null, order.portalFlag?.name ?: order.status.name, order.portalFlagReason, command.actorId, operation)
            true
        }

    @Transactional
    override fun releaseSystemFlag(command: ReleaseSystemFlagCommand): Boolean =
        TenantContext.runAs(command.tenantId) {
            val operation = operationOf(command.source, command.reference, command.flag.name, "RELEASE")
            val order = load(command.tenantId, command.orderId, command.source) ?: return@runAs false
            val previous = order.portalFlag?.name
            if (!order.releaseSystemFlag(command.flag, command.actorId, operation)) return@runAs false
            orders.save(order)
            record(order, ORDER_UNFLAGGED, previous, order.status.name, null, command.actorId, operation)
            true
        }

    /*
     * `TenantContext.runAs` dipasang SENGAJA meski kebanyakan pemanggil sudah berada di dalamnya:
     * jalur otomatis berjalan di thread worker outbox dan di listener event, dan di sana GUC
     * app.tenant_id yang tidak ter-set membuat query native/RLS memulangkan NOL baris TANPA
     * error. Gejalanya identik dengan "pesanannya memang tidak ada", dan itu sudah pernah
     * menghabiskan waktu berjam-jam di repo ini. runAs bersarang tidak berbahaya.
     */
    private fun load(tenantId: java.util.UUID, orderId: java.util.UUID, source: String): Order? {
        val order = orders.find(orderId)?.takeIf { it.tenantId == tenantId }
        // Tidak melempar: pesanan yang sudah dihapus/pindah tenant bukan alasan membatalkan
        // transaksi pemanggil. Tapi ia WAJIB terlihat di log — otomasi yang diam-diam tak
        // pernah jalan adalah bug yang paling mahal dicari.
        if (order == null) log.warn("Penanda portal otomatis dilewati: pesanan {} tidak ditemukan (sumber={})", orderId, source)
        return order
    }

    @Suppress("LongParameterList")
    private fun record(
        order: Order,
        eventType: String,
        fromStatus: String?,
        toStatus: String,
        reason: String?,
        actorId: java.util.UUID?,
        operation: OperationCommand,
    ) {
        // Kolom status dipakai untuk PENANDA-nya, bukan status pesanan — konvensi yang sama
        // dengan OrderAttentionService supaya timeline pesanan tidak punya dua dialek.
        audit.append(
            OrderAuditEntry(
                tenantId = order.tenantId,
                orderId = order.id,
                revision = order.revision,
                eventType = eventType,
                fromStatus = fromStatus,
                toStatus = toStatus,
                reason = reason,
                actorId = actorId,
                operationNamespace = operation.namespace,
                operationKey = operation.key,
                payloadHash = operation.payloadHash,
                occurredAt = Instant.now(),
            ),
        )
    }

    /**
     * Kunci operasi berasal dari SUMBER kejadiannya (id kunjungan, kunci operasi saga), bukan
     * dari UUID acak: kunjungan yang sama yang dilaporkan ulang harus menghasilkan kunci yang
     * sama, sehingga `last_operation_key` di pesanan menunjuk penyebabnya dan bukan sekadar
     * "entah, otomatis".
     *
     * SENGAJA tidak ditulis ke `order_operation`: penjagaan replay-nya sudah ada di agregat
     * ([Order.applySystemFlag] memulangkan false kalau tak ada yang berubah), dan menambah
     * penjaga kedua untuk janji yang sama berarti dua tempat yang akan menyimpang diam-diam.
     */
    private fun operationOf(source: String, reference: String, vararg parts: String): OperationCommand {
        val key = "$source:$reference".take(MAX_OPERATION_KEY)
        val canonical = (listOf(NAMESPACE, source, reference) + parts).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return OperationCommand(NAMESPACE, key, digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val NAMESPACE = "order-automation"

        /** Sepadan dengan `order_record.last_operation_key varchar(240)`. */
        const val MAX_OPERATION_KEY = 240
        const val ORDER_FLAGGED = "ORDER_FLAGGED"
        const val ORDER_UNFLAGGED = "ORDER_UNFLAGGED"
    }
}
