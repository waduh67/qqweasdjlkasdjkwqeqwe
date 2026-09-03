package com.duluin.ftth.collector.certification

import com.duluin.ftth.collector.adapter.InMemoryRouterOsProvisioningStateStore
import com.duluin.ftth.collector.adapter.RouterOsProvisioningAdapter
import com.duluin.ftth.collector.adapter.RouterOsProvisioningLifecycleTest
import com.duluin.ftth.contract.AdapterCertificationSubject
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningTarget
import java.time.Clock
import java.time.ZoneOffset

internal class RouterOsCertificationSubject : AdapterCertificationSubject {
    private val fixture = RouterOsProvisioningLifecycleTest.StatefulRouterOsFixture()
    private val adapter = RouterOsProvisioningAdapter(
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        allowInsecureHttpForTests = true,
        stateStore = InMemoryRouterOsProvisioningStateStore(),
    )
    private val target = fixture.target()
    private val wireTarget = ProvisioningTarget("router-1", "BRAS", "127.0.0.1", "HTTPS_REST")
    private var beforeHash: String? = null
    private var afterHash: String? = null

    override val profileId = "routeros-ccr2004-7.20.1-fixture"
    override val implementation = RouterOsProvisioningAdapter::class.qualifiedName.orEmpty()
    override val origin = ADAPTER_FIXTURE_ORIGIN

    override fun capabilityReport(): DeviceCapabilityReport = adapter.capabilityReport(target)

    override fun executePhase(phase: CertificationPhase) = when (phase) {
        CertificationPhase.CREATE -> {
            val preflight = adapter.execute(target, routerCommand(ProvisioningCommandPhase.PREFLIGHT, key = "preflight"))
            beforeHash = preflight.preflight?.preconditionHash
            val applied = adapter.execute(target, routerCommand(ProvisioningCommandPhase.APPLY, beforeHash, "create"))
            afterHash = applied.verification?.stateHash
            phaseResult(
                phase,
                preflight.success && applied.success && applied.apply?.changed == true,
                "preflight=${preflight.errorCode};apply=${applied.errorCode}",
            )
        }
        CertificationPhase.VERIFY -> {
            val verified = adapter.execute(target, routerCommand(ProvisioningCommandPhase.VERIFY, key = "verify"))
            afterHash = verified.verification?.stateHash
            phaseResult(phase, verified.success && verified.verification?.matchesExpected == true, "verify=${verified.errorCode}")
        }
        CertificationPhase.IDEMPOTENT_REPEAT -> {
            val writes = fixture.requests.count { !it.startsWith("GET ") }
            val repeated = adapter.execute(target, routerCommand(ProvisioningCommandPhase.APPLY, beforeHash, "repeat"))
            phaseResult(
                phase,
                repeated.success && repeated.apply?.changed == false && writes == fixture.requests.count { !it.startsWith("GET ") },
                "repeat=${repeated.errorCode}",
            )
        }
        CertificationPhase.ROLLBACK -> {
            val rollback = adapter.execute(target, routerCommand(ProvisioningCommandPhase.ROLLBACK, afterHash, "rollback"))
            phaseResult(phase, rollback.success && rollback.rollback?.success == true, "rollback=${rollback.errorCode}")
        }
        CertificationPhase.DELETE -> {
            val rollback = adapter.execute(target, routerCommand(ProvisioningCommandPhase.ROLLBACK, beforeHash, "delete"))
            phaseResult(phase, rollback.success && fixture.allRows().isEmpty(), "DELETE_VIA_IDEMPOTENT_ROLLBACK")
        }
        CertificationPhase.OBSERVATION_ONLY -> {
            val writes = fixture.requests.count { !it.startsWith("GET ") }
            val observed = adapter.execute(
                target,
                routerCommand(ProvisioningCommandPhase.PREFLIGHT, key = "observe").copy(observationOnly = true),
            )
            phaseResult(phase, observed.success && writes == fixture.requests.count { !it.startsWith("GET ") })
        }
    }

    override fun verifyUnsupportedOperations(): Map<String, String> {
        val unsupported = adapter.execute(
            target,
            routerCommand(ProvisioningCommandPhase.PREFLIGHT, key = "unsupported", tagging = "QINQ"),
        )
        return mapOf("QINQ" to (unsupported.errorCode ?: ProvisioningErrorCode.MANUAL_RECONCILIATION).name)
    }

    override fun close() = fixture.close()

    private fun routerCommand(
        phase: ProvisioningCommandPhase,
        expectedHash: String? = null,
        key: String,
        tagging: String = "SINGLE_TAG",
    ) = command(
        phase = phase,
        target = wireTarget,
        operation = "ENSURE_PPPOE_TERMINATION",
        expectedHash = expectedHash,
        key = "routeros-$key",
        payload = ProvisioningPayload(
            mapOf(
                "tenantId" to "tenant-17",
                "intentId" to "intent-17",
                "bridge" to "br-service",
                "vlanId" to VLAN.toString(),
                "tagging" to tagging,
                "trunkPorts" to "ether1",
                "accessPorts" to "ether2",
                "vlanInterface" to "svc-110",
                "vlanParent" to "br-service",
                "pppoeInterface" to "svc-110",
                "pppoeServiceName" to "ftth-110",
                "poolName" to "ftth-110",
                "poolRanges" to "100.64.110.2-100.64.110.254",
                "interfaceList" to "FTTH-CUSTOMER",
                "firewallChain" to "forward",
                "managementPathProven" to "true",
                "managementSourceId" to "0199386e-9718-7000-8000-000000000217",
                "managementSourceType" to "TOPOLOGY_OBSERVATION",
                "protectedInterfaces" to "mgmt",
                "protectedVlanIds" to "99",
            ),
        ),
    )
}
