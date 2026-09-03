package com.duluin.ftth.collector.adapter.huawei

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HuaweiTranscriptTest {
    @Test
    fun `golden identity and paginated readback normalize exactly`() {
        val identity = HuaweiTranscriptParser.commandBody("display version", HuaweiGoldenTranscripts.identity)
        val readback = HuaweiTranscriptParser.commandBody("display service-port 1", HuaweiGoldenTranscripts.paginatedServicePort)

        assertEquals(HuaweiProfileKey("SmartAX MA5800-X7", "MA5800V100R019C10"), HuaweiTranscriptParser.profileKey(identity))
        assertFalse(readback.contains("More"))
        assertEquals(1, HuaweiStateParser.parseServicePort(readback)?.servicePortId)
    }

    @Test
    fun `known prompts normalize while errors and unsafe prompts fail scrubbed`() {
        assertEquals("Save the configuration successfully.", HuaweiTranscriptParser.commandBody("save", "save\r\nSave the configuration successfully.\r\n<HUAWEI>"))
        assertEquals("", HuaweiTranscriptParser.commandBody("config", "config\n[HUAWEI]"))
        assertEquals("", HuaweiTranscriptParser.commandBody("commit", "commit\n[HUAWEI-gpon-lineprofile-10]"))

        val unsafe = assertFailsWith<HuaweiAdapterException> {
            HuaweiTranscriptParser.commandBody("save", "save\nAre you sure to continue? (y/n)[n]:")
        }
        val error = assertFailsWith<HuaweiAdapterException> {
            HuaweiTranscriptParser.commandBody(
                "service-port 1",
                "service-port 1\nFailure: password SuperSecret is invalid\n[HUAWEI]",
            )
        }
        assertEquals(HuaweiFailureCode.UNSAFE_PROMPT, unsafe.code)
        assertEquals(HuaweiFailureCode.COMMAND_ERROR, error.code)
        assertFalse(error.message.orEmpty().contains("SuperSecret"))
        assertFalse(HuaweiTranscriptParser.scrub("community=DontLeak token BearerValue").contains("DontLeak"))
        assertFalse(HuaweiTranscriptParser.scrub("community=DontLeak token BearerValue").contains("BearerValue"))
    }
}

internal object HuaweiGoldenTranscripts {
    val identity = """
        display version
        PRODUCT : SmartAX MA5800-X7
        VERSION : MA5800V100R019C10
        <HUAWEI>
    """.trimIndent()

    val paginatedServicePort = """
        display service-port 1
        Service-port index : 1
        VLAN ID : 110
        F/S/P : 0/1/1
        ---- More ( Press 'Q' to break ) ----
        ONT ID : 1
        GEM port index : 1
        User VLAN ID : 110
        <HUAWEI>
    """.trimIndent()
}
