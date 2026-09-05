package com.duluin.ftth.order

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.order.adapter.outbound.persistence.InMemoryOrderCustomerProjection
import com.duluin.ftth.order.adapter.outbound.persistence.InMemoryOrderRepository
import com.duluin.ftth.order.application.service.OrderApplicationService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.beans.Introspector

class OrderApplicationServiceTest {
    private val tenant = UuidV7.generate()
    private val customer = UuidV7.generate()
    private val user = AuthenticatedUser(UuidV7.generate(), tenant, "operator@example.test", "Operator", false, setOf("order.order.create", "order.order.manage"), emptySet())
    private val current = object : CurrentUserProvider { override fun currentOrNull() = user }
    private val service = OrderApplicationService(InMemoryOrderRepository(), current, InMemoryOrderCustomerProjection())

    @Test
    fun `same operation replays one order and different payload conflicts`() {
        val first = service.create(create("same", "hash-a"))
        val replay = service.create(create("same", "hash-a"))
        assertThat(replay.id).isEqualTo(first.id)
        assertThatThrownBy { service.create(create("same", "hash-b")) }.isInstanceOf(ConflictException::class.java)
        assertThat(service.portalOrders(customer)).hasSize(0)
    }

    @Test
    fun `portal projection appears only after submit and foreign customer is absent`() {
        val first = service.create(create("create", "create-hash"))
        assertThat(service.portalOrders(customer)).isEmpty()
        val submitted = service.transition(OrderTransitionCommand(first.id, OrderTransition.SUBMIT, 0, operation = OperationCommand("order.submit", "submit", "submit-hash")))
        assertThat(submitted.status).isEqualTo("SUBMITTED")
        assertThat(service.portalOrders(customer)).extracting<String> { it.status.name }.containsExactly("RECEIVED")
        assertThat(service.portalOrder(UuidV7.generate(), first.id)).isNull()
    }

    @Test
    fun `portal projection redacts coordinates and internal order fields`() {
        val first = service.create(
            CreateOrderCommand(
                customer,
                listOf(OrderLineCommand(UuidV7.generate(), "Paket", 1)),
                ServiceAddress("Alamat", "Kota", "12345", -6.2, 106.8),
                operation = OperationCommand("order.create", "redaction", "redaction-hash"),
            ),
        )
        service.transition(OrderTransitionCommand(first.id, OrderTransition.SUBMIT, 0, operation = OperationCommand("order.submit", "redaction", "submit-redaction-hash")))

        val addressProperties = Introspector.getBeanInfo(service.portalOrders(customer).single().serviceAddress.javaClass)
            .propertyDescriptors.map { it.name }

        assertThat(addressProperties).doesNotContain("latitude", "longitude")
        assertThat(service.portalOrders(UuidV7.generate())).isEmpty()
    }

    private fun create(key: String, hash: String) = CreateOrderCommand(
        customer, listOf(OrderLineCommand(UuidV7.generate(), "Paket", 1)), ServiceAddress("Alamat", "Kota", "12345"),
        operation = OperationCommand("order.create", key, hash),
    )
}
