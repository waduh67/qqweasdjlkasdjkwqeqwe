package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder

class WarehouseReservationITCompatibility : WarehouseReservationFixture() {
    @Test fun `material owner reserve and release use the same durable command and reject unbound work orders`() {
        val (token, fixture) = prepare()
        receive(fixture, "10")
        val demand = demand(token, fixture, 10000)
        authenticated(token, fixture) {
            val api = context.getBean(InventoryMaterialReservationApi::class.java)
            val input = MaterialDocumentRequest(demand.document, 1, ReservationRequest(1, 0, 1))
            val metadata = WarehouseMutationMetadata("internal-reserve")
            val receipt = api.reserve(demand.workOrder, input, metadata)
            assertThat(api.reserve(demand.workOrder, input, metadata)).isEqualTo(receipt)
            val allocation = context.getBean(InventoryApi::class.java).fulfillmentAllocations(demand.workOrder).single().reservation!!
            val release = MaterialDocumentRequest(demand.document, 2, ReservationRequest(2, 0, 1,
                allocations = listOf(ReservationAmount(allocation.reservationId, allocation.reservationRevision, "10000")), reason = "Release remaining material"))
            val released = api.release(demand.workOrder, release, WarehouseMutationMetadata("internal-release"))
            assertThat(api.release(demand.workOrder, release, WarehouseMutationMetadata("internal-release"))).isEqualTo(released)
            assertThat(context.getBean(InventoryApi::class.java).fulfillmentAllocations(demand.workOrder).single().reservation?.state).isEqualTo("RELEASED")
            org.assertj.core.api.Assertions.assertThatThrownBy { context.getBean(InventoryApi::class.java).fulfillmentAllocations(java.util.UUID.randomUUID()) }
                .isInstanceOf(WarehouseContractException::class.java)
        }
    }
    @Test fun `public inventory allocation API exposes durable links rather than empty success`() {
        val (token, fixture) = prepare()
        receive(fixture, "60")
        val demand = demand(token, fixture, 100000, false)
        reserve(demand)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationConverter().convert(context.getBean(JwtDecoder::class.java).decode(token))
        try {
            val allocations = TenantContext.runAs(fixture.tenant) { context.getBean(InventoryApi::class.java).fulfillmentAllocations(demand.workOrder) }
            assertThat(allocations).hasSize(1)
            assertThat(allocations.single().quantity).isNull()
            assertThat(allocations.single().customerId).isNull()
            assertThat(allocations.single().reservation?.reservedUnpickedBase).isEqualTo("60000")
            assertThat(allocations.single().reservation?.documentRevision).isEqualTo(2)
            assertThat(allocations.single().itemId.toString()).isEqualTo(allocations(demand).single().path("stockIdentityId").asString())
        } finally { SecurityContextHolder.clearContext() }
    }
}
