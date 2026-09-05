package com.duluin.ftth.collector.certification

import com.duluin.ftth.contract.CertificationEvidenceOrigin
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.CertificationPhaseResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningTarget
import java.time.Instant

internal val NOW: Instant = Instant.parse("2026-09-03T12:00:00Z")
internal const val VLAN = 110

internal fun phaseResult(phase: CertificationPhase, passed: Boolean, detail: String = if (passed) "PASS" else "FAIL") =
    CertificationPhaseResult(phase, passed, detail)

internal fun command(
    phase: ProvisioningCommandPhase,
    target: ProvisioningTarget,
    operation: String = "ENSURE_TAGGED_VLAN",
    expectedHash: String? = null,
    key: String = phase.name,
    payload: ProvisioningPayload = transportPayload(),
    observationOnly: Boolean = false,
) = ProvisioningPlanStepCommand(
    planId = "task-17-plan",
    revision = 1,
    stepId = "task-17-step",
    attemptId = "task-17-$key",
    phase = phase,
    operationClass = operation,
    idempotencyKey = "task-17-$key",
    fencingEpoch = 17,
    expectedPreconditionHash = expectedHash,
    deadline = NOW.plusSeconds(300),
    target = target,
    payload = payload,
    observationOnly = observationOnly,
)

internal fun transportPayload() = ProvisioningPayload(
    mapOf(
        "tenantId" to "tenant-17",
        "intentId" to "intent-17",
        "vlanId" to VLAN.toString(),
        "tagging" to "SINGLE_TAG",
        "trunkPorts" to "GigabitEthernet1/0/48",
        "accessPorts" to "GigabitEthernet1/0/1",
        "vlanInterface" to "irb.110",
        "firewallChain" to "FTTH-IN",
        "managementPathProven" to "true",
        "protectedInterfaces" to "MGMT0",
        "protectedVlanIds" to "100",
    ),
)

internal val ADAPTER_FIXTURE_ORIGIN = CertificationEvidenceOrigin.ADAPTER_FIXTURE
