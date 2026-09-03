package com.duluin.ftth.collector.certification

import com.duluin.ftth.collector.adapter.huawei.HuaweiAdapterException
import com.duluin.ftth.collector.adapter.huawei.HuaweiExecutionMode
import com.duluin.ftth.collector.adapter.huawei.HuaweiMa5800Fixture
import com.duluin.ftth.collector.adapter.huawei.HuaweiProvisioningAdapter
import com.duluin.ftth.collector.adapter.huawei.HuaweiTarget
import com.duluin.ftth.collector.adapter.huawei.servicePlan
import com.duluin.ftth.contract.AdapterCertificationSubject
import com.duluin.ftth.contract.CertificationPhase
import com.duluin.ftth.contract.DeviceCapabilityReport
import java.time.Clock
import java.time.ZoneOffset

internal class HuaweiCertificationSubject : AdapterCertificationSubject {
    private val fixture = HuaweiMa5800Fixture()
    private val adapter = HuaweiProvisioningAdapter(fixture, clock = Clock.fixed(NOW, ZoneOffset.UTC))
    private val target = HuaweiTarget("huawei-17", "192.0.2.19", "SmartAX MA5800-X7", "MA5800V100R019C10")
    private val plan = servicePlan()

    override val profileId = "huawei-ma5800-r019-fixture"
    override val implementation = HuaweiProvisioningAdapter::class.qualifiedName.orEmpty()
    override val origin = ADAPTER_FIXTURE_ORIGIN

    override fun capabilityReport(): DeviceCapabilityReport = adapter.capabilityReport(target).report

    override fun executePhase(phase: CertificationPhase) = when (phase) {
        CertificationPhase.CREATE -> {
            val result = adapter.apply(target, plan, HuaweiExecutionMode.SIMULATOR)
            phaseResult(phase, result.changed && result.observation.matches(plan))
        }
        CertificationPhase.VERIFY -> {
            val result = adapter.apply(target, plan, HuaweiExecutionMode.SIMULATOR)
            phaseResult(phase, !result.changed && result.observation.matches(plan))
        }
        CertificationPhase.IDEMPOTENT_REPEAT -> {
            val mutations = fixture.mutations.size
            val result = adapter.apply(target, plan, HuaweiExecutionMode.SIMULATOR)
            phaseResult(phase, !result.changed && mutations == fixture.mutations.size)
        }
        CertificationPhase.ROLLBACK -> {
            val result = adapter.compensate(target, plan, HuaweiExecutionMode.SIMULATOR)
            phaseResult(phase, result.changed && !result.observation.matches(plan))
        }
        CertificationPhase.DELETE -> {
            val recreated = adapter.apply(target, plan, HuaweiExecutionMode.SIMULATOR)
            val removed = adapter.compensate(target, plan, HuaweiExecutionMode.SIMULATOR)
            phaseResult(phase, recreated.changed && removed.changed && !removed.observation.matches(plan))
        }
        CertificationPhase.OBSERVATION_ONLY -> {
            val mutations = fixture.mutations.size
            adapter.apply(target, plan, HuaweiExecutionMode.DRY_RUN)
            phaseResult(phase, mutations == fixture.mutations.size)
        }
    }

    override fun verifyUnsupportedOperations(): Map<String, String> {
        val code = try {
            adapter.apply(target, plan, HuaweiExecutionMode.PRODUCTION_AUTO_APPLY)
            "UNEXPECTED_SUPPORT"
        } catch (failure: HuaweiAdapterException) {
            failure.code.name
        }
        return mapOf("PRODUCTION_AUTO_APPLY" to code)
    }
}
