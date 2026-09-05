package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.customer.application.port.inbound.CustomerView
import com.duluin.ftth.customer.application.port.inbound.ManageCustomerUseCase
import com.duluin.ftth.customer.application.port.inbound.ManageSubscriptionUseCase
import com.duluin.ftth.customer.application.port.inbound.SaveCustomerCommand
import com.duluin.ftth.customer.application.port.inbound.SaveSubscriptionCommand
import com.duluin.ftth.customer.application.port.inbound.UnmappedCustomerView
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.CustomerContactChanged
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.CustomerStatus
import com.duluin.ftth.network.NetworkApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
@Transactional
class CustomerService(
    private val customerRepository: CustomerRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val manageSubscription: ManageSubscriptionUseCase,
    private val onuRepository: OnuRepository,
    private val assembler: CustomerAssembler,
    private val networkApi: NetworkApi,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    private val events: ApplicationEventPublisher,
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
    override fun findUnmapped(query: String, limit: Int): List<UnmappedCustomerView> =
        customerRepository.findUnmapped(query, currentUser.current().areaScope(), limit.coerceIn(1, MAX_UNMAPPED))
            .map { UnmappedCustomerView(it.id, it.code, it.name, it.address, it.phone, it.status) }

    @Transactional(readOnly = true)
    override fun get(id: UUID): CustomerView = assemble(requireCustomer(id))

    /**
     * Pelanggan + langganannya lahir bersama. Urutannya penting: langganan dibuka SETELAH
     * pelanggan tersimpan (butuh id-nya), tapi masih di transaksi yang sama — paket yang
     * salah membatalkan pendaftaran seutuhnya, bukan meninggalkan pelanggan tanpa paket.
     */
    override fun create(command: SaveCustomerCommand, plan: SaveSubscriptionCommand?): CustomerView {
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
        events.publishEvent(customer.contactChanged())
        if (plan != null) manageSubscription.setPlan(customer.id, plan)
        return assemble(customer)
    }

    override fun update(id: UUID, command: SaveCustomerCommand): CustomerView {
        val customer = requireCustomer(id)
        val moved = customer.location != command.location
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
        // Kabel drop dimiliki module network; minta network menempelkan ujungnya
        // ke titik baru bila rumah pelanggan berpindah.
        if (moved) saved.location?.let { networkApi.resnapCablesForMovedCustomer(id, it) }
        auditor.record("customer.updated", "Customer", saved.id, saved.tenantId, mapOf("code" to saved.code))
        events.publishEvent(saved.contactChanged())
        return assemble(saved)
    }

    override fun relocate(id: UUID, location: Coordinate): CustomerView {
        val customer = requireCustomer(id)
        customer.relocate(location)
        val saved = customerRepository.save(customer)
        saved.location?.let { networkApi.resnapCablesForMovedCustomer(id, it) }
        auditor.record("customer.relocated", "Customer", saved.id, saved.tenantId, mapOf("code" to saved.code))
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

    /**
     * Hanya `create`/`update` yang memancarkan ini — `relocate` dan `changeStatus` memang tak
     * bisa menyentuh kontak, jadi menerbitkannya di sana hanya akan jadi kerja sia-sia.
     */
    private fun Customer.contactChanged() = CustomerContactChanged(tenantId, id, email, phone)

    private fun assemble(customer: Customer): CustomerView = assembler.toViews(
        customers = listOf(customer),
        subscriptions = listOfNotNull(subscriptionRepository.findByCustomerId(customer.id)),
        onus = onuRepository.findByCustomerId(customer.id),
    ).single()

    private fun requireCustomer(id: UUID): Customer =
        customerRepository.findById(id) ?: throw NotFoundException("Pelanggan $id tidak ditemukan")

    /**
     * Kode unik `CUST-{yyyyMMdd}-{acak}` (mis. `CUST-20260807-K7M2Q9`). Sufiks acak dibuat lokal —
     * tak perlu kueri urutan global (yang rapuh & bikin bentrok di DB bersama). Diulang bila kandidat
     * kebetulan sudah terpakai; keunikan akhir tetap dijaga UNIQUE(tenant, code).
     */
    private fun generateNextCode(): String {
        repeat(MAX_CODE_ATTEMPTS) {
            val candidate = Customer.formatAutoCode(LocalDate.now(), randomCodeSuffix())
            if (!customerRepository.existsByCode(candidate)) return candidate
        }
        throw ConflictException("Gagal membuat kode pelanggan otomatis, coba isi manual")
    }

    private fun randomCodeSuffix(): String =
        (1..AUTO_CODE_SUFFIX_LEN).map { CODE_ALPHABET.random() }.joinToString("")

    private companion object {
        /** Pemilih di peta hanya perlu daftar pendek; batas keras agar tak bisa dipakai menguras tabel. */
        const val MAX_UNMAPPED = 100
        const val MAX_CODE_ATTEMPTS = 100
        const val AUTO_CODE_SUFFIX_LEN = 6

        /** Base36 tanpa huruf/angka ambigu (tanpa 0/O/1/I) agar kode mudah dibaca & didikte. */
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
