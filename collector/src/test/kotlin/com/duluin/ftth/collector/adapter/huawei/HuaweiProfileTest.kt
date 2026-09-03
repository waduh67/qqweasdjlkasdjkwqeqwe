package com.duluin.ftth.collector.adapter.huawei

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HuaweiProfileTest {
    @Test
    fun `exact family and firmware profile renders documented commands`() {
        val profile = HuaweiProfileRegistry.provisional().require(
            HuaweiProfileKey("SmartAX MA5800-X7", "MA5800V100R019C10"),
        )

        assertEquals(
            listOf(
                "config",
                "vlan 110 smart",
                "port vlan 110 0/19 0",
                "ont-lineprofile gpon profile-id 10",
                "tcont 1 dba-profile-id 20",
                "gem add 1 eth tcont 1",
                "gem mapping 1 0 vlan 110",
                "commit",
                "quit",
                "service-port 1 vlan 110 gpon 0/1/1 ont 1 gemport 1 multi-service user-vlan 110 tag-transform transparent",
                "save",
            ),
            profile.applyCommands(servicePlan(), HuaweiMutationSet.all()),
        )
        assertEquals(
            listOf(
                "config",
                "undo service-port 1",
                "ont-lineprofile gpon profile-id 10",
                "undo gem mapping 1 0",
                "undo gem add 1",
                "undo tcont 1",
                "commit",
                "quit",
                "undo port vlan 110 0/19 0",
                "undo vlan 110",
                "save",
            ),
            profile.inverseCommands(servicePlan(), HuaweiMutationSet.all()),
        )
        assertEquals(HuaweiCertification.PROVISIONAL, profile.certification)
    }

    @Test
    fun `unknown family firmware and ambiguous indices fail closed`() {
        val registry = HuaweiProfileRegistry.provisional()
        listOf(
            HuaweiProfileKey("SmartAX MA5800-X17", "MA5800V100R019C10"),
            HuaweiProfileKey("SmartAX MA5800-X7", "MA5800V100R020C00"),
        ).forEach { key ->
            assertEquals(
                HuaweiFailureCode.UNKNOWN_PROFILE,
                assertFailsWith<HuaweiAdapterException> { registry.require(key) }.code,
            )
        }
        listOf("0/1", "0/01/1", "slot/1/1", "0/1/1,0/1/2").forEach { notation ->
            assertEquals(
                HuaweiFailureCode.AMBIGUOUS_GPON_INDEX,
                assertFailsWith<HuaweiAdapterException> { HuaweiGponPort.parse(notation) }.code,
            )
        }
    }

    @Test
    fun `release one rejects service and user vlan translation`() {
        assertEquals(
            HuaweiFailureCode.UNSUPPORTED_CAPABILITY,
            assertFailsWith<HuaweiAdapterException> {
                HuaweiServicePlan.create(
                    operationKey = "translation",
                    vlanId = 110,
                    uplinkNotation = "0/19/0",
                    gponNotation = "0/1/1",
                    ontId = 1,
                    lineProfileId = 10,
                    tcontId = 1,
                    dbaProfileId = 20,
                    gemPortId = 1,
                    servicePortId = 1,
                    userVlanId = 120,
                )
            }.code,
        )
    }
}

internal fun servicePlan(
    operationKey: String = "plan-10:step-1",
    requiredCapability: String = "GPON_SERVICE_PORT",
) = HuaweiServicePlan.create(
    operationKey = operationKey,
    vlanId = 110,
    uplinkNotation = "0/19/0",
    gponNotation = "0/1/1",
    ontId = 1,
    lineProfileId = 10,
    tcontId = 1,
    dbaProfileId = 20,
    gemPortId = 1,
    servicePortId = 1,
    userVlanId = 110,
    requiredCapability = requiredCapability,
)
