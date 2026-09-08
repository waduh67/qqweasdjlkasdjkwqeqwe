package com.duluin.ftth.snmp

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HsgqSnmpAdapterTest {
    private val target = OltTarget(
        oltId = "olt-test", oltCode = "OLT-TEST", vendor = "HSGQ",
        host = "127.0.0.1", snmpPort = 1161, snmpCommunity = "test-only",
    )

    @Test
    fun `G01ID GPON serials are not discarded by the EPON MAC profile`() {
        val rows = mapOf(
            "16777472" to mapOf(SERIAL to "TEST001122aa", STATUS to "1"),
            "16777479" to mapOf(SERIAL to "TEST001122bb", STATUS to "2"),
            "16777472.0.0" to mapOf(RX to "-2200"),
            "16777472.65535.65535" to mapOf(RX to "-1000"),
        )
        val factory = reader(rows)
        val readings = HsgqSnmpAdapter(factory).pollOnus(target)

        assertEquals(2, readings.size, "GPON serial table must yield both registered ONUs, including ONU 0")
        assertEquals("TEST001122AA", readings[0].serialNumber)
        assertEquals("PON1", readings[0].ponPortLabel)
        assertEquals(OnuOperationalStatus.ONLINE, readings[0].status)
        assertEquals(-22.0, readings[0].rxPowerDbm, "OLT PON optics must never overwrite ONU optics")
        assertEquals(OnuOperationalStatus.OFFLINE, readings[1].status)
        assertNull(readings[1].rxPowerDbm)
    }

    @Test
    fun `profile selection is per device and preserves EPON MAC polling`() {
        val rows = mapOf(
            "16777473" to mapOf(HsgqEponSnmpAdapter.MAC_OID to "02:00:00:11:22:aa", HsgqEponSnmpAdapter.STATUS_OID to "1"),
            "16777473.0.0" to mapOf(HsgqEponSnmpAdapter.RX_POWER_OID to "-2351"),
        )
        val adapter = HsgqSnmpAdapter(reader(rows, description = "HSGQ-E04I"))
        assertEquals(SERIAL, adapter.oidPlanFor("hsgq-g01id firmware 1").first().oid)
        assertEquals(HsgqEponSnmpAdapter.MAC_OID, adapter.oidPlanFor("HSGQ-E04I").first().oid)
        assertEquals(HsgqEponSnmpAdapter.MAC_OID, adapter.oidPlanFor(null).first().oid)
        val onu = adapter.pollOnus(target).single()
        assertEquals("0200001122AA", onu.serialNumber)
        assertEquals(-23.51, onu.rxPowerDbm)
    }

    @Test
    fun `zero based ONT ID and optical index exclude OLT port rows`() {
        val adapter = HsgqSnmpAdapter(reader(emptyMap()))
        assertEquals("PON01/0", adapter.ontIdFrom("16777472"))
        assertEquals("PON02/7", adapter.ontIdFrom("16777735"))
        assertEquals("16777472", adapter.inventoryIndex("16777472.0.0"))
        assertNull(adapter.inventoryIndex("16777472.65535.65535"))
        assertNull(adapter.inventoryIndex("16777472.1.0"))
        assertNull(adapter.ontIdFrom("bad-index"))
    }

    @Test
    fun `unsupported fields unknown enums and malformed serials stay unavailable`() {
        val plan = HsgqSnmpAdapter(reader(emptyMap())).oidPlanFor("HSGQ-G01ID").associateBy { it.role }
        assertEquals("Active", plan.getValue("STATE").interpret("1"))
        assertEquals("Normal", plan.getValue("CONFIG_STATE").interpret("1"))
        assertEquals("1970/01/12 19:22:12", plan.getValue("LAST_ON").interpret("1970/01/12 19:22:12"))
        assertNull(plan.getValue("STATE").interpret("99"))
        assertNull(plan.getValue("CONFIG_STATE").interpret("99"))
        assertNull(plan.getValue("SERIAL").interpret("No Such Instance"))
        assertNull(plan.getValue("SERIAL").interpret("000000000000"))
        for (role in listOf("DEVICE_TYPE", "LAST_OFF", "DOWN_CAUSE")) {
            assertNull(plan.getValue(role).oid)
        }
        for (raw in listOf("-2147483648", "2147483647", "-32768", "65535", "bad")) {
            assertNull(plan.getValue("RX_POWER").interpret(raw))
        }
        assertEquals("-22.0 dBm", plan.getValue("RX_POWER").interpret("-2200"))
    }

    @Test
    fun `optical timeout preserves inventory but identity timeout fails polling`() {
        val rows = mapOf("16777472" to mapOf(SERIAL to "TEST001122AA", STATUS to "99"))
        val onu = HsgqSnmpAdapter(reader(rows, failingColumn = RX)).pollOnus(target).single()
        assertEquals(OnuOperationalStatus.UNKNOWN, onu.status)
        assertNull(onu.rxPowerDbm)
        assertNull(onu.lastOnAt)
        assertFailsWith<OltProtocolException> {
            HsgqSnmpAdapter(reader(rows, failingColumn = SERIAL)).pollOnus(target)
        }
    }

    private fun reader(
        rows: Map<String, Map<String, String>>,
        description: String = "HSGQ-G01ID",
        failingColumn: String? = null,
    ) = SnmpReaderFactory { _, _, _ ->
        object : SnmpReader {
            override fun get(oid: String) = description
            override fun walkTable(columnOids: List<String>): Map<String, Map<String, String>> {
                if (failingColumn in columnOids) throw OltProtocolException("fixture timeout")
                return rows.mapValues { (_, row) ->
                    row.filterKeys { it in columnOids }
                }.filterValues { it.isNotEmpty() }
            }
            override fun close() = Unit
        }
    }

    private companion object {
        const val SERIAL = "1.3.6.1.4.1.50224.3.12.2.1.15"
        const val STATUS = "1.3.6.1.4.1.50224.3.12.2.1.4"
        const val RX = "1.3.6.1.4.1.50224.3.12.3.1.4"
    }
}
