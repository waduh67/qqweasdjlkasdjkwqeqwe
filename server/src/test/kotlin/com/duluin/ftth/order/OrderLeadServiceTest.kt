package com.duluin.ftth.order

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.RegisteredCustomer
import com.duluin.ftth.order.application.port.inbound.CreateOrderLeadCommand
import com.duluin.ftth.order.application.port.inbound.PromoteOrderLeadCommand
import com.duluin.ftth.order.application.port.inbound.UpdateOrderLeadCommand
import com.duluin.ftth.order.application.port.outbound.OrderLeadFilter
import com.duluin.ftth.order.application.service.OrderLeadService
import com.duluin.ftth.order.domain.model.LeadStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * Aturan meja calon pelanggan, diuji tanpa Spring dan tanpa database.
 *
 * Module `customer` diwakili tiruan: yang dijaga di sini adalah KAPAN order memanggilnya
 * (tepat sekali per lead, tak pernah lagi setelah dikonversi), bukan cara pelanggan dibuat.
 */
class OrderLeadServiceTest {

    private val tenant = UuidV7.generate()
    private val otherTenant = UuidV7.generate()
    private val user = AuthenticatedUser(
        UuidV7.generate(), tenant, "operator@example.test", "Operator", false,
        setOf("order.lead.view", "order.lead.manage"), emptySet(),
    )
    private val current = object : CurrentUserProvider { override fun currentOrNull() = user }
    private val leads = InMemoryOrderLeadRepository()
    private val customers = mock(CustomerApi::class.java)
    private val service = OrderLeadService(leads, current, customers)

    private val plan = UuidV7.generate()

    private fun newLead(
        name: String = "Budi Santoso",
        phone: String = "0812-3456-7890",
        address: String? = "Jl. Merdeka 1",
    ) = service.create(
        CreateOrderLeadCommand(name = name, phone = phone, address = address, interestedPlanId = plan),
    )

