package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.OltManagementTransport
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal const val EVIDENCE_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val BEFORE_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

internal fun target(
    firmware: String = "V1.2.3-certified",
    transport: OltManagementTransport = OltManagementTransport.HTTPS_API,
) = OltTarget(
    oltId = "olt-hsgq-1",
    oltCode = "OLT-LAB-HSGQ",
    vendor = "HSGQ",
    host = "192.0.2.10",
    snmpPort = 1161,
    snmpCommunity = null,
    model = "HSGQ-E04I",
    firmware = firmware,
    managementTransport = transport,
    managementPort = if (transport == OltManagementTransport.SSH) 22 else 443,
    managementCredentialRef = "credential:hsgq-lab",
)

internal fun command(
    phase: ProvisioningCommandPhase,
    operation: String = "ENSURE_TAGGED_VLAN",
    idempotencyKey: String = "task-9-command",
    expectedHash: String? = BEFORE_HASH,
) = ProvisioningPlanStepCommand(
    planId = "plan-9",
    revision = 1,
    stepId = "step-hsgq",
    attemptId = "attempt-$idempotencyKey-$phase",
    phase = phase,
    operationClass = operation,
    idempotencyKey = idempotencyKey,
    fencingEpoch = 1,
    expectedPreconditionHash = expectedHash,
    deadline = Instant.parse("2026-09-02T00:05:00Z"),
    target = com.duluin.ftth.contract.ProvisioningTarget("olt-hsgq-1", "OLT", "192.0.2.10", "HTTPS_API"),
    payload = ProvisioningPayload(
        mapOf(
            "vlanId" to "3901",
            "tagging" to "SINGLE_TAG",
            "trunkPorts" to "GE1",
            "accessPorts" to "EPON1/1:ONU1",
            "managementPathProven" to "true",
            "protectedInterfaces" to "MGMT0",
            "protectedVlanIds" to "100",
        ),
    ),
)

internal fun fixtureState(
    bindings: Set<HsgqSubscriberVlanBinding> = emptySet(),
    uplinks: Set<HsgqTaggedUplinkMembership> = emptySet(),
) = HsgqDeviceState(
    model = "HSGQ-E04I",
    firmware = "V1.2.3-certified",
    managementVlanId = 100,
    managementInterface = "MGMT0",
    subscriberBindings = bindings,
    taggedUplinks = uplinks,
)

internal class FixtureSession(
    initial: HsgqDeviceState = fixtureState(),
    private val failure: HsgqTransportFailure? = null,
) : HsgqManagementSession {
    var running = initial
    var persisted = initial
    val calls = mutableListOf<String>()

    override fun discover(): HsgqDeviceState {
        calls += "discover"
        failure?.takeIf { it.stage == HsgqFailureStage.DISCOVERY }?.let { throw it }
        return running
    }

    override fun ensureSubscriberVlan(desired: HsgqDesiredVlan) {
        calls += "ensure-binding"
        running = running.copy(
            subscriberBindings = running.subscriberBindings + HsgqSubscriberVlanBinding(desired.vlanId, desired.subscriberPort),
        )
    }

    override fun ensureTaggedUplink(desired: HsgqDesiredVlan, uplink: String) {
        calls += "ensure-uplink:$uplink"
        running = running.copy(taggedUplinks = running.taggedUplinks + HsgqTaggedUplinkMembership(desired.vlanId, uplink))
    }

    override fun removeSubscriberVlan(desired: HsgqDesiredVlan) {
        calls += "remove-binding"
        running = running.copy(
            subscriberBindings = running.subscriberBindings - HsgqSubscriberVlanBinding(desired.vlanId, desired.subscriberPort),
        )
    }

    override fun removeTaggedUplink(desired: HsgqDesiredVlan, uplink: String) {
        calls += "remove-uplink:$uplink"
        running = running.copy(taggedUplinks = running.taggedUplinks - HsgqTaggedUplinkMembership(desired.vlanId, uplink))
    }

    override fun restore(state: HsgqDeviceState) {
        calls += "restore"
        running = state
    }

    override fun persist() {
        calls += "persist"
        failure?.takeIf { it.stage == HsgqFailureStage.PERSISTENCE }?.let { throw it }
        persisted = running
    }

    override fun reconnect() {
        calls += "reconnect"
        running = persisted
    }

    override fun close() = Unit
}

internal fun adapter(
    session: FixtureSession,
    certifications: List<HsgqCertification> = listOf(certification()),
    store: HsgqProvisioningStateStore = InMemoryHsgqProvisioningStateStore(),
) = HsgqProvisioningAdapter(
    sessionFactory = HsgqManagementSessionFactory { _, _ -> session },
    credentialResolver = HsgqCredentialResolver { HsgqCredentials("operator", "redacted-fixture-secret") },
    certifications = HsgqCertificationRegistry(certifications),
    stateStore = store,
    clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC),
)

internal fun certification() = HsgqCertification(
    fingerprint = HsgqFirmwareFingerprint("HSGQ-E04I", "V1.2.3-certified", OltManagementTransport.HTTPS_API),
    operationClasses = setOf("ENSURE_TAGGED_VLAN", "REMOVE_TAGGED_VLAN", "VERIFY_STATE"),
    evidenceSha256 = EVIDENCE_SHA256,
)
