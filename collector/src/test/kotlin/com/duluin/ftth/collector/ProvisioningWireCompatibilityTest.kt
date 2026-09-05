package com.duluin.ftth.collector

import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.ProvisioningAcknowledgement
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPayloadValues
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvisioningWireCompatibilityTest {
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    @Test
    fun `legacy heartbeat and config retain additive empty defaults`() {
        val heartbeat = mapper.readValue("""{"agentVersion":"legacy"}""", CollectorHeartbeat::class.java)
        val config = mapper.readValue(
            """{"collectorName":"legacy","pollIntervalSeconds":60,"targets":[]}""",
            CollectorConfig::class.java,
        )

        assertTrue(heartbeat.deviceReports.isEmpty())
        assertTrue(heartbeat.provisioningResults.isEmpty())
        assertTrue(config.provisioningCommands.isEmpty())
        assertEquals(ProvisioningAcknowledgement(), config.provisioningAcknowledgement)
    }

    @Test
    fun `legacy provisioning command defaults phase and fencing epoch`() {
        val command = mapper.readValue(
            """{
                "planId":"plan-1",
                "revision":1,
                "stepId":"step-1",
                "operationClass":"ENSURE_TAGGED_VLAN",
                "idempotencyKey":"attempt-1",
                "deadline":"2026-09-02T12:30:00Z",
                "target":{"deviceId":"device-1","deviceKind":"BRAS","managementAddress":"router.invalid","transport":"HTTPS_REST"},
                "payload":{"values":{"vlanId":"110","trunkPorts":"ether1,ether2","accessPorts":"ether3",
                    "vlanInterface":"svc-110","pppoeVlanRange":"110-120","managementPathProven":"true",
                    "protectedInterfaces":"mgmt,loopback","protectedVlanIds":"99,100"}}
            }""".trimIndent(),
            ProvisioningPlanStepCommand::class.java,
        )

        assertEquals(ProvisioningCommandPhase.APPLY, command.phase)
        assertEquals(0, command.fencingEpoch)
        assertEquals(null, command.attemptId)
        assertEquals("110", command.payload.values.vlanId)
        assertEquals("ether1,ether2", command.payload.values.trunkPorts)
        assertEquals("110-120", command.payload.values.pppoeVlanRange)
        assertEquals("true", command.payload.values.managementPathProven)
        assertEquals(null, command.payload.values.managementSourceId)
        assertEquals(null, command.payload.values.managementSourceType)
        assertFalse(command.observationOnly)
    }

    @Test
    fun `legacy provisioning result defaults phase to apply`() {
        val hash = "a".repeat(64)
        val result = mapper.readValue(
            """{
                "planId":"plan-1",
                "revision":1,
                "stepId":"step-1",
                "operationClass":"ENSURE_TAGGED_VLAN",
                "idempotencyKey":"attempt-1",
                "success":true,
                "completedAt":"2026-09-02T12:30:00Z",
                "apply":{"appliedAt":"2026-09-02T12:29:59Z","changed":true,"resultingStateHash":"$hash"},
                "verification":{"observedAt":"2026-09-02T12:30:00Z","matchesExpected":true,"stateHash":"$hash"}
            }""".trimIndent(),
            ProvisioningStepResult::class.java,
        )

        assertEquals(ProvisioningCommandPhase.APPLY, result.phase)
        assertEquals(null, result.attemptId)
        assertEquals(null, result.targetId)
        assertEquals(0, result.fencingEpoch)
    }

    @Test
    fun `new command JSON pins exact property names and scalar types`() {
        val command = ProvisioningPlanStepCommand(
            planId = "plan-1",
            revision = 2,
            stepId = "step-1",
            attemptId = "attempt-id-1",
            phase = ProvisioningCommandPhase.VERIFY,
            operationClass = "VERIFY_STATE",
            idempotencyKey = "attempt-1",
            fencingEpoch = 7,
            expectedPreconditionHash = "a".repeat(64),
            deadline = Instant.parse("2026-09-02T12:30:00Z"),
            target = ProvisioningTarget("device-1", "BRAS", "router.invalid", "HTTPS_REST"),
            payload = ProvisioningPayload(
                ProvisioningPayloadValues(
                    vlanId = "110",
                    vlanInterface = "ether2",
                    managementSourceId = "0199386e-9718-7000-8000-000000000201",
                    managementSourceType = "TOPOLOGY_OBSERVATION",
                ),
            ),
        )

        val root = mapper.readTree(mapper.writeValueAsString(command))

        assertEquals(
            setOf(
                "planId", "revision", "stepId", "attemptId", "phase", "operationClass", "idempotencyKey",
                "fencingEpoch", "expectedPreconditionHash", "deadline", "target", "payload",
                "observationOnly",
            ),
            root.properties().map { it.key }.toSet(),
        )
        assertTrue(root.get("revision").isIntegralNumber)
        assertTrue(root.get("fencingEpoch").isIntegralNumber)
        assertTrue(root.get("phase").isTextual)
        assertTrue(root.get("target").isObject)
        assertTrue(root.get("payload").isObject)
        assertEquals(
            setOf(
                "tenantId", "intentId", "bridge", "vlanId", "tagging", "trunkPorts", "accessPorts",
                "vlanInterface", "vlanParent", "pppoeInterface", "pppoeServiceName", "pppoeVlanRange",
                "poolName", "poolRanges", "interfaceList", "firewallChain", "managementPathProven",
                "managementSourceId", "managementSourceType", "protectedInterfaces", "protectedVlanIds",
            ),
            root.at("/payload/values").properties().map { it.key }.toSet(),
        )
        assertEquals(setOf("values"), root.get("payload").properties().map { it.key }.toSet())
        assertTrue(root.at("/payload/values/vlanId").isTextual)
        assertTrue(root.at("/payload/values/trunkPorts").isNull)
        assertTrue(root.at("/payload/values/managementPathProven").isNull)
        assertEquals("0199386e-9718-7000-8000-000000000201", root.at("/payload/values/managementSourceId").asString())
        assertEquals("TOPOLOGY_OBSERVATION", root.at("/payload/values/managementSourceType").asString())
    }

    @Test
    fun `new config JSON pins dedicated provisioning acknowledgement shape`() {
        val config = CollectorConfig(
            collectorName = "collector-1",
            pollIntervalSeconds = 60,
            targets = emptyList(),
            provisioningAcknowledgement = ProvisioningAcknowledgement(
                resultIdempotencyKeys = setOf("attempt-1"),
                deviceReportKeys = setOf("report-1"),
            ),
        )

        val root = mapper.readTree(mapper.writeValueAsString(config))

        assertEquals(
            setOf(
                "collectorName", "pollIntervalSeconds", "targets", "paused", "nasTargets", "bngActions",
                "provisioningCommands", "provisioningAcknowledgement",
            ),
            root.properties().map { it.key }.toSet(),
        )
        assertEquals(
            setOf("resultIdempotencyKeys", "resultAttemptIds", "deviceReportKeys"),
            root.get("provisioningAcknowledgement").properties().map { it.key }.toSet(),
        )
        assertTrue(root.at("/provisioningAcknowledgement/resultIdempotencyKeys").isArray)
        assertTrue(root.at("/provisioningAcknowledgement/resultAttemptIds").isArray)
        assertTrue(root.at("/provisioningAcknowledgement/deviceReportKeys").isArray)
    }

    @Test
    fun `new heartbeat JSON pins dedicated report and result arrays`() {
        val report = DeviceCapabilityReport(
            targetId = "device-1",
            fingerprint = DeviceFingerprint("MIKROTIK", "CCR", "7.20", "HTTPS_REST"),
            reportedAt = Instant.parse("2026-09-02T12:00:00Z"),
        )
        val result = ProvisioningStepResult(
            planId = "plan-1",
            revision = 1,
            stepId = "step-1",
            attemptId = "attempt-id-1",
            operationClass = "VERIFY_STATE",
            idempotencyKey = "attempt-1",
            success = false,
            completedAt = Instant.parse("2026-09-02T12:00:01Z"),
            errorCode = ProvisioningErrorCode.VERIFICATION_MISMATCH,
        )

        val root = mapper.readTree(
            mapper.writeValueAsString(CollectorHeartbeat("collector-1", deviceReports = listOf(report), provisioningResults = listOf(result))),
        )

        assertEquals(
            setOf("agentVersion", "protocolVersion", "lastCycle", "actionResults", "deviceReports", "provisioningResults"),
            root.properties().map { it.key }.toSet(),
        )
        assertTrue(root.get("deviceReports").isArray)
        assertTrue(root.get("provisioningResults").isArray)
        assertEquals(
            setOf(
                "planId", "revision", "stepId", "attemptId", "targetId", "operationClass", "idempotencyKey",
                "fencingEpoch", "phase", "success", "completedAt", "errorCode", "preflight", "apply",
                "verification", "rollback",
            ),
            root.at("/provisioningResults/0").properties().map { it.key }.toSet(),
        )
        assertEquals("attempt-id-1", root.at("/provisioningResults/0/attemptId").asString())
    }

    @Test
    fun `result state has a closed secret-free JSON vocabulary`() {
        val state = ProvisioningResultState(
            managedResourceCount = 1,
        )
        val root = mapper.readTree(mapper.writeValueAsString(state))

        assertEquals(setOf("managedResourceCount", "vlanIds"), root.properties().map { it.key }.toSet())
        assertTrue(root.get("managedResourceCount").isIntegralNumber)
        assertTrue(root.get("vlanIds").isArray)
        assertFalse(root.toString().contains("values"))
    }

    @Test
    fun `legacy map parser rejects fields outside the closed command vocabulary`() {
        assertFailsWith<IllegalArgumentException> {
            ProvisioningPayload(mapOf("opaqueExtension" to "not-allowed"))
        }
    }
}
