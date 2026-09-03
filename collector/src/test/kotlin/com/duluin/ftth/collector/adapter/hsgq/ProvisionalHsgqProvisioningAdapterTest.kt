package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.OltManagementTransport
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningTarget
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvisionalHsgqProvisioningAdapterTest {
    private val now = Instant.parse("2026-09-03T06:00:00Z")
    private val adapter = ProvisionalHsgqProvisioningAdapter(Clock.fixed(now, ZoneOffset.UTC))
    private val target = OltTarget(
        oltId = "olt-1",
        oltCode = "OLT-HSGQ",
        vendor = "HSGQ",
        host = "10.0.0.10",
        snmpCommunity = null,
        model = "HSGQ-E04I",
        firmware = "V1.0.0",
        managementTransport = OltManagementTransport.SSH,
    )

    @Test
    fun `reports provisional identity without executable operations`() {
        val report = adapter.capabilityReport(target)

        assertEquals("HSGQ", report.fingerprint.vendor)
        assertEquals(setOf("CERTIFICATION_PROVISIONAL"), report.capabilities)
        assertTrue(report.operationClasses.isEmpty())
    }

    @Test
    fun `rejects every command without opening device transport`() {
        val command = ProvisioningPlanStepCommand(
            planId = "plan-1",
            revision = 1,
            stepId = "step-1",
            phase = ProvisioningCommandPhase.APPLY,
            operationClass = "ENSURE_ACCESS_PORT",
            idempotencyKey = "plan-1:step-1:apply",
            deadline = now.plusSeconds(30),
            target = ProvisioningTarget(target.oltId, "OLT", target.host, "SSH"),
        )

        val result = adapter.execute(target, command)

        assertFalse(result.success)
        assertEquals(ProvisioningErrorCode.UNCERTIFIED_FINGERPRINT, result.errorCode)
    }
}
