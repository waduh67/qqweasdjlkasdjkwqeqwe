package com.duluin.ftth.collector.adapter.huawei

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuaweiProvisioningAdapterTest {
    @Test
    fun `simulator applies saves reads back replays idempotently and compensates`() {
        val fixture = HuaweiMa5800Fixture()
        val adapter = adapter(fixture)
        val target = target()
        val plan = servicePlan()

        val applied = adapter.apply(target, plan, HuaweiExecutionMode.SIMULATOR)

        assertTrue(applied.changed)
        assertTrue(applied.observation.matches(plan))
        assertEquals("save", fixture.mutations.last())
        assertTrue(fixture.commands.takeLast(4).containsAll(profileReadCommands()))
        val mutationCount = fixture.mutations.size

        val replay = adapter.apply(target, plan, HuaweiExecutionMode.SIMULATOR)
        assertFalse(replay.changed)
        assertEquals(mutationCount, fixture.mutations.size)

        val compensated = adapter.compensate(target, plan, HuaweiExecutionMode.SIMULATOR)
        assertTrue(compensated.changed)
        assertFalse(compensated.observation.matches(plan))
        assertTrue(fixture.mutations.contains("undo service-port 1"))
        assertTrue(fixture.mutations.contains("undo gem mapping 1 0"))
        assertTrue(fixture.mutations.contains("undo port vlan 110 0/19 0"))
        assertTrue(fixture.mutations.contains("undo vlan 110"))
    }

    @Test
    fun `dry run remains available and every profile denies production before IO`() {
        val fixture = HuaweiMa5800Fixture()
        val adapter = adapter(fixture)

        val dryRun = adapter.apply(target(), servicePlan(), HuaweiExecutionMode.DRY_RUN)
        assertTrue(dryRun.changed)
        assertTrue(dryRun.commands.contains("service-port 1 vlan 110 gpon 0/1/1 ont 1 gemport 1 multi-service user-vlan 110 tag-transform transparent"))
        assertTrue(fixture.mutations.isEmpty())

        val callsBeforeProduction = fixture.commands.size
        val failure = assertFailsWith<HuaweiAdapterException> {
            adapter.apply(target(), servicePlan(), HuaweiExecutionMode.PRODUCTION_AUTO_APPLY)
        }
        assertEquals(HuaweiFailureCode.PRODUCTION_NOT_CERTIFIED, failure.code)
        assertEquals(callsBeforeProduction, fixture.commands.size)
    }

    @Test
    fun `unknown profile capability ambiguity and unsafe prompt stop before mutation`() {
        val scenarios = listOf(
            HuaweiMa5800Fixture(firmware = "MA5800V100R020C00") to HuaweiFailureCode.UNKNOWN_PROFILE,
            HuaweiMa5800Fixture(ambiguousLineProfile = true) to HuaweiFailureCode.AMBIGUOUS_READBACK,
            HuaweiMa5800Fixture(overrides = mapOf("display vlan 110" to "display vlan 110\nContinue? [Y/N]:")) to HuaweiFailureCode.UNSAFE_PROMPT,
        )
        scenarios.forEach { (fixture, expectedCode) ->
            val failure = assertFailsWith<HuaweiAdapterException> {
                adapter(fixture).apply(target(), servicePlan(), HuaweiExecutionMode.SIMULATOR)
            }
            assertEquals(expectedCode, failure.code)
            assertTrue(fixture.mutations.isEmpty())
        }

        val unsupportedFixture = HuaweiMa5800Fixture()
        val unsupported = assertFailsWith<HuaweiAdapterException> {
            adapter(unsupportedFixture).apply(
                target(),
                servicePlan(requiredCapability = "GPON_SERVICE_PORT_V2"),
                HuaweiExecutionMode.SIMULATOR,
            )
        }
        assertEquals(HuaweiFailureCode.UNSUPPORTED_CAPABILITY, unsupported.code)
        assertTrue(unsupportedFixture.mutations.isEmpty())
    }

    @Test
    fun `unconfirmed save fails instead of accepting volatile state`() {
        val fixture = HuaweiMa5800Fixture(
            overrides = mapOf("save" to "save\nConfiguration remains in memory only.\n<HUAWEI>"),
        )

        val failure = assertFailsWith<HuaweiAdapterException> {
            adapter(fixture).apply(target(), servicePlan(), HuaweiExecutionMode.SIMULATOR)
        }

        assertEquals(HuaweiFailureCode.SAVE_FAILED, failure.code)
        assertEquals(1, fixture.commands.count { it == "display service-port 1" })
    }

    @Test
    fun `capability report is exact provisional and secret free`() {
        val report = adapter(HuaweiMa5800Fixture()).capabilityReport(target())

        assertEquals(HuaweiCertification.PROVISIONAL, report.certification)
        assertEquals("HUAWEI", report.report.fingerprint.vendor)
        assertEquals("SmartAX MA5800-X7", report.report.fingerprint.model)
        assertEquals("MA5800V100R019C10", report.report.fingerprint.firmware)
        assertEquals("SSH_CLI", report.report.fingerprint.transport)
        assertTrue("CERTIFICATION_PROVISIONAL" in report.report.capabilities)
    }

    private fun adapter(transport: HuaweiCliTransport) = HuaweiProvisioningAdapter(
        transport = transport,
        clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC),
    )

    private fun target() = HuaweiTarget(
        deviceId = "olt-huawei-1",
        managementAddress = "192.0.2.20",
        expectedFamily = "SmartAX MA5800-X7",
        expectedFirmware = "MA5800V100R019C10",
    )
}

