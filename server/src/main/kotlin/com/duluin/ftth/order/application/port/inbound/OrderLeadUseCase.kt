package com.duluin.ftth.order.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.order.application.port.outbound.OrderLeadFilter
import com.duluin.ftth.order.domain.model.LeadSource
import com.duluin.ftth.order.domain.model.LeadStatus
import com.duluin.ftth.order.domain.model.OrderLead
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Meja calon pelanggan untuk OPERATOR. Pintu publik/anonim belum ada di fase ini. */
interface OrderLeadUseCase {
    fun create(command: CreateOrderLeadCommand): OrderLeadView
    fun update(id: UUID, command: UpdateOrderLeadCommand): OrderLeadView
    fun changeStatus(id: UUID, target: LeadStatus): OrderLeadView
    fun get(id: UUID): OrderLeadView
    fun search(filter: OrderLeadFilter, pageRequest: PageRequest): Page<OrderLeadView>

    /**
     * Promosi calon pelanggan jadi pelanggan sungguhan. Satu transaksi dan idempoten:
     * memanggilnya dua kali mengembalikan pelanggan yang sama, bukan membuat pelanggan kedua
     * untuk orang yang sama.
     */
    fun promote(id: UUID, command: PromoteOrderLeadCommand): PromotedLeadView
}

data class CreateOrderLeadCommand(
    val name: String,
    val phone: String,
    val email: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val interestedPlanId: UUID? = null,
    val source: LeadSource = LeadSource.OPERATOR,
    val notes: String? = null,
)

/** Setiap field null = "pertahankan yang ada"; PATCH parsial tak boleh mengosongkan data. */
data class UpdateOrderLeadCommand(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val interestedPlanId: UUID? = null,
    val notes: String? = null,
)

/**
 * [planId] null = pakai paket yang diminati calon pelanggan. Kalau dua-duanya kosong promosi
 * ditolak: module `customer` mewajibkan pelanggan lahir bersama langganannya, jadi tak ada
 * jalan membuat pelanggan tanpa memilih paket.
 */
data class PromoteOrderLeadCommand(
    val planId: UUID? = null,
    /** Kosong = server membuat kode pelanggan berurut otomatis. */
    val code: String? = null,
    val areaId: UUID? = null,
    val idCardNumber: String? = null,
    val monthlyFeeOverride: BigDecimal? = null,
)

/**
 * [subscriptionId] null pada pemanggilan ulang: lead sudah CONVERTED sebelumnya dan kita hanya
 * memegang id pelanggannya, bukan id langganan yang lahir saat itu.
 */
data class PromotedLeadView(
    val leadId: UUID,
    val customerId: UUID,
    val subscriptionId: UUID?,
    val alreadyConverted: Boolean,
)

data class OrderLeadView(
    val id: UUID,
    val name: String,
    val phone: String,
    val email: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val interestedPlanId: UUID?,
    val source: LeadSource,
    val status: LeadStatus,
    val convertedCustomerId: UUID?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(lead: OrderLead) = OrderLeadView(
            id = lead.id,
            name = lead.name,
            phone = lead.phone,
            email = lead.email,
            address = lead.address,
            latitude = lead.latitude,
            longitude = lead.longitude,
            interestedPlanId = lead.interestedPlanId,
            source = lead.source,
            status = lead.status,
            convertedCustomerId = lead.convertedCustomerId,
            notes = lead.notes,
            createdAt = lead.createdAt,
            updatedAt = lead.updatedAt,
        )
    }
}
