package com.duluin.ftth.order.application.service

import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.order.OrderApi
import com.duluin.ftth.order.OrderTransition
import com.duluin.ftth.order.OrderTransitionCommand
import com.duluin.ftth.order.OrderView
import com.duluin.ftth.order.application.port.inbound.AcceptOrderCommand
import com.duluin.ftth.order.application.port.inbound.OrderAcceptanceUseCase
import com.duluin.ftth.order.application.port.inbound.OrderLeadUseCase
import com.duluin.ftth.order.application.port.outbound.OrderRepository
import com.duluin.ftth.order.domain.model.Order
import com.duluin.ftth.workorder.RaisePsbCommand
import com.duluin.ftth.workorder.WorkorderApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Penerimaan pesanan: terima → promosikan calon pelanggan → buka WO PSB, dalam SATU transaksi.
 *
 * Urutannya penting dan tidak boleh dibalik. Pelanggan harus ada lebih dulu karena work order
 * menuntut pelanggan yang sah (`requireCustomerExists` di `WorkOrderService.create`). Dan karena
 * ketiganya satu transaksi, kegagalan membuka WO — teknisi yang ditunjuk sudah nonaktif, area
 * tak dikenal — MEMBATALKAN promosi pelanggannya juga. Itu memang yang diinginkan: lebih baik
 * operator mengulang dari awal daripada meninggalkan pelanggan yang lahir tanpa pekerjaan
 * pemasangan, yang tak muncul di antrean mana pun dan hanya ketahuan saat ia menelepon.
 *
 * Module `workorder` disentuh HANYA lewat [WorkorderApi] di base package-nya — aturan yang sama
 * dengan `OrderLeadService` terhadap `customer`, dan `ModularityTests` menegakkannya.
 */
@Service
class OrderAcceptanceService(
    private val orders: OrderApi,
    private val repository: OrderRepository,
    private val leads: OrderLeadUseCase,
    private val catalog: CatalogApi,
    private val customers: CustomerApi,
    private val workOrders: WorkorderApi,
    private val currentUser: CurrentUserProvider,
) : OrderAcceptanceUseCase {

    @Transactional
    override fun accept(command: AcceptOrderCommand): OrderView {
        val tenantId = currentUser.current().tenantId
        /*
         * Diperiksa SEBELUM transisi dijalankan. `OrderApi.transition` sudah idempoten menurut
         * operation key, tapi promosi pelanggan dan pembukaan WO di bawahnya TIDAK — tanpa
         * gerbang ini, operator yang menekan "Terima" dua kali (atau klien yang mengulang POST
         * karena timeout) akan membuka WO PSB KEDUA untuk pemasangan yang sama, dan teknisi
         * berangkat dua kali ke rumah yang sama.
         */
        val replayed = repository.findOutcome(tenantId, command.operation.namespace, command.operation.key) != null

        val accepted = orders.transition(
            OrderTransitionCommand(
                orderId = command.orderId,
                transition = OrderTransition.ACCEPT,
                expectedRevision = command.expectedRevision,
                operation = command.operation,
            ),
        )
        if (replayed) return accepted

        val order = repository.find(command.orderId)?.takeIf { it.tenantId == tenantId }
            ?: throw NotFoundException("Order tidak ditemukan")
        val (customerId, freshSubscriptionId) = resolveCustomer(order, command)
        val subscriptionId = freshSubscriptionId ?: customers.findSubscriptionByCustomer(customerId)?.id

        workOrders.raisePsb(
            RaisePsbCommand(
                customerId = customerId,
                subscriptionId = subscriptionId,
                title = "Pasang baru — pesanan ${order.orderNumber}",
                description = order.serviceAddress.address,
                areaId = command.promotion.areaId,
                // Janji temu pada pesanan menjadi jadwal awal WO; dispatcher tetap bisa menggesernya.
                scheduledAt = command.appointmentStartsAt ?: order.appointment?.startsAt,
                assignees = command.assignees,
                // Inilah taut yang membuat approval WO kelak MENUTUP pesanan ini (efek `ORDER`,
                // P5.6). Tanpanya pesanan menggantung "sedang ditinjau" meski sudah terpasang.
                orderId = order.id,
            ),
        )
        return accepted
    }

    /**
     * Pesanan punya TEPAT SATU pemesan (lihat [Order]). Kalau ia masih calon pelanggan, di
     * sinilah ia menjadi pelanggan sungguhan — dan promosi itu sendiri sudah idempoten, jadi
     * lead yang entah bagaimana sudah dipromosikan tak melahirkan pelanggan kedua.
     *
     * Nilai kedua adalah id langganan yang BARU lahir bersama pelanggannya; pada pemanggilan
     * ulang ia null dan pemanggil harus mencarinya sendiri.
     */
    private fun resolveCustomer(order: Order, command: AcceptOrderCommand): Pair<UUID, UUID?> {
        order.customerId?.let { return it to null }
        val leadId = order.leadId
            ?: throw ValidationException("Pesanan tidak punya pemesan; tidak bisa diterima")
        /*
         * Paket diambil dari BARIS PESANAN bila operator tak menyebutnya sendiri. Baris pesanan
         * adalah yang benar-benar dipesan, sedangkan `interestedPlanId` pada calon pelanggan
         * cuma ketertarikan awal yang sering sudah berubah saat operator menelepon balik —
         * memakainya berarti pelanggan berlangganan paket yang bukan yang ia pesan.
         *
         * Baris pesanan dicocokkan dulu ke katalog paket: pesanan buatan operator boleh memuat
         * barang gudang (ONT, drop core) di baris pertamanya, dan meneruskan id barang sebagai
         * id paket akan MENGGAGALKAN seluruh transaksi penerimaan — termasuk transisi yang
         * sebetulnya sah — dengan pesan "paket tidak ditemukan" yang tak menunjuk sebabnya.
         */
        val planFromLines = order.lines.map { it.catalogItemId }
            .firstOrNull { catalog.findPlanCommercial(it) != null }
        val promotion = command.promotion.takeIf { it.planId != null }
            ?: command.promotion.copy(planId = planFromLines)
        val promoted = leads.promote(leadId, promotion)
        return promoted.customerId to promoted.subscriptionId
    }
}
