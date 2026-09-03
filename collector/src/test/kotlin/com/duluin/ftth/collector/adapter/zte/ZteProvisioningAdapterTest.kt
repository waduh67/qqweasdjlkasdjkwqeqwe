package com.duluin.ftth.collector.adapter.zte

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZteProvisioningAdapterTest {
    @Test
    fun `versioned transcript applies saves reads back repeats idempotently and compensates`() {
        val fixture = ZteC320V201P3Fixture()
        val adapter = adapter(fixture)
        val target = target()
        val plan = servicePlan()

        val first = adapter.apply(target, plan, ZteExecutionMode.SIMULATOR)

        assertTrue(first.changed)
        assertTrue(first.state.matches(plan))
        val capability = adapter.capabilityReport(target)
        assertEquals(ZteCertification.PROVISIONAL, capability.certification)
        assertEquals("SSH_CLI", capability.report.fingerprint.transport)
        assertTrue("CERTIFICATION_PROVISIONAL" in capability.report.capabilities)
        assertEquals(5, first.resultState.managedResourceCount)
        assertEquals(
            listOf(
                "configure terminal",
                "vlan 110",
                "exit",
                "interface gei_1/3/1",
                "switchport vlan 110 tag",
                "exit",
                "interface gpon-onu_1/1/1:1",
                "tcont 1 profile UP-100M",
                "gemport 1 tcont 1",
                "service-port 1 vport 1 user-vlan 110 vlan 110",
                "exit",
                "end",
                "write",
            ),
            fixture.mutations.take(13),
        )

        val mutationCount = fixture.mutations.size
        val repeated = adapter.apply(target, plan, ZteExecutionMode.SIMULATOR)
        assertFalse(repeated.changed)
        assertEquals(mutationCount, fixture.mutations.size)

        val compensated = adapter.compensate(target, plan, ZteExecutionMode.SIMULATOR)
        assertTrue(compensated.changed)
        assertFalse(compensated.state.matches(plan))
        assertTrue(fixture.mutations.contains("no service-port 1"))
        assertTrue(fixture.mutations.contains("no gemport 1"))
        assertTrue(fixture.mutations.contains("no tcont 1"))
        assertTrue(fixture.mutations.contains("no switchport vlan 110"))
        assertTrue(fixture.mutations.contains("no vlan 110"))
    }

    @Test
    fun `dry run renders without mutation while production auto apply is denied`() {
        val fixture = ZteC320V201P3Fixture()
        val adapter = adapter(fixture)

        val dryRun = adapter.apply(target(), servicePlan(), ZteExecutionMode.DRY_RUN)
        assertTrue(dryRun.changed)
        assertTrue(dryRun.commands.contains("service-port 1 vport 1 user-vlan 110 vlan 110"))
        assertTrue(fixture.mutations.isEmpty())

        val callsBeforeProduction = fixture.commands.size
        val failure = assertFailsWith<ZteAdapterException> {
            adapter.apply(target(), servicePlan(), ZteExecutionMode.PRODUCTION)
        }
        assertEquals(ZteFailureCode.PRODUCTION_NOT_CERTIFIED, failure.code)
        assertEquals(callsBeforeProduction, fixture.commands.size)
    }

    @Test
    fun `unknown firmware stops after discovery before mutation`() {
        val fixture = ZteC320V201P3Fixture(firmware = "V2.1.0")

        val failure = assertFailsWith<ZteAdapterException> {
            adapter(fixture).apply(target(), servicePlan(), ZteExecutionMode.SIMULATOR)
        }

        assertEquals(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, failure.code)
        assertEquals(listOf("show version"), fixture.commands)
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun `unknown capability fails before mutation`() {
        val fixture = ZteC320V201P3Fixture()

        val failure = assertFailsWith<ZteAdapterException> {
            adapter(fixture).apply(
                target(),
                servicePlan(requiredCapability = "GPON_SERVICE_PORT_V2"),
                ZteExecutionMode.SIMULATOR,
            )
        }

        assertEquals(ZteFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertEquals(listOf("show version"), fixture.commands)
        assertTrue(fixture.mutations.isEmpty())
    }

    @Test
    fun `unknown prompt or command error stops before later mutations`() {
        listOf(
            "vlan 110" to "vlan 110\nOLT>",
            "vlan 110" to "vlan 110\n%Error: unsupported service-port capability\nZXAN(config-vlan110)#",
        ).forEach { (failingCommand, response) ->
            val fixture = ZteC320V201P3Fixture(overrides = mapOf(failingCommand to response))

            val failure = assertFailsWith<ZteAdapterException> {
                adapter(fixture).apply(target(), servicePlan(), ZteExecutionMode.SIMULATOR)
            }

            assertEquals(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, failure.code)
            assertFalse(fixture.commands.contains("interface gei_1/3/1"))
            assertFalse(fixture.commands.contains("service-port 1 vport 1 user-vlan 110 vlan 110"))
        }
    }

    @Test
    fun `ambiguous or destructive readback prompt fails before mutation`() {
        listOf(
            "show vlan 110\nVLAN ID : 110\n--More--" to ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
            "show vlan 110\nConfirm to continue? [yes/no]:" to ZteFailureCode.DESTRUCTIVE_PROMPT,
        ).forEach { (response, code) ->
            val fixture = ZteC320V201P3Fixture(overrides = mapOf("show vlan 110" to response))

            val failure = assertFailsWith<ZteAdapterException> {
                adapter(fixture).apply(target(), servicePlan(), ZteExecutionMode.SIMULATOR)
            }

            assertEquals(code, failure.code)
            assertTrue(fixture.mutations.isEmpty())
            assertFalse(fixture.commands.contains("show vlan port 110"))
        }
    }

    @Test
    fun `ambiguous save response is never accepted as persisted`() {
        val fixture = ZteC320V201P3Fixture(overrides = mapOf("write" to "write\nSaving configuration\nZXAN#"))

        val failure = assertFailsWith<ZteAdapterException> {
            adapter(fixture).apply(target(), servicePlan(), ZteExecutionMode.SIMULATOR)
        }

        assertEquals(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, failure.code)
        assertEquals(fixture.commands.lastIndex, fixture.commands.indexOf("write"))
    }

    private fun adapter(transport: ZteCliTransport) = ZteProvisioningAdapter(
        transport = transport,
        clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC),
    )

    private fun target() = ZteTarget(
        deviceId = "olt-zte-1",
        managementAddress = "192.0.2.10",
        expectedFamily = "ZXA10 C320",
        expectedFirmware = "V2.0.1P3",
    )
}

private class ZteC320V201P3Fixture(
    private val firmware: String = "V2.0.1P3",
    private val overrides: Map<String, String> = emptyMap(),
) : ZteCliTransport {
    val commands = mutableListOf<String>()
    val mutations = mutableListOf<String>()
    private var vlan = false
    private var uplink = false
    private var tcont = false
    private var gem = false
    private var service = false

    override fun execute(command: String): String {
        commands += command
        overrides[command]?.let { return it }
        if (command !in READ_COMMANDS) mutations += command
        when (command) {
            "vlan 110" -> vlan = true
            "switchport vlan 110 tag" -> uplink = true
            "tcont 1 profile UP-100M" -> tcont = true
            "gemport 1 tcont 1" -> gem = true
            "service-port 1 vport 1 user-vlan 110 vlan 110" -> service = true
            "no service-port 1" -> service = false
            "no gemport 1" -> gem = false
            "no tcont 1" -> tcont = false
            "no switchport vlan 110" -> uplink = false
            "no vlan 110" -> vlan = false
        }
        val body = when (command) {
            "show version" -> "Product Name : ZXA10 C320\nSoftware Version : $firmware"
            "show vlan 110" -> if (vlan) "VLAN ID : 110\nName : VLAN0110" else ZteTranscriptParser.NO_MATCH
            "show vlan port 110" -> if (uplink) "gei_1/3/1 tagged" else ZteTranscriptParser.NO_MATCH
            "show running-config interface gpon-onu_1/1/1:1" -> buildList {
                add("interface gpon-onu_1/1/1:1")
                if (tcont) add(" tcont 1 profile UP-100M")
                if (gem) add(" gemport 1 tcont 1")
                if (service) add(" service-port 1 vport 1 user-vlan 110 vlan 110")
            }.joinToString("\n")
            "write" -> "Building configuration......[OK]"
            else -> ""
        }
        return "$command\n$body\n${prompt(command)}"
    }

    private fun prompt(command: String): String = when {
        command == "configure terminal" -> "ZXAN(config)#"
        command == "end" || command == "write" || command.startsWith("show ") -> "ZXAN#"
        command.startsWith("interface ") || command.startsWith("switchport ") || command.startsWith("no switchport") ||
            command.startsWith("tcont ") || command.startsWith("gemport ") || command.startsWith("service-port ") ||
            command.startsWith("no tcont") || command.startsWith("no gemport") || command.startsWith("no service-port") -> "ZXAN(config-if)#"
        command == "vlan 110" -> "ZXAN(config-vlan110)#"
        else -> "ZXAN(config)#"
    }

    private companion object {
        val READ_COMMANDS = setOf(
            "show version",
            "show vlan 110",
            "show vlan port 110",
            "show running-config interface gpon-onu_1/1/1:1",
        )
    }
}