private fun profileReadCommands() = listOf(
    "display vlan 110",
    "display port vlan 110",
    "display ont-lineprofile gpon profile-id 10",
    "display service-port 1",
)

internal class HuaweiMa5800Fixture(
    private val firmware: String = "MA5800V100R019C10",
    private val ambiguousLineProfile: Boolean = false,
    private val overrides: Map<String, String> = emptyMap(),
) : HuaweiCliTransport {
    val commands = mutableListOf<String>()
    val mutations = mutableListOf<String>()
    private var vlan = false
    private var uplink = false
    private var tcont = false
    private var gem = false
    private var mapping = false
    private var service = false

    override fun execute(command: String): String {
        commands += command
        overrides[command]?.let { return it }
        if (command !in READ_COMMANDS) mutations += command
        when (command) {
            "vlan 110 smart" -> vlan = true
            "port vlan 110 0/19 0" -> uplink = true
            "tcont 1 dba-profile-id 20" -> tcont = true
            "gem add 1 eth tcont 1" -> gem = true
            "gem mapping 1 0 vlan 110" -> mapping = true
            "service-port 1 vlan 110 gpon 0/1/1 ont 1 gemport 1 multi-service user-vlan 110 tag-transform transparent" -> service = true
            "undo service-port 1" -> service = false
            "undo gem mapping 1 0" -> mapping = false
            "undo gem add 1" -> gem = false
            "undo tcont 1" -> tcont = false
            "undo port vlan 110 0/19 0" -> uplink = false
            "undo vlan 110" -> vlan = false
        }
        val body = when (command) {
            "display version" -> "PRODUCT : SmartAX MA5800-X7\nVERSION : $firmware"
            "display vlan 110" -> if (vlan) "VLAN ID : 110" else HuaweiTranscriptParser.NO_MATCH
            "display port vlan 110" -> if (uplink) "0/19/0 tagged" else HuaweiTranscriptParser.NO_MATCH
            "display ont-lineprofile gpon profile-id 10" -> buildList {
                add("Line profile ID : 10")
                if (tcont) add("T-CONT 1 DBA Profile-ID 20")
                if (gem) add("GEM 1 T-CONT 1")
                if (mapping) add("GEM Mapping 1 0 VLAN 110")
                if (ambiguousLineProfile) add("GEM 1 T-CONT 2")
            }.joinToString("\n")
            "display service-port 1" -> if (service) {
                "Service-port index : 1\nVLAN ID : 110\nF/S/P : 0/1/1\nONT ID : 1\nGEM port index : 1\nUser VLAN ID : 110"
            } else {
                HuaweiTranscriptParser.NO_MATCH
            }
            "save" -> "Save the configuration successfully."
            else -> ""
        }
        return "$command\n$body\n${prompt(command)}"
    }

    private fun prompt(command: String): String = when {
        command.startsWith("display ") || command == "save" -> "<HUAWEI>"
        command == "ont-lineprofile gpon profile-id 10" || command.startsWith("tcont ") ||
            command.startsWith("gem ") || command.startsWith("undo gem") || command.startsWith("undo tcont") ||
            command == "commit" -> "[HUAWEI-gpon-lineprofile-10]"
        else -> "[HUAWEI]"
    }

    private companion object {
        val READ_COMMANDS = profileReadCommands().toSet() + "display version"
    }
}