    /**
     * Mockito mendaftarkan matcher ke tumpukan internalnya lalu mengembalikan null. Kotlin
     * menolak null itu DI TEMPAT PEMANGGILAN karena [CustomerApi.registerCustomer] menerima
     * parameter non-null, jadi stubbing meledak NPE sebelum matcher-nya sempat dipakai — dan
     * matcher yang tertinggal di tumpukan justru menjatuhkan tes BERIKUTNYA, sehingga sumber
     * kegagalannya menunjuk ke tes yang salah. Helper ini membiarkan matcher terdaftar, lalu
     * menyerahkan null yang dipaksa bertipe non-null: mock-nya bytecode Java, tak mengecek null.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> matcherFor(@Suppress("UNUSED_PARAMETER") registered: Any?): T = null as T

    private fun anyCommand(): RegisterCustomerCommand = matcherFor(any(RegisterCustomerCommand::class.java))

    private fun captured(captor: ArgumentCaptor<RegisterCustomerCommand>): RegisterCustomerCommand =
        matcherFor(captor.capture())

    private fun stubRegister(customerId: UUID, subscriptionId: UUID = UuidV7.generate()) {
        `when`(customers.registerCustomer(anyCommand()))
            .thenReturn(RegisteredCustomer(customerId, subscriptionId))
    }

    @Test
    fun `phone number is normalised so one person is not two prospects`() {
        val a = newLead(phone = "0812-3456-7890")
        val b = newLead(phone = "0812 3456 7890")
        assertThat(a.phone).isEqualTo("081234567890")
        assertThat(b.phone).isEqualTo(a.phone)
        assertThat(leads.findByPhone("081234567890")).hasSize(2)
    }

    @Test
    fun `a prospect without a usable phone number is rejected`() {
        assertThatThrownBy { newLead(phone = "12") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { newLead(phone = "hubungi-kantor") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a new prospect starts at NEW and can only follow legal transitions`() {
        val lead = newLead()
        assertThat(lead.status).isEqualTo(LeadStatus.NEW)
        assertThat(service.changeStatus(lead.id, LeadStatus.CONTACTED).status).isEqualTo(LeadStatus.CONTACTED)
        assertThat(service.changeStatus(lead.id, LeadStatus.QUALIFIED).status).isEqualTo(LeadStatus.QUALIFIED)
        // Kembali ke NEW dilarang: prospek yang sudah ditelepon tak boleh tampak belum disentuh.
        assertThatThrownBy { service.changeStatus(lead.id, LeadStatus.NEW) }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `CONVERTED can never be reached by hand`() {
        val lead = newLead()
        assertThatThrownBy { service.changeStatus(lead.id, LeadStatus.CONVERTED) }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a dropped prospect comes back as already contacted, not as brand new`() {
        val lead = newLead()
        service.changeStatus(lead.id, LeadStatus.DROPPED)
        assertThat(service.changeStatus(lead.id, LeadStatus.CONTACTED).status).isEqualTo(LeadStatus.CONTACTED)
    }

    @Test
    fun `promotion creates a customer once and is idempotent on retry`() {
        val customerId = UuidV7.generate()
        stubRegister(customerId)
        val lead = newLead()

        val first = service.promote(lead.id, PromoteOrderLeadCommand())
        val second = service.promote(lead.id, PromoteOrderLeadCommand())

        assertThat(first.customerId).isEqualTo(customerId)
        assertThat(first.alreadyConverted).isFalse()
        assertThat(second.customerId).isEqualTo(customerId)
        assertThat(second.alreadyConverted).isTrue()
        // Tombol yang ditekan dua kali TIDAK boleh melahirkan pelanggan kedua untuk orang yang sama.
        verify(customers, times(1)).registerCustomer(anyCommand())
        assertThat(service.get(lead.id).status).isEqualTo(LeadStatus.CONVERTED)
        assertThat(service.get(lead.id).convertedCustomerId).isEqualTo(customerId)
    }

    @Test
    fun `promotion carries the prospect biodata into the customer module`() {
        stubRegister(UuidV7.generate())
        val lead = service.create(
            CreateOrderLeadCommand(
                name = "Sari", phone = "081200001111", email = "sari@example.test",
                address = "Jl. Mawar 9", latitude = -6.2, longitude = 106.8, interestedPlanId = plan,
            ),
        )

        service.promote(lead.id, PromoteOrderLeadCommand())

        val captor = ArgumentCaptor.forClass(RegisterCustomerCommand::class.java)
        verify(customers).registerCustomer(captured(captor))
        assertThat(captor.value.name).isEqualTo("Sari")
        assertThat(captor.value.phone).isEqualTo("081200001111")
        assertThat(captor.value.address).isEqualTo("Jl. Mawar 9")
        assertThat(captor.value.planId).isEqualTo(plan)
        // Coordinate menerima (longitude, latitude) — tertukar berarti pelanggan mendarat di laut.
        assertThat(captor.value.location?.latitude).isEqualTo(-6.2)
        assertThat(captor.value.location?.longitude).isEqualTo(106.8)
    }

    @Test
    fun `promotion without a plan or an address is refused before any customer is created`() {
        val noPlan = service.create(CreateOrderLeadCommand(name = "Tanpa Paket", phone = "081200002222", address = "Jl. A"))
        assertThatThrownBy { service.promote(noPlan.id, PromoteOrderLeadCommand()) }
            .isInstanceOf(ValidationException::class.java)

        val noAddress = service.create(CreateOrderLeadCommand(name = "Tanpa Alamat", phone = "081200003333", interestedPlanId = plan))
        assertThatThrownBy { service.promote(noAddress.id, PromoteOrderLeadCommand()) }
            .isInstanceOf(ValidationException::class.java)

        verify(customers, times(0)).registerCustomer(anyCommand())
    }

    @Test
    fun `a converted prospect can no longer be edited here`() {
        stubRegister(UuidV7.generate())
        val lead = newLead()
        service.promote(lead.id, PromoteOrderLeadCommand())
        assertThatThrownBy { service.update(lead.id, UpdateOrderLeadCommand(name = "Nama Baru")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `a partial update keeps the fields it does not mention`() {
        val lead = newLead()
        val updated = service.update(lead.id, UpdateOrderLeadCommand(notes = "Minta dipasang hari Sabtu"))
        assertThat(updated.name).isEqualTo("Budi Santoso")
        assertThat(updated.address).isEqualTo("Jl. Merdeka 1")
        assertThat(updated.notes).isEqualTo("Minta dipasang hari Sabtu")
    }

    @Test
    fun `a prospect belonging to another tenant is not found, not forbidden`() {
        val foreign = com.duluin.ftth.order.domain.model.OrderLead.create(
            tenantId = otherTenant, name = "Tetangga", phone = "081299998888",
        )
        leads.save(foreign)
        // 404, bukan 403: keberadaan prospek tenant lain pun tak boleh bocor.
        assertThatThrownBy { service.get(foreign.id) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `search matches on name or phone`() {
        newLead(name = "Budi Santoso", phone = "081211112222")
        newLead(name = "Sari Dewi", phone = "081233334444")

        assertThat(service.search(OrderLeadFilter(query = "budi"), PageRequest(0, 20)).content)
            .extracting<String> { it.name }.containsExactly("Budi Santoso")
        assertThat(service.search(OrderLeadFilter(query = "3333"), PageRequest(0, 20)).content)
            .extracting<String> { it.name }.containsExactly("Sari Dewi")
        assertThat(service.search(OrderLeadFilter(status = LeadStatus.DROPPED), PageRequest(0, 20)).content).isEmpty()
    }
}
