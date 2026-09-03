package com.duluin.ftth.collector.adapter.zte

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZteProfileTest {
    @Test
    fun `exact C300 and C320 firmware profiles render their own commands`() {
        val registry = ZteProfileRegistry.provisional()
        val plan = servicePlan()

        val c300 = registry.require(ZteProfileKey("ZXA10 C300", "V2.0.1P3"))
        val c320 = registry.require(ZteProfileKey("ZXA10 C320", "V2.0.1P3"))

        assertEquals("interface gei_1/3/1", c300.applyCommands(plan, ZteMutationSet.all()).first { it.startsWith("interface gei_") })
        assertEquals("interface gpon-onu_1/1/1:1", c320.applyCommands(plan, ZteMutationSet.all()).first { it.startsWith("interface gpon-onu_") })
        assertEquals(ZteCertification.PROVISIONAL, c300.certification)
        assertEquals(ZteCertification.PROVISIONAL, c320.certification)
    }

    @Test
    fun `unknown exact firmware is rejected`() {
        val failure = assertFailsWith<ZteAdapterException> {
            ZteProfileRegistry.provisional().require(ZteProfileKey("ZXA10 C320", "V2.1.0"))
        }

        assertEquals(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, failure.code)
    }

    @Test
    fun `unknown slot port and onu notation is rejected instead of inferred`() {
        listOf("1/3/1", "gei-1/3/1", "gei_01/3/1").forEach { notation ->
            assertEquals(
                ZteFailureCode.UNKNOWN_NOTATION,
                assertFailsWith<ZteAdapterException> { ZteUplinkPort.parse(notation) }.code,
            )
        }
        listOf("1/1/1:1", "gpon-onu_1/1/1", "gpon-onu_01/1/1:1").forEach { notation ->
            assertEquals(
                ZteFailureCode.UNKNOWN_NOTATION,
                assertFailsWith<ZteAdapterException> { ZteOnuPort.parse(notation) }.code,
            )
        }
    }
}

internal fun servicePlan(
    operationKey: String = "plan-11:step-1",
    requiredCapability: String = "GPON_SERVICE_PORT",
) = ZteServicePlan.create(
    operationKey = operationKey,
    vlanId = 110,
    uplinkNotation = "gei_1/3/1",
    onuNotation = "gpon-onu_1/1/1:1",
    tcontId = 1,
    tcontProfile = "UP-100M",
    gemPortId = 1,
    servicePortId = 1,
    userVlanId = 110,
    requiredCapability = requiredCapability,
)
