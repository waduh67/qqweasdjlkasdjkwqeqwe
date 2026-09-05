package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.UUID

class InventoryApprovalServiceTest {
    private val tenant = UUID.randomUUID()
    private val requester = UUID.randomUUID()
    private val custodian = UUID.randomUUID()
    private val firstApprover = UUID.randomUUID()
    private val secondApprover = UUID.randomUUID()
    private val policy = InventoryApprovalPolicy(1, listOf(ApprovalTier(1, 0, setOf(firstApprover)), ApprovalTier(2, 100, setOf(secondApprover))))
    private fun request(service: InventoryApprovalService, amount: Long = 100) = service.request(CreateInventoryApproval(tenant, InventoryApprovalType.ADJUSTMENT, amount, requester, custodian, policy, "policy-v1", "request-1", "request-hash"))
    private fun decision(approver: UUID, key: String = approver.toString(), value: InventoryApprovalDecision = InventoryApprovalDecision.APPROVE) = DecideInventoryApproval(tenant, approver, value, key, key)

    @Test fun `threshold requires two tiers and effect once`() {
        val service = InventoryApprovalService()
        val approval = request(service)
        assertThat(service.decide(approval.approvalId, decision(firstApprover)).status).isEqualTo(InventoryApprovalStatus.PENDING)
        val complete = service.decide(approval.approvalId, decision(secondApprover))
        assertThat(complete.status).isEqualTo(InventoryApprovalStatus.APPROVED)
        assertThat(service.effects(tenant)).hasSize(1)
        assertThat(service.decide(approval.approvalId, decision(secondApprover)).status).isEqualTo(InventoryApprovalStatus.APPROVED)
        assertThat(service.effects(tenant)).hasSize(1)
    }

    @Test fun `requester and custodian cannot approve`() {
        val service = InventoryApprovalService()
        val approval = request(service, 1)
        assertThatThrownBy { service.decide(approval.approvalId, decision(requester)) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service.decide(approval.approvalId, decision(custodian)) }.isInstanceOf(ValidationException::class.java)
    }

    @Test fun `same key different payload conflicts and rejection is rework for variance`() {
        val service = InventoryApprovalService()
        val approval = service.request(CreateInventoryApproval(tenant, InventoryApprovalType.COUNT_VARIANCE, 1, requester, null, policy, "policy-v1", "variance-1", "hash-1"))
        service.decide(approval.approvalId, decision(firstApprover, "d-1", InventoryApprovalDecision.REJECT))
        assertThat(service.get(approval.approvalId)!!.status).isEqualTo(InventoryApprovalStatus.REWORK_REQUIRED)
        assertThatThrownBy { service.decide(approval.approvalId, decision(firstApprover, "d-1", InventoryApprovalDecision.APPROVE).copy(operationHash = "other")) }.isInstanceOf(ConflictException::class.java)
    }

    @Test fun `expired approval is deterministic`() {
        val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val service = InventoryApprovalService(clock)
        val approval = service.request(CreateInventoryApproval(tenant, InventoryApprovalType.LOSS, 1, requester, null, policy.copy(expiry = java.time.Duration.ofSeconds(1)), "policy-v1", "loss-1", "hash"))
        clock.now = clock.now.plusSeconds(2)
        assertThatThrownBy { service.decide(approval.approvalId, decision(firstApprover)) }.isInstanceOf(ConflictException::class.java)
        assertThat(service.get(approval.approvalId)!!.status).isEqualTo(InventoryApprovalStatus.EXPIRED)
    }

    private class MutableTestClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneOffset.UTC
    }
}
