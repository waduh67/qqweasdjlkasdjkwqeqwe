package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.application.port.inbound.AttachOnuCommand
import com.duluin.ftth.customer.application.port.inbound.ManageOnuUseCase
import com.duluin.ftth.customer.application.port.inbound.OnuView
import com.duluin.ftth.customer.application.port.inbound.RegisterOnuCommand
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.Onu
import com.duluin.ftth.customer.domain.model.OnuStatus
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Pemasangan ONU ke port ODP — titik temu module customer dan network.
 *
 * Pembagian tanggung jawabnya disengaja: module customer tahu port mana yang
 * sudah terisi (ONU miliknya), module network tahu aturan portnya (kapasitas,
 * status ODP). Tidak ada module yang perlu mengintip tabel milik yang lain.
 */
@Service
@Transactional
class OnuService(
    private val onuRepository: OnuRepository,
    private val customerRepository: CustomerRepository,
    private val networkApi: NetworkApi,
    private val assembler: CustomerAssembler,
    private val auditor: AuditRecorder,
) : ManageOnuUseCase {

    @Transactional(readOnly = true)
    override fun listForCustomer(customerId: UUID): List<OnuView> {
        requireCustomer(customerId)
        return assembler.toOnuViews(onuRepository.findByCustomerId(customerId))
    }

    override fun register(customerId: UUID, command: RegisterOnuCommand): OnuView {
        val customer = requireCustomer(customerId)
        val serial = command.serialNumber.trim().uppercase()
        if (onuRepository.existsBySerialNumber(serial)) {
            throw ConflictException("ONU dengan serial '$serial' sudah terdaftar")
        }
        val onu = onuRepository.save(
            Onu.create(
                tenantId = customer.tenantId,
                customerId = customerId,
                serialNumber = command.serialNumber,
                model = command.model,
            ),
        )
        auditor.record(
            "onu.registered", "Onu", onu.id, onu.tenantId,
            mapOf("serialNumber" to onu.serialNumber, "customer" to customer.code),
        )
        return assembler.toOnuViews(listOf(onu)).single()
    }

    override fun attach(id: UUID, command: AttachOnuCommand): OnuView {
        val onu = requireOnu(id)
        val odp = networkApi.requireOdp(command.odpId)

        // ONU ini sendiri dikecualikan agar memindahkannya ke port lain di ODP
        // yang sama tidak dianggap bentrok dengan dirinya sendiri.
        val occupied = onuRepository.findByOdpId(command.odpId)
            .filter { it.id != id }
            .mapNotNullTo(HashSet()) { it.odpPortNumber }

        networkApi.assertOdpPortAssignable(command.odpId, command.portNumber, occupied)

        onu.attachTo(command.odpId, command.portNumber, command.installRxPowerDbm)
        val saved = onuRepository.save(onu)
        auditor.record(
            "onu.attached", "Onu", saved.id, saved.tenantId,
            mapOf(
                "serialNumber" to saved.serialNumber,
                "odp" to odp.code,
                "port" to command.portNumber,
                "rxPowerDbm" to saved.installRxPowerDbm,
                "opticalHealth" to saved.opticalHealth().name,
            ),
        )
        return assembler.toOnuViews(listOf(saved)).single()
    }

    override fun detach(id: UUID): OnuView {
        val onu = requireOnu(id)
        val previousOdp = onu.odpId
        onu.detach()
        val saved = onuRepository.save(onu)
        auditor.record(
            "onu.detached", "Onu", saved.id, saved.tenantId,
            mapOf("serialNumber" to saved.serialNumber, "previousOdpId" to previousOdp?.toString()),
        )
        return assembler.toOnuViews(listOf(saved)).single()
    }

    override fun changeStatus(id: UUID, status: OnuStatus): OnuView {
        val onu = requireOnu(id)
        onu.changeStatus(status)
        val saved = onuRepository.save(onu)
        auditor.record(
            "onu.status_changed", "Onu", saved.id, saved.tenantId,
            mapOf("serialNumber" to saved.serialNumber, "status" to status.name),
        )
        return assembler.toOnuViews(listOf(saved)).single()
    }

    override fun delete(id: UUID) {
        val onu = requireOnu(id)
        if (onu.attached) {
            throw ConflictException("ONU ${onu.serialNumber} masih terpasang di ODP, lepas dulu sebelum dihapus")
        }
        onuRepository.deleteById(id)
        auditor.record("onu.deleted", "Onu", id, onu.tenantId, mapOf("serialNumber" to onu.serialNumber))
    }

    private fun requireOnu(id: UUID): Onu =
        onuRepository.findById(id) ?: throw NotFoundException("ONU $id tidak ditemukan")

    private fun requireCustomer(id: UUID): Customer =
        customerRepository.findById(id) ?: throw NotFoundException("Pelanggan $id tidak ditemukan")
}
