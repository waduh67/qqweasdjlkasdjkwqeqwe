package com.duluin.ftth.collector.adapter.zte

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ZteTranscriptTest {
    @Test
    fun `versioned discovery transcript resolves exact profile`() {
        val body = ZteTranscriptParser.commandBody("show version", ZteV201P3Transcripts.discoveryC320)

        assertEquals(ZteProfileKey("ZXA10 C320", "V2.0.1P3"), ZteTranscriptParser.profileKey(body))
    }

    @Test
    fun `known exec config and interface prompts are accepted`() {
        assertEquals("Building configuration......[OK]", ZteTranscriptParser.commandBody("write", "write\nBuilding configuration......[OK]\nZXAN#"))
        assertEquals("", ZteTranscriptParser.commandBody("vlan 110", "vlan 110\nZXAN(config-vlan110)#"))
        assertEquals("", ZteTranscriptParser.commandBody("gemport 1 tcont 1", "gemport 1 tcont 1\nZXAN(config-if)#"))
    }

    @Test
    fun `unknown and destructive prompts fail with scrubbed detail`() {
        val unknown = assertFailsWith<ZteAdapterException> {
            ZteTranscriptParser.commandBody("show version", "show version\npassword SuperSecret\nOLT>")
        }
        val destructive = assertFailsWith<ZteAdapterException> {
            ZteTranscriptParser.commandBody("no vlan 110", "no vlan 110\nConfirm to delete? [yes/no]:")
        }

        assertEquals(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, unknown.code)
        assertFalse(unknown.message.orEmpty().contains("SuperSecret"))
        assertEquals(ZteFailureCode.DESTRUCTIVE_PROMPT, destructive.code)
    }

    @Test
    fun `vendor error output is rejected without leaking transcript secrets`() {
        val failure = assertFailsWith<ZteAdapterException> {
            ZteTranscriptParser.commandBody(
                "service-port 1 vport 1 user-vlan 110 vlan 110",
                "service-port 1 vport 1 user-vlan 110 vlan 110\n%Error: password DontLeak is invalid\nZXAN(config-if)#",
            )
        }

        assertEquals(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, failure.code)
        assertFalse(failure.message.orEmpty().contains("DontLeak"))
    }

    @Test
    fun `pagination marker is rejected instead of being answered`() {
        val failure = assertFailsWith<ZteAdapterException> {
            ZteTranscriptParser.commandBody("show vlan 110", "show vlan 110\nVLAN ID : 110\n--More--")
        }

        assertEquals(ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE, failure.code)
    }
}

internal object ZteV201P3Transcripts {
    val discoveryC320 = """
        show version
        ZXA10 C320 Software, Version V2.0.1P3
        Product Name : ZXA10 C320
        Software Version : V2.0.1P3
        ZXAN#
    """.trimIndent()
}
