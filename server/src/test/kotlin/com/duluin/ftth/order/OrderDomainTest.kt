package com.duluin.ftth.order

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.order.domain.model.Order
import com.duluin.ftth.order.domain.model.OrderStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class OrderDomainTest {
    private val operation = OperationCommand("order.create", "create-1", "hash-1")
    private val address = ServiceAddress("Jalan Merdeka 1", "Bekasi", "17111")
    private val line = OrderLineCommand(UuidV7.generate(), "Paket 100 Mbps", 1)

    private fun draft() = Order.create(CreateOrderCommand(UuidV7.generate(), listOf(line), address, operation = operation), UuidV7.generate(), UuidV7.generate())

    @Test
    fun `lifecycle reaches fulfilled in authoritative order`() {
        val order = draft()
        order.transition(OrderTransitionCommand(order.id, OrderTransition.SUBMIT, 0, operation = op("submit")), UuidV7.generate())
        order.transition(OrderTransitionCommand(order.id, OrderTransition.ACCEPT, 1, operation = op("accept")), UuidV7.generate())
        order.transition(OrderTransitionCommand(order.id, OrderTransition.SCHEDULE, 2, appointment = Appointment(Instant.parse("2030-01-01T10:00:00Z"), Instant.parse("2030-01-01T11:00:00Z")), operation = op("schedule")), UuidV7.generate())
        order.transition(OrderTransitionCommand(order.id, OrderTransition.START_FULFILLING, 3, operation = op("start")), UuidV7.generate())
        order.transition(OrderTransitionCommand(order.id, OrderTransition.FULFILL, 4, operation = op("fulfill")), UuidV7.generate())
        assertThat(order.status).isEqualTo(OrderStatus.FULFILLED)
        assertThat(order.revision).isEqualTo(5)
    }

    @Test
    fun `cancel and reject require reasons and are terminal`() {
        val cancelled = draft()
        cancelled.transition(OrderTransitionCommand(cancelled.id, OrderTransition.SUBMIT, 0, operation = op("submit")), null)
        assertThatThrownBy { cancelled.transition(OrderTransitionCommand(cancelled.id, OrderTransition.CANCEL, 1, operation = op("cancel")), null) }
            .isInstanceOf(ValidationException::class.java)
        cancelled.transition(OrderTransitionCommand(cancelled.id, OrderTransition.CANCEL, 1, reason = "Pelanggan berubah pikiran", operation = op("cancel")), null)
        assertThat(cancelled.status).isEqualTo(OrderStatus.CANCELLED)
        assertThatThrownBy { cancelled.transition(OrderTransitionCommand(cancelled.id, OrderTransition.ACCEPT, 2, operation = op("accept")), null) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `stale revision is rejected without changing state`() {
        val order = draft()
        order.transition(OrderTransitionCommand(order.id, OrderTransition.SUBMIT, 0, operation = op("submit")), null)
        assertThatThrownBy { order.transition(OrderTransitionCommand(order.id, OrderTransition.ACCEPT, 0, operation = op("stale")), null) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(order.status).isEqualTo(OrderStatus.SUBMITTED)
    }

    @Test
    fun `fulfillment cannot happen before approval and scheduling`() {
        val order = draft()
        assertThatThrownBy { order.transition(OrderTransitionCommand(order.id, OrderTransition.FULFILL, 0, operation = op("fulfill")), null) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `rejection is terminal and records reason`() {
        val order = draft()
        order.transition(OrderTransitionCommand(order.id, OrderTransition.SUBMIT, 0, operation = op("submit")), null)
        order.transition(OrderTransitionCommand(order.id, OrderTransition.REJECT, 1, reason = "Alamat tidak terlayani", operation = op("reject")), null)
        assertThat(order.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(order.rejectionReason).isEqualTo("Alamat tidak terlayani")
        assertThatThrownBy { order.transition(OrderTransitionCommand(order.id, OrderTransition.SUBMIT, 2, operation = op("submit-again")), null) }
            .isInstanceOf(ConflictException::class.java)
    }

    private fun op(key: String) = OperationCommand("order.$key", key, key)
}
