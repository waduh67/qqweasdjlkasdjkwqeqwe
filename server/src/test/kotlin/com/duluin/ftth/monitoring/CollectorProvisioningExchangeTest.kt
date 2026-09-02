package com.duluin.ftth.monitoring

import com.duluin.ftth.common.integration.CollectorProvisioningChannel
import com.duluin.ftth.common.integration.ProvisioningDispatch
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.ProvisioningAcknowledgement
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPayloadValues
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.monitoring.application.service.CollectorProvisioningExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CollectorProvisioningExchangeTest {
    private val collectorId = UUID.fromString("0199386e-9718-7000-8000-000000000001")
    private val tenantId = UUID.fromString("0199386e-9718-7000-8000-000000000002")
    private val reportedAt = Instant.parse("2026-09-02T12:00:00Z")

    @Test
    fun `exchange emits dedicated command and returns only durable acknowledgements`() {
        val result = failedResult("attempt-accepted")
        val stale = failedResult("attempt-stale")
        val report = DeviceCapabilityReport(
            targetId = "device-1",
            fingerprint = DeviceFingerprint("MIKROTIK", "CCR", "7.20", "HTTPS_REST"),
            capabilities = setOf("SINGLE_TAG_802_1Q"),
            reportedAt = reportedAt,
            operationClasses = setOf("ENSURE_TAGGED_VLAN"),
        )
        val unownedReport = report.copy(targetId = "device-unowned")
        val channel = RecordingChannel(result.idempotencyKey, "device-1@$reportedAt")
        val exchange = CollectorProvisioningExchange(listOf(channel))

        val response = exchange.exchange(
            collectorId,
            tenantId,
            CollectorHeartbeat(
                "collector-1",
                deviceReports = listOf(report, unownedReport),
                provisioningResults = listOf(result, stale),
            ),
            listOf(ProvisioningTarget("device-1", "BRAS", "router.invalid", "HTTPS_REST")),
        )

        assertThat(response.commands.map { it.idempotencyKey }).containsExactly("attempt-pending")
        assertThat(response.acknowledgement.resultIdempotencyKeys).containsExactly("attempt-accepted")
        assertThat(response.acknowledgement.deviceReportKeys).containsExactly("device-1@$reportedAt")
        assertThat(channel.receivedResults).containsExactly(result, stale)
        assertThat(channel.receivedReports).containsExactly(report)
        assertThat(channel.receivedReports.single().operationClasses).containsExactly("ENSURE_TAGGED_VLAN")
    }

    private fun failedResult(key: String) = ProvisioningStepResult(
        planId = "plan-1",
        revision = 1,
        stepId = "step-1",
        operationClass = "ENSURE_TAGGED_VLAN",
        idempotencyKey = key,
        success = false,
        completedAt = reportedAt,
        errorCode = ProvisioningErrorCode.STALE_PRECONDITION,
    )

    private class RecordingChannel(
        private val acceptedResult: String,
        private val acceptedReport: String,
    ) : CollectorProvisioningChannel {
        val receivedResults = mutableListOf<ProvisioningStepResult>()
        val receivedReports = mutableListOf<DeviceCapabilityReport>()

        override fun pendingFor(
            collectorId: UUID,
            tenantId: UUID,
            availableTargetIds: Set<String>,
        ): List<ProvisioningDispatch> = listOf(
            ProvisioningDispatch(
                planId = "plan-1",
                revision = 1,
                stepId = "step-1",
                attemptId = "attempt-id-pending",
                phase = ProvisioningCommandPhase.APPLY,
                operationClass = "ENSURE_TAGGED_VLAN",
                idempotencyKey = "attempt-pending",
                fencingEpoch = 3,
                expectedPreconditionHash = "a".repeat(64),
                deadline = Instant.parse("2026-09-02T12:05:00Z"),
                deviceId = "device-1",
                deviceKind = "BRAS",
                payload = ProvisioningPayload(ProvisioningPayloadValues(vlanId = "110", vlanInterface = "ether2")),
            ),
        )

        override fun accept(
            collectorId: UUID,
            tenantId: UUID,
            availableTargets: Map<String, ProvisioningTarget>,
            results: List<ProvisioningStepResult>,
            reports: List<DeviceCapabilityReport>,
        ): ProvisioningAcknowledgement {
            receivedResults += results
            receivedReports += reports.filter { it.targetId in availableTargets }
            return ProvisioningAcknowledgement(
                resultIdempotencyKeys = setOf(acceptedResult),
                deviceReportKeys = setOf(acceptedReport),
            )
        }
    }
}
