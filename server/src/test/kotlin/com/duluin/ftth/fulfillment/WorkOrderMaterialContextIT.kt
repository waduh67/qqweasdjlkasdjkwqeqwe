package com.duluin.ftth.fulfillment

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.WorkOrderMaterialContextApi
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkOrderMaterialContextIT : WarehouseReservationFixture() {
    @Test
    fun `owner snapshot uses live WO revision and rejects stale cancelled and foreign contexts`() {
        val token = tenant()
        val fixture = fixture(token)
        val response = request("POST", "/api/work-orders", token,
            """{"type":"PREVENTIVE","title":"Network inspection","areaId":"${area(token)}"}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        val id = UUID.fromString(mapper.readTree(response.contentAsString).path("id").asString())
        val owner = context.getBean(WorkOrderMaterialContextApi::class.java)
        val authority = context.getBean(CurrentAuthorityApi::class.java)
        val cutovers = context.getBean(InventoryTenantCutoverApi::class.java)
        val snapshot = authenticated(token, fixture) { owner.read(id) }
        assertThat(snapshot.customerId).isNull()
        assertThat(snapshot.subscriptionId).isNull()
        assertThat(snapshot.activeAssigneeIds).isEmpty()
        assertThat(snapshot.action.name).isEqualTo("PREVENTIVE")
        authenticated(token, fixture) {
            fixture.transaction {
                cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
                val fence = authority.lockCurrent().fence
                assertThat(owner.lock(id, snapshot.workOrderRevision, fence)).isEqualTo(snapshot)
            }
            assertThatThrownBy { fixture.transaction {
                cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
                owner.lock(id, snapshot.workOrderRevision + 1, authority.lockCurrent().fence)
            } }.isInstanceOf(WarehouseContractException::class.java).hasMessage("STALE_REVISION")
        }
        assertThat(request("POST", "/api/work-orders/$id/cancel", token, """{"reason":"Cancelled visit"}""").status).isEqualTo(200)
        authenticated(token, fixture) {
            val cancelled = owner.read(id)
            assertThat(cancelled.cancelled).isTrue()
            assertThat(cancelled.workOrderRevision).isGreaterThan(snapshot.workOrderRevision)
            assertThatThrownBy { fixture.transaction {
                cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
                owner.lock(id, cancelled.workOrderRevision, authority.lockCurrent().fence)
            } }.isInstanceOf(WarehouseContractException::class.java).hasMessage("SOURCE_NOT_VERIFIED")
        }
        val other = tenant()
        assertThatThrownBy { authenticated(other, fixture(other)) { owner.read(id) } }
            .isInstanceOf(WarehouseContractException::class.java).hasMessage("NOT_FOUND")
    }
}
