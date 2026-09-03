package com.duluin.ftth.collector.adapter.iosxe

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IosXeProfileTest {
    @Test
    fun `Catalyst and ASR mappings are exact and structurally distinct`() {
        val catalyst = IosXeProfiles.resolve("C9300-24T", "17.18.1")
        val asr = IosXeProfiles.resolve("ASR1001-X", "17.18.1")
        val desired = IosXeDesiredConfiguration(110, setOf("GigabitEthernet1/0/48"), emptySet(), "FTTH-IN", false)

        assertNotNull(catalyst)
        assertNotNull(asr)
        assertTrue("Cisco-IOS-XE-vlan" in catalyst.requiredModules)
        assertFalse("Cisco-IOS-XE-vlan" in asr.requiredModules)
        assertTrue("<vlan-list" in catalyst.renderEdit(desired))
        assertTrue("<dot1Q" in asr.renderEdit(desired))
        assertNotEquals(catalyst.renderEdit(desired), asr.renderEdit(desired))
    }

    @Test
    fun `sensitive XML redaction removes credentials and payload bodies`() {
        val xml = "<rpc><username>admin</username><password>super-secret</password><config><native>private</native></config></rpc>"

        val redacted = IosXeXml.redact(xml)

        assertFalse("admin" in redacted)
        assertFalse("super-secret" in redacted)
        assertFalse("private" in redacted)
        assertTrue("[REDACTED]" in redacted)
    }
}
