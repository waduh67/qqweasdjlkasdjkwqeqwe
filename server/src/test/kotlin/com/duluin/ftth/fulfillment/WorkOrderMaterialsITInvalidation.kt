package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.util.UUID

class WorkOrderMaterialsITInvalidation : WarehouseApprovalHttpFixture() {
    @Test fun `public invalidation owner makes pending approvals stale without decisions or physical effects`() {
        val pending = pending()
        assertThat(invalidate(pending)).isEqualTo(1)
        assertThat(invalidate(pending)).isZero()
        val current = request("GET", "/api/v1/warehouse/approvals/${pending.id}", pending.checker.first)
        assertThat(current.status).isEqualTo(200)
        assertThat(mapper.readTree(current.contentAsString).path("status").asString()).isEqualTo("STALE")
        assertThat(decide(pending).status).isEqualTo(409)
        counts(pending, 0, 0)
    }
    @Test fun `public invalidation owner retains terminal decisions and posting history`() {
        val approved = pending()
        assertThat(decide(approved).status).isEqualTo(200)
        val before = request("GET", "/api/v1/warehouse/approvals/${approved.id}/history", approved.checker.first).contentAsString
        assertThat(invalidate(approved)).isZero()
        assertThat(request("GET", "/api/v1/warehouse/approvals/${approved.id}/history", approved.checker.first).contentAsString).isEqualTo(before)
        counts(approved, 1, 1)
    }
    private fun invalidate(case: ApprovalCase): Int {
        val fixture = fixture(case.setup.token)
        val security = SecurityContextHolder.getContext()
        val prior = security.authentication
        security.authentication = JwtAuthenticationConverter().convert(context.getBean(JwtDecoder::class.java).decode(case.setup.token))
        try {
            return TenantContext.runAs(fixture.tenant) { fixture.transaction {
                val cutovers = context.getBean(InventoryTenantCutoverApi::class.java)
                val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
                val authority = context.getBean(CurrentAuthorityApi::class.java).lockCurrent()
                context.getBean(InventoryApprovalInvalidationApi::class.java)
                    .invalidateUnposted(setOf(UUID.fromString(case.document)), authority.fence, cutover)
            } }
        } finally { security.authentication = prior }
    }
}
