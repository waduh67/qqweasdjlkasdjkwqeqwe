package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.order.application.port.outbound.OrderAuditEntry
import com.duluin.ftth.order.application.port.outbound.OrderListRow
import com.duluin.ftth.order.application.port.outbound.OrderSearchFilter
import com.duluin.ftth.order.domain.model.OrderPortalFlag
import com.duluin.ftth.order.domain.model.OrderPortalFlagSource
import com.duluin.ftth.order.domain.model.OrderStatus
import java.time.Instant
import java.util.UUID

/** Sisi BACA pesanan untuk back-office: antrean, pencarian, dan riwayat. */
interface OrderQuery {
    fun search(filter: OrderSearchFilter, pageRequest: PageRequest): Page<OrderSummaryView>
    fun timeline(orderId: UUID): List<OrderTimelineEntryView>
}

/**
 * [requesterName]/[requesterPhone] menyatukan dua jenis pemesan di satu kolom layar: prospek
 * datanya ikut di baris pesanan, pelanggan terdaftar diresolusi lewat `CustomerApi`. Null
 * berarti pelanggannya sudah tak bisa diresolusi (mis. terhapus) — barisnya tetap ditampilkan
 * supaya pesanan yatim kelihatan, bukan hilang diam-diam dari antrean.
 */
data class OrderSummaryView(
    val id: UUID,
    val orderNumber: String,
    val status: OrderStatus,
    val customerId: UUID?,
    val leadId: UUID?,
    val requesterName: String?,
    val requesterPhone: String?,
    val address: String,
    val city: String,
    val appointmentStartsAt: Instant?,
    /**
     * Penanda portal apa adanya. [portalFlagSource] ikut karena layar HARUS bisa membedakan tanda
     * yang dipasang operator dari yang dipasang sistem: yang pertama hanya boleh dicabut manusia,
     * yang kedua dicabut sendiri begitu keadaannya berubah. Tanpa pembedanya, operator mencabut
     * tanda sistem dan heran kenapa ia muncul lagi.
     */
    val portalFlag: OrderPortalFlag?,
    val portalFlagReason: String?,
    val portalFlagSource: OrderPortalFlagSource?,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(row: OrderListRow, requesterName: String?, requesterPhone: String?) = OrderSummaryView(
            id = row.id,
            orderNumber = row.orderNumber,
            status = row.status,
            customerId = row.customerId,
            leadId = row.leadId,
            requesterName = requesterName,
            requesterPhone = requesterPhone,
            address = row.address,
            city = row.city,
            appointmentStartsAt = row.appointmentStartsAt,
            portalFlag = row.portalFlag,
            portalFlagReason = row.portalFlagReason,
            portalFlagSource = row.portalFlagSource,
            revision = row.revision,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }
}

data class OrderTimelineEntryView(
    val revision: Long,
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    val reason: String?,
    val actorId: UUID?,
    val occurredAt: Instant,
) {
    companion object {
        fun from(entry: OrderAuditEntry) = OrderTimelineEntryView(
            revision = entry.revision,
            eventType = entry.eventType,
            fromStatus = entry.fromStatus,
            toStatus = entry.toStatus,
            reason = entry.reason,
            actorId = entry.actorId,
            occurredAt = entry.occurredAt,
        )
    }
}
