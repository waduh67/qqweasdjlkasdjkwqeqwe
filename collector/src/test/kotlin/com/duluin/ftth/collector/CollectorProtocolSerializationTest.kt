package com.duluin.ftth.collector

import com.duluin.ftth.contract.CollectorConfig
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningRollbackResult
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorProtocolSerializationTest {
    data class LegacyCollectorConfig(
        val collectorName: String,
        val pollIntervalSeconds: Int,
        val targets: List<Any>,
        val paused: Boolean = false,
    )

    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    @Test
    fun `complete provisioning plan step round trips without secrets`() {
        val command = ProvisioningPlanStepCommand(
            planId = "plan-1",
            revision = 3,
            stepId = "step-2",
            phase = ProvisioningCommandPhase.APPLY,
            operationClass = "ENSURE_TAGGED_VLAN",
            idempotencyKey = "plan-1:3:step-2:apply",
            expectedPreconditionHash = "before-sha256",
            deadline = Instant.parse("2026-09-02T12:30:00Z"),
            target = ProvisioningTarget(
                deviceId = "switch-1",
                deviceKind = "SWITCH",
                managementAddress = "10.0.0.2",
                transport = "SSH",
            ),
            payload = ProvisioningPayload(
                mapOf("vlanId" to "110", "interface" to "xe-0/0/1", "tagging" to "TAGGED"),
            ),
        )

        val json = mapper.writeValueAsString(command)
        val restored = mapper.readValue(json, ProvisioningPlanStepCommand::class.java)

        assertEquals(command, restored)
        listOf("password", "secret", "credential", "token", "rawCli").forEach {
            assertFalse(json.contains(it, ignoreCase = true), "serialized command leaked forbidden key $it")
        }
    }

    @Test
    fun `old heartbeat and config fixtures deserialize with empty provisioning defaults`() {
        val oldHeartbeat = mapper.readValue(
            """{"agentVersion":"1.4.0","protocolVersion":1,"actionResults":[]}""",
            CollectorHeartbeat::class.java,
        )
        val oldConfig = mapper.readValue(
            """{"collectorName":"legacy","pollIntervalSeconds":60,"targets":[],"paused":false,"nasTargets":[],"bngActions":[]}""",
            CollectorConfig::class.java,
        )

        assertTrue(oldHeartbeat.deviceReports.isEmpty())
        assertTrue(oldHeartbeat.provisioningResults.isEmpty())
        assertTrue(oldConfig.provisioningCommands.isEmpty())
    }

    @Test
    fun `legacy collector ignores additive provisioning commands from new server`() {
        val command = ProvisioningPlanStepCommand(
            planId = "plan-1",
            revision = 1,
            stepId = "step-1",
            phase = ProvisioningCommandPhase.PREFLIGHT,
            operationClass = "ENSURE_TAGGED_VLAN",
            idempotencyKey = "plan-1:1:step-1:preflight",
            deadline = Instant.parse("2026-09-02T12:30:00Z"),
            target = ProvisioningTarget("switch-1", "SWITCH", "10.0.0.2", "SSH"),
        )
        val json = mapper.writeValueAsString(
            CollectorConfig("legacy", 60, emptyList(), provisioningCommands = listOf(command)),
        )

        val legacy = mapper.readValue(json, LegacyCollectorConfig::class.java)

        assertEquals("legacy", legacy.collectorName)
        assertEquals(60, legacy.pollIntervalSeconds)
        assertTrue(legacy.targets.isEmpty())
    }

    @Test
    fun `new protocol fields and result codes serialize with stable values`() {
        val observedAt = Instant.parse("2026-09-02T12:31:00Z")
        val result = ProvisioningStepResult(
            planId = "plan-1",
            revision = 3,
            stepId = "step-2",
            operationClass = "ENSURE_TAGGED_VLAN",
            idempotencyKey = "plan-1:3:step-2:apply",
            success = true,
            completedAt = observedAt,
            apply = ProvisioningApplyResult(appliedAt = observedAt, changed = true, resultingStateHash = "after-sha256"),
            verification = ProvisioningVerificationObservation(
                observedAt = observedAt,
                matchesExpected = true,
                stateHash = "after-sha256",
                state = ProvisioningPayload(mapOf("vlanId" to "110")),
            ),
        )
        val heartbeat = CollectorHeartbeat(
            agentVersion = "2.0.0",
            deviceReports = listOf(
                DeviceCapabilityReport(
                    targetId = "switch-1",
                    fingerprint = DeviceFingerprint("JUNIPER", "EX2300", "23.4R1", "NETCONF"),
                    capabilities = setOf("ENSURE_TAGGED_VLAN"),
                    reportedAt = observedAt,
                ),
            ),
            provisioningResults = listOf(result),
        )

        val json = mapper.writeValueAsString(heartbeat)
        assertEquals(heartbeat, mapper.readValue(json, CollectorHeartbeat::class.java))
        assertEquals(
            listOf(
                "UNSUPPORTED_CAPABILITY",
                "STALE_PRECONDITION",
                "VERIFICATION_MISMATCH",
                "TIMEOUT",
                "ROLLBACK_CONFLICT",
                "MANUAL_RECONCILIATION",
                "VALIDATION_FAILED",
                "MANAGEMENT_PATH_UNPROVEN",
                "PROTECTED_RESOURCE",
                "INSECURE_TRANSPORT",
            ),
            ProvisioningErrorCode.entries.map { it.name },
        )
    }

    @Test
    fun `successful preflight may report desired mismatch while apply stays strict`() {
        val at = Instant.parse("2026-09-02T12:31:00Z")
        val preflight = ProvisioningStepResult(
            planId = "plan-1",
            revision = 1,
            stepId = "step-1",
            operationClass = "ENSURE_TAGGED_VLAN",
            idempotencyKey = "preflight",
            phase = ProvisioningCommandPhase.PREFLIGHT,
            success = true,
            completedAt = at,
            preflight = com.duluin.ftth.contract.ProvisioningPreflightSnapshot(at, "before"),
            verification = ProvisioningVerificationObservation(at, false, "before"),
        )

        assertFalse(preflight.verification!!.matchesExpected)
        assertFailsWith<IllegalArgumentException> {
            preflight.copy(
                phase = ProvisioningCommandPhase.APPLY,
                preflight = null,
                apply = ProvisioningApplyResult(at, changed = false, resultingStateHash = "before"),
            )
        }
    }

    @Test
    fun `redacted payload rejects sensitive keys and success requires verification`() {
        listOf(
            "password",
            "apiSecret",
            "deviceCredential",
            "accessToken",
            "raw_cli",
            "snmpCommunity",
            "privateKey",
            "passphrase",
        ).forEach { key ->
            assertFailsWith<IllegalArgumentException> { ProvisioningPayload(mapOf(key to "must-not-leak")) }
        }
        listOf(
            "password=hunter2",
            "token: reusable-token",
            "-----BEGIN PRIVATE KEY-----",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> { ProvisioningPayload(mapOf("configuration" to value)) }
        }

        assertFailsWith<IllegalArgumentException> {
            ProvisioningStepResult(
                planId = "plan-1",
                revision = 1,
                stepId = "step-1",
                operationClass = "ENSURE_TAGGED_VLAN",
                idempotencyKey = "key",
                success = true,
                completedAt = Instant.parse("2026-09-02T12:31:00Z"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvisioningStepResult(
                planId = "plan-1",
                revision = 1,
                stepId = "step-1",
                operationClass = "ENSURE_TAGGED_VLAN",
                idempotencyKey = "key",
                success = false,
                completedAt = Instant.parse("2026-09-02T12:31:00Z"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvisioningStepResult(
                planId = "plan-1",
                revision = 1,
                stepId = "step-1",
                operationClass = "ENSURE_TAGGED_VLAN",
                idempotencyKey = "key",
                success = true,
                completedAt = Instant.parse("2026-09-02T12:31:00Z"),
                errorCode = ProvisioningErrorCode.MANUAL_RECONCILIATION,
                apply = ProvisioningApplyResult(
                    appliedAt = Instant.parse("2026-09-02T12:30:00Z"),
                    changed = true,
                    resultingStateHash = "after-sha256",
                ),
                verification = ProvisioningVerificationObservation(
                    observedAt = Instant.parse("2026-09-02T12:31:00Z"),
                    matchesExpected = true,
                    stateHash = "after-sha256",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvisioningRollbackResult(
                completedAt = Instant.parse("2026-09-02T12:31:00Z"),
                success = true,
                errorCode = ProvisioningErrorCode.ROLLBACK_CONFLICT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProvisioningRollbackResult(
                completedAt = Instant.parse("2026-09-02T12:31:00Z"),
                success = false,
            )
        }

        val source = mutableMapOf("vlanId" to "110")
        val payload = ProvisioningPayload(source)
        source["vlanId"] = "999"
        assertEquals("110", payload.values.getValue("vlanId"))
        assertFalse(payload.toString().contains("110"))
    }
}
