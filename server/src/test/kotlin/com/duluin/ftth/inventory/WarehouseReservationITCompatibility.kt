package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder

class WarehouseReservationITCompatibility : WarehouseReservationFixture() {
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
