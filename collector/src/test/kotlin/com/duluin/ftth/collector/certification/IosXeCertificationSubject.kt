package com.duluin.ftth.collector.certification

import com.duluin.ftth.collector.adapter.iosxe.IosXeCapabilities
import com.duluin.ftth.collector.adapter.iosxe.IosXeCredentials
import com.duluin.ftth.collector.adapter.iosxe.IosXeDesiredConfiguration
import com.duluin.ftth.collector.adapter.iosxe.IosXeHello
import com.duluin.ftth.collector.adapter.iosxe.IosXeNetconfSession
import com.duluin.ftth.collector.adapter.iosxe.IosXeOperationalState
import com.duluin.ftth.collector.adapter.iosxe.IosXeProfiles
import com.duluin.ftth.collector.adapter.iosxe.IosXeProvisioningAdapter
import com.duluin.ftth.contract.AdapterCertificationSubject
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningTarget
import java.time.Clock
import java.time.ZoneOffset

internal class IosXeCertificationSubject : AdapterCertificationSubject {
    private val fixture = StatefulIosXeSessionFactory()
    private val adapter = IosXeProvisioningAdapter(fixture::open, Clock.fixed(NOW, ZoneOffset.UTC))
    private val target = NasTarget(
        "iosxe-17", "iosxe-17", "CISCO", "192.0.2.17", "IOS_XE_NETCONF",
        apiUsername = "fixture", apiSecret = "fixture", apiPort = 830,
    )
    private val wireTarget = ProvisioningTarget("iosxe-17", "SWITCH", "192.0.2.17", "NETCONF_SSH")

    override val profileId = "iosxe-c9300-17.18.1-fixture"
    override val implementation = IosXeProvisioningAdapter::class.qualifiedName.orEmpty()
    override val origin = ADAPTER_FIXTURE_ORIGIN

    override fun capabilityReport(): DeviceCapabilityReport = adapter.capabilityReport(target)

    override fun executePhase(phase: CertificationPhase) = when (phase) {
        CertificationPhase.CREATE -> {
            val result = adapter.execute(target, iosCommand(ProvisioningCommandPhase.APPLY, fixture.state.hash(), "create"))
            phaseResult(phase, result.success && result.apply?.changed == true)
        }
        CertificationPhase.VERIFY -> {
            val result = adapter.execute(target, iosCommand(ProvisioningCommandPhase.VERIFY, key = "verify"))
            phaseResult(phase, result.success && result.verification?.matchesExpected == true)
        }
        CertificationPhase.IDEMPOTENT_REPEAT -> {
            val result = adapter.execute(target, iosCommand(ProvisioningCommandPhase.APPLY, fixture.state.hash(), "repeat"))
            phaseResult(phase, result.success && result.apply?.changed == false)
        }
        CertificationPhase.ROLLBACK -> {
            val result = adapter.execute(target, iosCommand(ProvisioningCommandPhase.ROLLBACK, key = "rollback"))
            phaseResult(phase, result.errorCode == ProvisioningErrorCode.UNSUPPORTED_CAPABILITY, "UNSUPPORTED_CAPABILITY_DECLARED")
        }
        CertificationPhase.DELETE -> {
            val result = adapter.execute(
                target,
                iosCommand(ProvisioningCommandPhase.APPLY, fixture.state.hash(), "delete", "REMOVE_TAGGED_VLAN"),
            )
            phaseResult(phase, result.success && fixture.state.vlanPresent.not())
        }
        CertificationPhase.OBSERVATION_ONLY -> {
            val edits = fixture.events.count { it == "edit" }
            val result = adapter.execute(
                target,
                iosCommand(ProvisioningCommandPhase.PREFLIGHT, key = "observe").copy(observationOnly = true),
            )
            phaseResult(phase, result.success && edits == fixture.events.count { it == "edit" })
        }
    }

    override fun verifyUnsupportedOperations(): Map<String, String> {
        val result = adapter.execute(target, iosCommand(ProvisioningCommandPhase.ROLLBACK, key = "unsupported"))
        return mapOf("ROLLBACK" to requireNotNull(result.errorCode).name)
    }

    private fun iosCommand(
        phase: ProvisioningCommandPhase,
        expectedHash: String? = null,
        key: String,
        operation: String = "ENSURE_TAGGED_VLAN",
    ) = command(phase, wireTarget, operation, expectedHash, "iosxe-$key")
}

private class StatefulIosXeSessionFactory {
    val events = mutableListOf<String>()
    var state = IosXeOperationalState(
        vlanPresent = false,
        vlanId = null,
        trunkInterfaces = emptySet(),
        accessInterfaces = emptySet(),
        aclApplied = false,
        managementReachable = true,
    )

    fun open(target: NasTarget, credentials: IosXeCredentials): IosXeNetconfSession {
        require(target.nasId == "iosxe-17" && credentials.username == "fixture")
        return Session()
    }

    private inner class Session : IosXeNetconfSession {
        private var confirmed = false

        override fun hello() = IosXeHello(
            "Cisco IOS XE",
            "C9300-24T",
            "17.18.1",
            IosXeCapabilities.PROTOCOL,
            IosXeProfiles.CATALYST_9300_17_18.requiredModules,
        )

        override fun readBaseline() = state
        override fun lockCandidate() { events += "lock" }
        override fun discardChanges() { events += "discard" }
        override fun editCandidate(xml: String) { events += "edit" }
        override fun validateCandidate() { events += "validate" }
        override fun confirmedCommit(timeoutSeconds: Int) { confirmed = true; events += "confirmed:$timeoutSeconds" }

        override fun verifyOperational(expected: IosXeDesiredConfiguration): IosXeOperationalState {
            if (confirmed) {
                state = if (expected.remove) {
                    IosXeOperationalState(
                        vlanPresent = false,
                        vlanId = null,
                        trunkInterfaces = emptySet(),
                        accessInterfaces = emptySet(),
                        aclApplied = false,
                        managementReachable = true,
                    )
                } else {
                    IosXeOperationalState(
                        true,
                        expected.vlanId,
                        expected.trunkInterfaces,
                        expected.accessInterfaces,
                        expected.aclName != null,
                        true,
                    )
                }
            }
            return state
        }

        override fun finalCommit() { events += "final" }
        override fun unlockCandidate() { events += "unlock" }
        override fun awaitDeviceRollback(expectedStateHash: String, timeoutSeconds: Int) = state
        override fun close() = Unit
    }
}
