package com.duluin.ftth.simulator.network

import com.duluin.ftth.collector.adapter.huawei.HuaweiCertification
import com.duluin.ftth.collector.adapter.huawei.HuaweiProfileKey
import com.duluin.ftth.collector.adapter.huawei.HuaweiProfileRegistry
import com.duluin.ftth.collector.adapter.iosxe.IosXeProfiles
import com.duluin.ftth.collector.adapter.junos.JunosCapabilityProfiles
import com.duluin.ftth.collector.adapter.junos.JunosDeviceIdentity
import com.duluin.ftth.collector.adapter.zte.ZteCertification
import com.duluin.ftth.collector.adapter.zte.ZteProfileKey
import com.duluin.ftth.collector.adapter.zte.ZteProfileRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulatorProfilesTest {
    @Test
    fun `profiles declare exact capabilities instead of inheriting a vendor fallback`() {
        assertEquals(7, SimulatorProfiles.all.size)
        assertTrue(SimulatorProfiles.iosXe.capabilities.contains(SimulatorCapability.CONFIRMED_COMMIT))
        assertTrue(SimulatorProfiles.junos.capabilities.contains(SimulatorCapability.CANDIDATE_CONFIG))
        assertTrue(SimulatorProfiles.routerOs.capabilities.contains(SimulatorCapability.REST_RESOURCE_IDS))
        assertTrue(SimulatorProfiles.huawei.capabilities.contains(SimulatorCapability.STRICT_PROMPTS))
        assertTrue(SimulatorProfiles.zte.capabilities.contains(SimulatorCapability.PERSISTENCE_RECONNECT))
        assertFalse(SimulatorProfiles.hsgq.capabilities.contains(SimulatorCapability.CONFIRMED_COMMIT))
        assertEquals(null, SimulatorProfiles.find("HUAWEI", "SmartAX MA5800-X7", "UNKNOWN", "SSH_CLI"))
    }

    @Test
    fun `matrix profiles stay aligned with exact collector fixtures`() {
        val iosXe = IosXeProfiles.resolve("C9300-24T", "17.18.1")
        val junos = JunosCapabilityProfiles.find(JunosDeviceIdentity("EX4300-48P", "21.4R3-S5.4"))
        val huawei = HuaweiProfileRegistry.provisional().require(HuaweiProfileKey("SmartAX MA5800-X7", "MA5800V100R019C10"))
        val zte = ZteProfileRegistry.provisional().require(ZteProfileKey("ZXA10 C320", "V2.0.1P3"))

        assertEquals(iosXe?.supportedOperations, SimulatorProfiles.iosXe.supportedOperations)
        assertEquals(junos?.operations?.map { it.operationClass }?.toSet(), SimulatorProfiles.junos.supportedOperations)
        assertEquals(HuaweiCertification.PROVISIONAL, huawei.certification)
        assertEquals(ZteCertification.PROVISIONAL, zte.certification)
    }
}
