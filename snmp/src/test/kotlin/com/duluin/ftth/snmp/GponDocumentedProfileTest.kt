package com.duluin.ftth.snmp

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuPathProvenance
import org.snmp4j.smi.OctetString
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GponDocumentedProfileTest {
    private val target = OltTarget("fixture", "OLT-DOC", "HUAWEI", "127.0.0.1", snmpCommunity = "unused")
    private val prefix = "1.3.6.1.4.1.2011.6.128.1.1.2"
    private val serial = "$prefix.43.1.3"
    private val status = "$prefix.46.1.15"
    private val rx = "$prefix.51.1.4"
    private val tx = "$prefix.51.1.3"
    private val distance = "$prefix.46.1.20"

    private fun adapter(rows: Map<String, Map<String, String>>, profile: MibProfile = MibProfiles.HUAWEI): GponSnmpAdapter =
        GponSnmpAdapter(profile, SnmpReaderFactory { _, _, _ -> object : SnmpReader {
            override fun get(oid: String): String = "offline fixture"
            override fun walkTable(columnOids: List<String>): Map<String, Map<String, String>> =
                rows.mapValues { (_, columns) -> columns.filterKeys { it in columnOids } }
            override fun close() {}
        } }) { Instant.parse("2026-09-17T00:00:00Z") }

    @Test
    fun `Huawei documented run states are up down and invalid never arbitrary LOS`() {
        val cases = mapOf("1" to OnuOperationalStatus.ONLINE, "2" to OnuOperationalStatus.OFFLINE,
            "-1" to OnuOperationalStatus.UNKNOWN, "3" to OnuOperationalStatus.UNKNOWN, "99" to OnuOperationalStatus.UNKNOWN)
        for ((raw, expected) in cases) {
            val reading = adapter(mapOf("4194304000.7" to mapOf(serial to "48575443ABCDEF12", status to raw))).pollOnus(target).single()
            assertEquals(expected, reading.status, "hwGponDeviceOntControlRunStatus=$raw")
        }
    }

    @Test
    fun `Huawei ONT optics use centi dBm and do not substitute OLT receive power for TX`() {
        val reading = adapter(mapOf("4194304000.7" to mapOf(serial to "48:57:54:43:ab:cd:ef:12", status to "1",
            rx to "-2415", tx to "245", "$prefix.51.1.6" to "7585", distance to "1234"))).pollOnus(target).single()
        assertEquals("HWTCABCDEF12", reading.serialNumber)
        assertEquals(-24.15, reading.rxPowerDbm)
        assertEquals(2.45, reading.txPowerDbm)
        assertEquals(1234, reading.distanceMeters)
    }

    @Test
    fun `Huawei invalid ranging is unavailable in polling and diagnostic interpretation`() {
        val adapter = adapter(mapOf("4194304000.7" to mapOf(serial to "48575443ABCDEF12", status to "2", distance to "-1")))
        assertNull(adapter.pollOnus(target).single().distanceMeters)
        assertNull(adapter.oidPlan.single { it.role == "DISTANCE" }.interpret("-1"))
    }

    @Test
    fun `missing or unrecognized fields do not fabricate offline LOS or optical readings`() {
        val reading = adapter(mapOf("4194304000.7" to mapOf(serial to "48575443ABCDEF12"))).pollOnus(target).single()
        assertEquals(OnuOperationalStatus.UNKNOWN, reading.status)
        assertNull(reading.rxPowerDbm)
        assertNull(reading.txPowerDbm)
        assertNull(reading.distanceMeters)
    }

    @Test
    fun `eight octet serials survive actual SNMP printable and hexadecimal rendering`() {
        for (bytes in listOf("HWTCSN12".toByteArray(), byteArrayOf(72, 87, 84, 67, -85, -51, -17, 18))) {
            val wire = OctetString(bytes).toString()
            val reading = adapter(mapOf("4194304000.7" to mapOf(serial to wire, status to "1"))).pollOnus(target).single()
            val suffix = bytes.drop(4).joinToString("") { "%02X".format(it) }
            assertEquals("HWTC$suffix", reading.serialNumber)
        }
    }

    @Test
    fun `malformed identity and six byte MAC are not fabricated into GPON serials`() {
        val invalid = listOf("uplink-port", "1000000", "C0:FD:84:65:FD:12", "AABBCCDDEEFF", "48575443ABCDEF1", "48575443ABCDEF1200", "00000000ABCDEF12")
        for (raw in invalid) assertTrue(adapter(mapOf("4194304000.7" to mapOf(serial to raw, status to "1"))).pollOnus(target).isEmpty(), raw)
    }

    @Test
    fun `raw index including configured-looking strings never becomes verified path evidence`() {
        for (index in listOf("4194304000.7", "268501249.1", "1/1/1", "undocumented")) {
            val reading = adapter(mapOf(index to mapOf(serial to "48575443ABCDEF12", status to "1"))).pollOnus(target).single()
            assertEquals(index, reading.ponPortLabel)
            assertEquals(OnuPathProvenance.UNVERIFIED_INDEX, reading.pathProvenance)
        }
    }

    @Test
    fun `FiberHome contradictory identity and speed objects report unsupported rather than ONU data`() {
        val rows = mapOf("1" to mapOf("1.3.6.1.4.1.5875.800.3.9.3.3.1.3" to "uplink-port",
            "1.3.6.1.4.1.5875.800.3.9.3.3.1.5" to "3"))
        val failure = assertFailsWith<OltProtocolException> { adapter(rows, MibProfiles.FIBERHOME).pollOnus(target.copy(vendor = "FIBERHOME")) }
        assertTrue(requireNotNull(failure.message).contains("FIBERHOME"))
        val plan = adapter(emptyMap(), MibProfiles.FIBERHOME).oidPlan
        assertTrue(plan.filter { it.essential }.all { it.oid == null })
    }
}
