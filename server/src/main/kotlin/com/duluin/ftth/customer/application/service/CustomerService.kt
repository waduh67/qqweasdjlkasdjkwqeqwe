package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.customer.application.port.inbound.CustomerView
import com.duluin.ftth.customer.application.port.inbound.ManageCustomerUseCase
import com.duluin.ftth.customer.application.port.inbound.SaveCustomerCommand
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.CustomerStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CustomerService(
    private val customerRepository: CustomerRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val onuRepository: OnuRepository,
    private val assembler: CustomerAssembler,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageCustomerUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, status: CustomerStatus?, pageRequest: PageRequest): Page<CustomerView> {
        val page = customerRepository.search(query, currentUser.current().areaScope(), status, pageRequest)
        val ids = page.content.mapTo(HashSet()) { it.id }
        val views = assembler.toViews(
            customers = page.content,
            subscriptions = subscriptionRepository.findByCustomerIds(ids),
            onus = onuRepository.findByCustomerIds(ids),
        ).associateBy { it.id }
        return page.map { views.getValue(it.id) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): CustomerView = assemble(requireCustomer(id))

    override fun create(command: SaveCustomerCommand): CustomerView {
        val manualCode = command.code?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (manualCode != null && customerRepository.existsByCode(manualCode)) {
            throw ConflictException("Kode pelanggan '$manualCode' sudah dipakai")
        }
        val code = manualCode ?: generateNextCode()
        val customer = customerRepository.save(
            Customer.create(
                tenantId = currentUser.current().tenantId,
                code = code,
                name = command.name,
                phone = command.phone,
                email = command.email,
                address = command.address,
                location = command.location,
                areaId = command.areaId,
                idCardNumber = command.idCardNumber,
            ),
        )
        auditor.record(
            "customer.created", "Customer", customer.id, customer.tenantId,
            mapOf("code" to customer.code, "name" to customer.name),
        )
        return assemble(customer)
    }

    override fun update(id: UUID, command: SaveCustomerCommand): CustomerView {
        val customer = requireCustomer(id)
        customer.update(
            name = command.name,
            phone = command.phone,
            email = command.email,
            address = command.address,
            location = command.location,
            areaId = command.areaId,
            idCardNumber = command.idCardNumber,
        )
        val saved = customerRepository.save(customer)
        auditor.record("customer.updated", "Customer", saved.id, saved.tenantId, mapOf("code" to saved.code))
        return assemble(saved)
    }

    override fun changeStatus(id: UUID, status: CustomerStatus): CustomerView {
        val customer = requireCustomer(id)
        customer.changeStatus(status)
        val saved = customerRepository.save(customer)
        auditor.record(
            "customer.status_changed", "Customer", saved.id, saved.tenantId,
            mapOf("code" to saved.code, "status" to status.name),
        )
        return assemble(saved)
    }

    /**
     * Menolak penghapusan selama ONU-nya masih menempel di ODP. Menghapus begitu
     * saja akan membebaskan port di data padahal kabel drop-nya masih terpasang di
     * lapangan — port itu lalu terjual dua kali, dan yang menanggung adalah
     * teknisi yang datang ke lokasi.
     */
    override fun delete(id: UUID) {
        val customer = requireCustomer(id)
        val attached = onuRepository.findByCustomerId(id).filter { it.attached }
        if (attached.isNotEmpty()) {
            throw ConflictException(
                "Pelanggan ${customer.code} masih punya ${attached.size} ONU terpasang di ODP, lepas dulu",
            )
        }
        customerRepository.deleteById(id)
        auditor.record("customer.deleted", "Customer", id, customer.tenantId, mapOf("code" to customer.code))
    }

    private fun assemble(customer: Customer): CustomerView = assembler.toViews(
        customers = listOf(customer),
        subscriptions = subscriptionRepository.findByCustomerId(customer.id),
        onus = onuRepository.findByCustomerId(customer.id),
    ).single()

    private fun requireCustomer(id: UUID): Customer =
        customerRepository.findById(id) ?: throw NotFoundException("Pelanggan $id tidak ditemukan")

    /**
     * Kode berurut per-tenant (`CUST-000001`). Mulai dari urutan tertinggi + 1, lalu naik selama
     * kandidatnya sudah terpakai — melompati lubang yang ditinggalkan kode manual (mis. operator
     * pernah mengetik `CUST-000005` sendiri). Uniknya tetap dijaga akhir oleh UNIQUE(tenant, code).
     */
    private fun generateNextCode(): String {
        var sequence = customerRepository.maxCodeSequence(Customer.AUTO_CODE_PREFIX) + 1
        repeat(MAX_CODE_ATTEMPTS) {
            val candidate = Customer.formatAutoCode(sequence)
            if (!customerRepository.existsByCode(candidate)) return candidate
            sequence++
        }
        throw ConflictException("Gagal membuat kode pelanggan otomatis, coba isi manual")
    }

    private companion object {
        const val MAX_CODE_ATTEMPTS = 100
    }
}
