package com.duluin.ftth.collector.certification

import com.duluin.ftth.collector.adapter.junos.InMemoryJunosRollbackJournal
import com.duluin.ftth.collector.adapter.junos.JunosCandidateChange
import com.duluin.ftth.collector.adapter.junos.JunosConfirmedCommit
import com.duluin.ftth.collector.adapter.junos.JunosDeviceIdentity
import com.duluin.ftth.collector.adapter.junos.JunosHello
import com.duluin.ftth.collector.adapter.junos.JunosNetconfSession
import com.duluin.ftth.collector.adapter.junos.JunosOperationalObservation
import com.duluin.ftth.collector.adapter.junos.JunosProvisioningAdapter
import com.duluin.ftth.collector.adapter.junos.JunosRollbackReceipt
import com.duluin.ftth.contract.AdapterCertificationSubject
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPayloadValues
import com.duluin.ftth.contract.ProvisioningTarget
import java.time.Clock
import java.time.ZoneOffset

internal class JunosCertificationSubject : AdapterCertificationSubject {
    private val fixture = StatefulJunosSessionFactory()
    private val adapter = JunosProvisioningAdapter(
        sessionFactory = { fixture.open() },
        rollbackJournal = InMemoryJunosRollbackJournal(),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )
    private val target = NasTarget(
        "junos-17", "junos-17", "JUNIPER", "192.0.2.18", "JUNOS_NETCONF",
        apiUsername = "fixture", apiSecret = "fixture", apiPort = 830,
    )
    private val wireTarget = ProvisioningTarget("junos-17", "SWITCH", "192.0.2.18", "NETCONF_SSH")

    override val profileId = "junos-ex4300-21.4r3-s5.4-fixture"
    override val implementation = JunosProvisioningAdapter::class.qualifiedName.orEmpty()
    override val origin = ADAPTER_FIXTURE_ORIGIN

    override fun capabilityReport(): DeviceCapabilityReport = adapter.capabilityReport(target)

    override fun executePhase(phase: CertificationPhase) = when (phase) {
        CertificationPhase.CREATE -> {
            val result = adapter.execute(target, junosCommand(ProvisioningCommandPhase.APPLY, fixture.stateHash(), "create"))
            phaseResult(phase, result.success && result.apply?.changed == true)
        }
        CertificationPhase.VERIFY -> {
            val result = adapter.execute(target, junosCommand(ProvisioningCommandPhase.VERIFY, key = "verify"))
            phaseResult(phase, result.success && result.verification?.matchesExpected == true)
        }
        CertificationPhase.IDEMPOTENT_REPEAT -> {
            val result = adapter.execute(target, junosCommand(ProvisioningCommandPhase.APPLY, fixture.stateHash(), "repeat"))
            phaseResult(phase, result.success && result.apply?.changed == false)
        }
        CertificationPhase.ROLLBACK -> {
            val result = adapter.execute(target, junosCommand(ProvisioningCommandPhase.ROLLBACK, key = "rollback"))
            phaseResult(phase, result.errorCode == ProvisioningErrorCode.ROLLBACK_CONFLICT, "ROLLBACK_CONFLICT_DECLARED")
        }
        CertificationPhase.DELETE -> {
            val result = adapter.execute(
                target,
                junosCommand(ProvisioningCommandPhase.APPLY, fixture.stateHash(), "delete", "REMOVE_TAGGED_VLAN"),
            )
            phaseResult(phase, result.errorCode == ProvisioningErrorCode.UNSUPPORTED_CAPABILITY, "UNSUPPORTED_CAPABILITY_DECLARED")
        }
        CertificationPhase.OBSERVATION_ONLY -> {
            val edits = fixture.events.count { it == "edit" }
            val result = adapter.execute(
                target,
                junosCommand(ProvisioningCommandPhase.PREFLIGHT, key = "observe").copy(observationOnly = true),
            )
            phaseResult(phase, result.success && edits == fixture.events.count { it == "edit" })
        }
    }

    override fun verifyUnsupportedOperations(): Map<String, String> {
        val remove = adapter.execute(
            target,
            junosCommand(ProvisioningCommandPhase.APPLY, fixture.stateHash(), "unsupported-remove", "REMOVE_TAGGED_VLAN"),
        )
        val rollback = adapter.execute(target, junosCommand(ProvisioningCommandPhase.ROLLBACK, key = "unsupported-rollback"))
        return mapOf(
            "REMOVE_TAGGED_VLAN" to requireNotNull(remove.errorCode).name,
            "ROLLBACK" to requireNotNull(rollback.errorCode).name,
        )
    }

    private fun junosCommand(
        phase: ProvisioningCommandPhase,
        expectedHash: String? = null,
        key: String,
        operation: String = "ENSURE_TAGGED_VLAN",
    ) = command(phase, wireTarget, operation, expectedHash, "junos-$key", payload = junosPayload())

    private fun junosPayload() = ProvisioningPayload(
        ProvisioningPayloadValues(
            tenantId = "tenant-17",
            intentId = "intent-17",
            vlanId = VLAN.toString(),
            trunkPorts = "ge-0/0/0",
            accessPorts = "ge-0/0/1",
            vlanInterface = "irb.110",
            firewallChain = "ftth-110",
        ),
    )
}

private class StatefulJunosSessionFactory {
    val events = mutableListOf<String>()
    private var resources = emptySet<String>()

    fun stateHash(): String = JunosOperationalObservation(resources, true).let { observation ->
        com.duluin.ftth.collector.adapter.junos.JunosProvisioningResults(Clock.fixed(NOW, ZoneOffset.UTC)).stateHash(observation)
    }

    fun open(): JunosNetconfSession = Session()

    private inner class Session : JunosNetconfSession {
        private var pending: JunosCandidateChange? = null

        override fun hello() = JunosHello(
            JunosDeviceIdentity("EX4300-48P", "21.4R3-S5.4"),
            setOf(
                "urn:ietf:params:netconf:capability:candidate:1.0",
                "urn:ietf:params:netconf:capability:confirmed-commit:1.1",
                "urn:ietf:params:netconf:capability:validate:1.1",
            ),
        )

        override fun observe(change: JunosCandidateChange) = JunosOperationalObservation(resources, true)
        override fun lockCandidate() { events += "lock" }
        override fun editCandidate(change: JunosCandidateChange) { pending = change; events += "edit" }
        override fun validateCandidate() { events += "validate" }
        override fun commitConfirmed(timeoutSeconds: Int): JunosConfirmedCommit {
            resources = requireNotNull(pending).expectedResources
            return JunosConfirmedCommit("commit-17", "rollback-17", NOW.plusSeconds(timeoutSeconds.toLong()))
        }
        override fun confirmCommit(commitId: String) { events += "confirm" }
        override fun awaitAutomaticRollback(rollbackId: String) = JunosRollbackReceipt(rollbackId, JunosOperationalObservation(resources, true))
        override fun discardCandidate() { pending = null }
        override fun unlockCandidate() { events += "unlock" }
        override fun close() = Unit
    }
}
