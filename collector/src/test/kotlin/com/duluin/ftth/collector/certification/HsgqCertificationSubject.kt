package com.duluin.ftth.collector.certification

import com.duluin.ftth.collector.adapter.hsgq.FixtureSession
import com.duluin.ftth.collector.adapter.hsgq.HsgqOperation
import com.duluin.ftth.collector.adapter.hsgq.adapter
import com.duluin.ftth.collector.adapter.hsgq.command
import com.duluin.ftth.collector.adapter.hsgq.target
import com.duluin.ftth.contract.AdapterCertificationSubject
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.OltManagementTransport
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode

internal class HsgqCertificationSubject : AdapterCertificationSubject {
    private val session = FixtureSession()
    private val executable = adapter(session)
    private val provisional = adapter(session, certifications = emptyList())
    private val olt = target()
    private var beforeHash: String? = null
    private var afterHash: String? = null

    override val profileId = "hsgq-e04i-v1.2.3-fixture"
    override val implementation = executable::class.qualifiedName.orEmpty()
    override val origin = ADAPTER_FIXTURE_ORIGIN

    override fun capabilityReport(): DeviceCapabilityReport = provisional.capabilityReport(olt)

    override fun executePhase(phase: CertificationPhase) = when (phase) {
        CertificationPhase.CREATE -> {
            val preflight = executable.execute(olt, hsgqCommand(ProvisioningCommandPhase.PREFLIGHT, "preflight"))
            beforeHash = preflight.preflight?.preconditionHash
            val applied = executable.execute(olt, hsgqCommand(ProvisioningCommandPhase.APPLY, "create", beforeHash))
            afterHash = applied.verification?.stateHash
            phaseResult(phase, preflight.success && applied.success && applied.apply?.changed == true)
        }
        CertificationPhase.VERIFY -> {
            val result = executable.execute(olt, hsgqCommand(ProvisioningCommandPhase.VERIFY, "verify"))
            phaseResult(phase, result.success && result.verification?.matchesExpected == true)
        }
        CertificationPhase.IDEMPOTENT_REPEAT -> {
            val calls = session.calls.size
            val result = executable.execute(olt, hsgqCommand(ProvisioningCommandPhase.APPLY, "repeat", beforeHash))
            phaseResult(phase, result.success && result.apply?.changed == false && session.calls.size == calls + 1)
        }
        CertificationPhase.ROLLBACK -> {
            val result = executable.execute(olt, hsgqCommand(ProvisioningCommandPhase.ROLLBACK, "rollback", afterHash))
            phaseResult(phase, result.success && result.rollback?.success == true)
        }
        CertificationPhase.DELETE -> {
            val prepare = executable.execute(
                olt,
                hsgqCommand(ProvisioningCommandPhase.APPLY, "delete-prepare", beforeHash).copy(stepId = "step-delete-prepare"),
            )
            val removePreflight = executable.execute(
                olt,
                hsgqCommand(ProvisioningCommandPhase.PREFLIGHT, "delete-preflight", operation = HsgqOperation.REMOVE_TAGGED_VLAN)
                    .copy(stepId = "step-delete"),
            )
            val removed = executable.execute(
                olt,
                hsgqCommand(
                    ProvisioningCommandPhase.APPLY,
                    "delete",
                    removePreflight.preflight?.preconditionHash,
                    HsgqOperation.REMOVE_TAGGED_VLAN,
                ).copy(stepId = "step-delete"),
            )
            phaseResult(phase, prepare.success && removePreflight.success && removed.success && session.running.managedResourceCount() == 0)
        }
        CertificationPhase.OBSERVATION_ONLY -> {
            val mutations = session.calls.count { it.startsWith("ensure") || it.startsWith("remove") || it == "restore" }
            val observed = executable.execute(
                olt,
                hsgqCommand(ProvisioningCommandPhase.PREFLIGHT, "observe").copy(
                    stepId = "step-observe",
                    observationOnly = true,
                ),
            )
            val after = session.calls.count { it.startsWith("ensure") || it.startsWith("remove") || it == "restore" }
            phaseResult(phase, observed.success && mutations == after)
        }
    }

    override fun verifyUnsupportedOperations(): Map<String, String> {
        val result = executable.execute(
            target(transport = OltManagementTransport.TELNET),
            hsgqCommand(ProvisioningCommandPhase.APPLY, "telnet"),
        )
        return mapOf("TELNET_MUTATION" to requireNotNull(result.errorCode).name)
    }

    private fun hsgqCommand(
        phase: ProvisioningCommandPhase,
        key: String,
        expectedHash: String? = null,
        operation: String = HsgqOperation.ENSURE_TAGGED_VLAN,
    ) = command(phase, operation, "matrix-$key", expectedHash)
}
