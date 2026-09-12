package com.duluin.ftth.order.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.order.application.port.inbound.CreateOrderLeadCommand
import com.duluin.ftth.order.application.port.inbound.OrderLeadUseCase
import com.duluin.ftth.order.application.port.inbound.OrderLeadView
import com.duluin.ftth.order.application.port.inbound.PromoteOrderLeadCommand
import com.duluin.ftth.order.application.port.inbound.PromotedLeadView
import com.duluin.ftth.order.application.port.inbound.UpdateOrderLeadCommand
import com.duluin.ftth.order.application.port.outbound.OrderLeadFilter
import com.duluin.ftth.order.application.port.outbound.OrderLeadRepository
import com.duluin.ftth.order.domain.model.LeadStatus
import com.duluin.ftth.order.domain.model.OrderLead
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Meja calon pelanggan sisi operator.
 *
 * Module `order` menyentuh module `customer` HANYA lewat [CustomerApi] di base package-nya —
 * `ModularityTests` menolak akses ke package internal, dan lebih penting lagi aturan kode
 * pelanggan & pembukaan langganan harus tetap ditegakkan pemiliknya, bukan disalin ke sini.
 */
@Service
class OrderLeadService(
    private val leads: OrderLeadRepository,
    private val currentUser: CurrentUserProvider,
    private val customers: CustomerApi,
) : OrderLeadUseCase {

    @Transactional
    override fun create(command: CreateOrderLeadCommand): OrderLeadView {
        val lead = OrderLead.create(
            tenantId = currentUser.current().tenantId,
            name = command.name,
            phone = command.phone,
            email = command.email,
            address = command.address,
            latitude = command.latitude,
            longitude = command.longitude,
            interestedPlanId = command.interestedPlanId,
            source = command.source,
            notes = command.notes,
        )
        leads.save(lead)
        return OrderLeadView.from(lead)
    }

    @Transactional
    override fun update(id: UUID, command: UpdateOrderLeadCommand): OrderLeadView {
        val lead = load(id)
        lead.updateProfile(
            name = command.name,
            phone = command.phone,
            email = command.email,
            address = command.address,
            latitude = command.latitude,
            longitude = command.longitude,
            interestedPlanId = command.interestedPlanId,
            notes = command.notes,
        )
        leads.save(lead)
        return OrderLeadView.from(lead)
    }

    @Transactional
    override fun changeStatus(id: UUID, target: LeadStatus): OrderLeadView {
        val lead = load(id)
        lead.changeStatus(target)
        leads.save(lead)
        return OrderLeadView.from(lead)
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): OrderLeadView = OrderLeadView.from(load(id))

    @Transactional(readOnly = true)
    override fun search(filter: OrderLeadFilter, pageRequest: PageRequest): Page<OrderLeadView> =
        leads.search(filter, pageRequest).map(OrderLeadView::from)

    /**
     * Satu transaksi: pelanggan lahir dan lead ditandai CONVERTED bersama-sama. Kalau dipisah,
     * kegagalan di tengah meninggalkan pelanggan tanpa jejak asalnya (atau lead yang mengaku
     * sudah dikonversi padahal pelanggannya tak pernah ada), dan percobaan kedua akan membuat
     * pelanggan kedua untuk orang yang sama.
     *
     * Idempoten: lead yang sudah CONVERTED langsung mengembalikan pelanggan yang sama tanpa
     * menyentuh module `customer` lagi — tombol yang ditekan dua kali tak melahirkan duplikat.
     */
    @Transactional
    override fun promote(id: UUID, command: PromoteOrderLeadCommand): PromotedLeadView {
        val lead = load(id)
        lead.convertedCustomerId?.let { existing ->
            return PromotedLeadView(lead.id, existing, subscriptionId = null, alreadyConverted = true)
        }
        val planId = command.planId ?: lead.interestedPlanId
            ?: throw ValidationException("Paket wajib dipilih saat mempromosikan calon pelanggan")
        val address = lead.address?.takeIf { it.isNotBlank() }
            ?: throw ValidationException("Alamat calon pelanggan wajib diisi sebelum dipromosikan")
        val registered = customers.registerCustomer(
            RegisterCustomerCommand(
                code = command.code,
                name = lead.name,
                phone = lead.phone,
                email = lead.email,
                address = address,
                location = lead.latitude?.let { lat -> lead.longitude?.let { lon -> Coordinate(lon, lat) } },
                areaId = command.areaId,
                idCardNumber = command.idCardNumber,
                planId = planId,
                monthlyFeeOverride = command.monthlyFeeOverride,
            ),
        )
        lead.convert(registered.customerId)
        leads.save(lead)
        return PromotedLeadView(lead.id, registered.customerId, registered.subscriptionId, alreadyConverted = false)
    }

    private fun load(id: UUID): OrderLead =
        leads.find(id)?.takeIf { it.tenantId == currentUser.current().tenantId }
            ?: throw NotFoundException("Calon pelanggan tidak ditemukan")
}
