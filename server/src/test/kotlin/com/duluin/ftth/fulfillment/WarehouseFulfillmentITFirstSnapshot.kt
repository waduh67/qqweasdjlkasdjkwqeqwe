package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehouseFulfillmentITFirstSnapshot : WarehouseFulfillmentFixture() {
    @Test fun `invented visit row and receipt cannot replace a captured owner transition`() {
        val case = usageCase()
        used(case)
        completeJob(case.receipt.workOrder,case.receipt.receiver.first)
        val actor = UUID.fromString(mapper.readTree(request("GET","/api/me",case.receipt.stock.token).contentAsString).path("id").asString())
        val database = fixture(case.receipt.stock.token)

        assertThatThrownBy { database.transaction {
            FirstFulfillmentForgery(database,UUID.fromString(case.receipt.workOrder),actor)
                .insert(missingVisit=true,handoff=true,fakeVisitOwner=true)
        } }.hasStackTraceContaining("FULFILLMENT_VISIT_TRANSITION")
    }

    @ParameterizedTest @ValueSource(strings = ["normal", "cleared", "mismatched", "restored", "selective"])
    fun `first snapshot cannot invent a visit and completed effect without its owner`(scope: String) {
        val case = usageCase()
        used(case)
        completeJob(case.receipt.workOrder, case.receipt.receiver.first)
        val actor = UUID.fromString(mapper.readTree(request("GET", "/api/me", case.receipt.stock.token).contentAsString).path("id").asString())
        val database = fixture(case.receipt.stock.token)
        val before = physicalState(case.receipt.stock.token)
        val forge = FirstFulfillmentForgery(database, UUID.fromString(case.receipt.workOrder), actor)

        assertThatThrownBy { database.transaction {
            forge.insert(missingVisit = true)
            forge.holdOwnerValidator(scope)
        } }.hasStackTraceContaining(if (scope in setOf("normal", "restored")) "FULFILLMENT_VISIT_BINDING" else "row tenant scope")

        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM fulfillment_approval_snapshot")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fieldservice_visit")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fieldservice_visit_operation")).isEqualTo("0")
        }
    }

    @Test fun `first snapshot cannot complete without durable handoff lineage`() {
        val case = usageCase()
        used(case)
        completeJob(case.receipt.workOrder, case.receipt.receiver.first)
        val actor = UUID.fromString(mapper.readTree(request("GET", "/api/me", case.receipt.stock.token).contentAsString).path("id").asString())
        val database = fixture(case.receipt.stock.token)

        assertThatThrownBy { database.transaction {
            val forge = FirstFulfillmentForgery(database, UUID.fromString(case.receipt.workOrder), actor)
            forge.insert(missingVisit = false)
            forge.holdOwnerValidator("normal")
        } }.hasStackTraceContaining("FULFILLMENT_HANDOFF_BINDING")
    }
}
