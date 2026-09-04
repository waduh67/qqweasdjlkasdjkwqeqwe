package com.duluin.ftth.order

import com.duluin.ftth.order.adapter.outbound.persistence.OrderJpaRepository
import com.duluin.ftth.order.adapter.outbound.persistence.OrderLineJpaRepository
import com.duluin.ftth.order.adapter.outbound.persistence.OrderOperationJpaEntity
import com.duluin.ftth.order.adapter.outbound.persistence.OrderOperationJpaRepository
import com.duluin.ftth.order.adapter.outbound.persistence.OrderPersistenceAdapter
import com.duluin.ftth.order.application.port.outbound.StoredOrderOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class OrderPersistenceAdapterTest {
    @Test
    fun `durable order outcome round trips through Boot Jackson 3 mapper`() {
        val orders = mock(OrderJpaRepository::class.java)
        val lines = mock(OrderLineJpaRepository::class.java)
        val operations = mock(OrderOperationJpaRepository::class.java)
        val tenantId = UUID.randomUUID()
        var stored: OrderOperationJpaEntity? = null
        `when`(operations.save(any())).thenAnswer {
            stored = it.arguments[0] as OrderOperationJpaEntity
            stored
        }
        doAnswer { stored }.`when`(operations).findByTenantIdAndNamespaceAndOperationKey(tenantId, "order.schedule", "operation-1")

        val adapter = OrderPersistenceAdapter(orders, lines, operations, JsonMapper.builder().findAndAddModules().build())
        val value = OrderView(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            customerId = UUID.randomUUID(),
            status = "SCHEDULED",
            lines = listOf(OrderLineView(UUID.randomUUID(), "Paket Fiber", 2)),
            serviceAddress = ServiceAddress("Jl. Mawar 1", "Bekasi", "17111", -6.2, 106.8),
            appointment = Appointment(Instant.parse("2026-09-04T09:00:00Z"), Instant.parse("2026-09-04T10:00:00Z")),
            cancellationReason = null,
            rejectionReason = null,
            revision = 3,
            lastActorId = UUID.randomUUID(),
            lastOperationNamespace = "order.schedule",
            lastOperationKey = "operation-1",
            lastOperationHash = "payload-hash",
        )

        adapter.saveOutcome(tenantId, "order.schedule", "operation-1", "payload-hash", value)

        assertThat(adapter.findOutcome(tenantId, "order.schedule", "operation-1"))
            .isEqualTo(StoredOrderOutcome("payload-hash", value))
    }
}
