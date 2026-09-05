package com.duluin.ftth.collector.certification

import com.duluin.ftth.collector.adapter.zte.ZteAdapterException
import com.duluin.ftth.collector.adapter.zte.ZteC320V201P3Fixture
import com.duluin.ftth.collector.adapter.zte.ZteExecutionMode
import com.duluin.ftth.collector.adapter.zte.ZteProvisioningAdapter
import com.duluin.ftth.collector.adapter.zte.ZteTarget
import com.duluin.ftth.collector.adapter.zte.servicePlan
import com.duluin.ftth.contract.AdapterCertificationSubject
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.DeviceCapabilityReport
import java.time.Clock
import java.time.ZoneOffset

internal class ZteCertificationSubject : AdapterCertificationSubject {
    private val fixture = ZteC320V201P3Fixture()
    private val adapter = ZteProvisioningAdapter(fixture, clock = Clock.fixed(NOW, ZoneOffset.UTC))
    private val target = ZteTarget("zte-17", "192.0.2.20", "ZXA10 C320", "V2.0.1P3")
    private val plan = servicePlan()

    override val profileId = "zte-c320-v2.0.1p3-fixture"
    override val implementation = ZteProvisioningAdapter::class.qualifiedName.orEmpty()
    override val origin = ADAPTER_FIXTURE_ORIGIN

    override fun capabilityReport(): DeviceCapabilityReport = adapter.capabilityReport(target).report

    override fun executePhase(phase: CertificationPhase) = when (phase) {
        CertificationPhase.CREATE -> {
            val result = adapter.apply(target, plan, ZteExecutionMode.SIMULATOR)
            phaseResult(phase, result.changed && result.state.matches(plan))
        }
        CertificationPhase.VERIFY -> {
            val result = adapter.apply(target, plan, ZteExecutionMode.SIMULATOR)
            phaseResult(phase, !result.changed && result.state.matches(plan))
        }
        CertificationPhase.IDEMPOTENT_REPEAT -> {
            val mutations = fixture.mutations.size
            val result = adapter.apply(target, plan, ZteExecutionMode.SIMULATOR)
            phaseResult(phase, !result.changed && mutations == fixture.mutations.size)
        }
        CertificationPhase.ROLLBACK -> {
            val result = adapter.compensate(target, plan, ZteExecutionMode.SIMULATOR)
            phaseResult(phase, result.changed && !result.state.matches(plan))
        }
        CertificationPhase.DELETE -> {
            val recreated = adapter.apply(target, plan, ZteExecutionMode.SIMULATOR)
            val removed = adapter.compensate(target, plan, ZteExecutionMode.SIMULATOR)
            phaseResult(phase, recreated.changed && removed.changed && !removed.state.matches(plan))
        }
        CertificationPhase.OBSERVATION_ONLY -> {
            val mutations = fixture.mutations.size
            adapter.apply(target, plan, ZteExecutionMode.DRY_RUN)
            phaseResult(phase, mutations == fixture.mutations.size)
        }
    }

    override fun verifyUnsupportedOperations(): Map<String, String> {
        val code = try {
            adapter.apply(target, plan, ZteExecutionMode.PRODUCTION)
            "UNEXPECTED_SUPPORT"
        } catch (failure: ZteAdapterException) {
            failure.code.name
        }
        return mapOf("PRODUCTION_AUTO_APPLY" to code)
    }
}
