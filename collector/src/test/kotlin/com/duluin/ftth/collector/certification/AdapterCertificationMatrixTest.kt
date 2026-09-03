package com.duluin.ftth.collector.certification

import com.duluin.ftth.collector.adapter.RouterOsProvisioningAdapter
import com.duluin.ftth.collector.adapter.hsgq.HsgqProvisioningAdapter
import com.duluin.ftth.collector.adapter.huawei.HuaweiProvisioningAdapter
import com.duluin.ftth.collector.adapter.iosxe.IosXeProvisioningAdapter
import com.duluin.ftth.collector.adapter.junos.JunosProvisioningAdapter
import com.duluin.ftth.collector.adapter.zte.ZteProvisioningAdapter
import com.duluin.ftth.contract.CertificationEvidenceOrigin
import com.duluin.ftth.contract.CertificationPhaseResult
import com.duluin.ftth.contract.CertificationVerdict
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.simulator.network.CertificationMatrixRunner
import com.duluin.ftth.simulator.network.CertificationMatrixWriter
import com.duluin.ftth.simulator.network.DeterministicNetworkSimulator
import com.duluin.ftth.simulator.network.SimulatorProfiles
import com.duluin.ftth.simulator.network.StandaloneSimulatorCertificationSubject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdapterCertificationMatrixTest {
    @Test
    fun `shared matrix executes six real adapters and standalone simulator`() {
        val subjects = listOf(
            RouterOsCertificationSubject(),
            IosXeCertificationSubject(),
            JunosCertificationSubject(),
            HsgqCertificationSubject(),
            HuaweiCertificationSubject(),
            ZteCertificationSubject(),
            StandaloneSimulatorCertificationSubject(SimulatorProfiles.simulator),
        )

        val reports = subjects.map(CertificationMatrixRunner()::certify)

        assertEquals(EXPECTED_IMPLEMENTATIONS, reports.map { it.implementation }.toSet())
        assertTrue(reports.all { it.phases.all(CertificationPhaseResult::passed) })
        assertEquals(EXPECTED_FINGERPRINTS, reports.associate { it.profileId to it.fingerprint })

        val byImplementation = reports.associateBy { it.implementation }
        val standalone = byImplementation.getValue(DeterministicNetworkSimulator::class.qualifiedName.orEmpty())
        val adapters = reports.filter { it !== standalone }
        assertEquals(CertificationEvidenceOrigin.SIMULATOR_FIXTURE, standalone.origin)
        assertEquals(CertificationVerdict.CERTIFIED_BY_TEST, standalone.verdict)
        assertTrue(assertNotNull(standalone.evidenceIdentity).matches(Regex("^[a-f0-9]{64}$")))
        assertTrue(adapters.all { it.origin == CertificationEvidenceOrigin.ADAPTER_FIXTURE })
        assertTrue(adapters.all { it.verdict == CertificationVerdict.PROVISIONAL && it.evidenceIdentity == null })
        assertTrue("REMOVE_TAGGED_VLAN" in byImplementation.getValue(IosXeProvisioningAdapter::class.qualifiedName.orEmpty()).operationClasses)
        assertFalse("REMOVE_TAGGED_VLAN" in byImplementation.getValue(JunosProvisioningAdapter::class.qualifiedName.orEmpty()).operationClasses)
        assertEquals("UNSUPPORTED_CAPABILITY", byImplementation.getValue(IosXeProvisioningAdapter::class.qualifiedName.orEmpty()).unsupportedOperations["ROLLBACK"])
        assertEquals("ROLLBACK_CONFLICT", byImplementation.getValue(JunosProvisioningAdapter::class.qualifiedName.orEmpty()).unsupportedOperations["ROLLBACK"])
        assertEquals("PRODUCTION_NOT_CERTIFIED", byImplementation.getValue(HuaweiProvisioningAdapter::class.qualifiedName.orEmpty()).unsupportedOperations["PRODUCTION_AUTO_APPLY"])
        assertEquals("PRODUCTION_NOT_CERTIFIED", byImplementation.getValue(ZteProvisioningAdapter::class.qualifiedName.orEmpty()).unsupportedOperations["PRODUCTION_AUTO_APPLY"])
        assertTrue(adapters.all {
            "CERTIFICATION_PROVISIONAL" in it.capabilities
        })

        System.getenv("TASK17_EVIDENCE_DIR")?.let { directory ->
            CertificationMatrixWriter.write(Path.of(directory).resolve("task-17-matrix.txt"), reports)
        }
    }

    private companion object {
        val EXPECTED_IMPLEMENTATIONS = setOf(
            RouterOsProvisioningAdapter::class.qualifiedName.orEmpty(),
            IosXeProvisioningAdapter::class.qualifiedName.orEmpty(),
            JunosProvisioningAdapter::class.qualifiedName.orEmpty(),
            HsgqProvisioningAdapter::class.qualifiedName.orEmpty(),
            HuaweiProvisioningAdapter::class.qualifiedName.orEmpty(),
            ZteProvisioningAdapter::class.qualifiedName.orEmpty(),
            DeterministicNetworkSimulator::class.qualifiedName.orEmpty(),
        )
        val EXPECTED_FINGERPRINTS = mapOf(
            "routeros-ccr2004-7.20.1-fixture" to DeviceFingerprint("MikroTik", "CCR2004", "7.20.1", "HTTPS_REST"),
            "iosxe-c9300-17.18.1-fixture" to DeviceFingerprint("Cisco IOS XE", "C9300-24T", "17.18.1", "NETCONF_SSH"),
            "junos-ex4300-21.4r3-s5.4-fixture" to DeviceFingerprint("JUNIPER", "EX4300-48P", "21.4R3-S5.4", "NETCONF_SSH"),
            "hsgq-e04i-v1.2.3-fixture" to DeviceFingerprint("HSGQ", "HSGQ-E04I", "V1.2.3-certified", "HTTPS_API"),
            "huawei-ma5800-r019-fixture" to DeviceFingerprint("HUAWEI", "SmartAX MA5800-X7", "MA5800V100R019C10", "SSH_CLI"),
            "zte-c320-v2.0.1p3-fixture" to DeviceFingerprint("ZTE", "ZXA10 C320", "V2.0.1P3", "SSH_CLI"),
            "ftth-network-simulator-v1" to DeviceFingerprint("FTTH", "NETWORK-SIMULATOR", "1.0.0", "IN_MEMORY"),
        )
    }
}
