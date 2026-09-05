package com.duluin.ftth.collector

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.OltManagementTransport
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.collector.adapter.iosxe.IosXeProvisioningAdapter
import com.duluin.ftth.collector.adapter.junos.JunosProvisioningAdapter
import com.duluin.ftth.collector.adapter.huawei.HuaweiMa5800Fixture
import com.duluin.ftth.collector.adapter.zte.ZteC320V201P3Fixture

class RuntimeProvisioningAdapterFactoryTest {
    @Test
    fun `real mode exposes all provisional runtime vendors`() {
        val registries = RuntimeProvisioningAdapterFactory.create(false, createTempDirectory())

        assertEquals(setOf("MIKROTIK", "CISCO", "JUNIPER"), registries.nas.supportedVendors)
        assertEquals(setOf("HSGQ", "HUAWEI", "ZTE"), registries.olt.supportedVendors)
        assertTrue(registries.nas.forVendor("CISCO") is IosXeProvisioningAdapter)
        assertTrue(registries.nas.forVendor("JUNIPER") is JunosProvisioningAdapter)
        assertTrue(registries.olt.forVendor("HUAWEI") is HuaweiRuntimeOltProvisioningAdapter)
        assertTrue(registries.olt.forVendor("ZTE") is ZteRuntimeOltProvisioningAdapter)
    }

    @Test
    fun `real Huawei and ZTE wrappers execute concrete capability discovery`() {
        val huawei = HuaweiMa5800Fixture()
        val zte = ZteC320V201P3Fixture()
        val registries = RuntimeProvisioningAdapterFactory.create(
            false,
            createTempDirectory(),
            RuntimeProvisioningTransports(huaweiTransport = { huawei }, zteTransport = { zte }),
        )

        assertEquals("SmartAX MA5800-X7", requireNotNull(registries.olt.forVendor("HUAWEI")).capabilityReport(olt("HUAWEI", "SmartAX MA5800-X7", "MA5800V100R019C10")).fingerprint.model)
        assertEquals("ZXA10 C320", requireNotNull(registries.olt.forVendor("ZTE")).capabilityReport(olt("ZTE", "ZXA10 C320", "V2.0.1P3")).fingerprint.model)
    }

    @Test
    fun `explicit simulator mode exposes only simulator scoped vendor adapters`() {
        val mode = CollectorRuntimeMode.resolve("development", simulatorRequested = true)
        val registries = RuntimeProvisioningAdapterFactory.create(mode.simulatorEnabled, createTempDirectory())

        assertEquals(setOf("MIKROTIK", "CISCO", "JUNIPER"), registries.nas.supportedVendors)
        assertEquals(setOf("HSGQ", "HUAWEI", "ZTE"), registries.olt.supportedVendors)
        assertTrue(requireNotNull(registries.nas.forVendor("CISCO")).capabilityReport(target()).operationClasses.isNotEmpty())
    }

    private fun target() = NasTarget("nas-1", "Simulator", "CISCO", "127.0.0.1", "SIMULATOR", emptyList())
    private fun olt(vendor: String, model: String, firmware: String) = OltTarget(
        "olt-1", "OLT-1", vendor, "127.0.0.1", snmpCommunity = null, model = model, firmware = firmware,
        managementTransport = OltManagementTransport.SSH, managementPort = 22, managementCredentialRef = "fixture",
    )
}
