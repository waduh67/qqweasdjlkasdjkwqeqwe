package com.duluin.ftth.snmp

import com.duluin.ftth.snmp.HsgqEponSnmpAdapter.Companion.MAC_OID
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter.Companion.NAME_OID
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter.Companion.RX_POWER_OID
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter.Companion.STATUS_OID
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter.Companion.TX_POWER_OID
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Menguji penafsiran SNMP HSGQ EPON tanpa perangkat, memakai baris hasil `snmpwalk`
 * sungguhan dari HSGQ-E04I: identitas MAC, join dua-tabel (inventori ↔ optik),
 * skala redaman 0,01 dBm, dan perilaku ONU offline yang absen dari tabel optik.
 */
class HsgqEponSnmpAdapterTest {

    private val target = OltTarget(
        oltId = "olt-1",
        oltCode = "OLT-EPON-01",
        vendor = "HSGQ",
        host = "10.0.0.1",
        snmpPort = 1161,
        snmpCommunity = "public",
    )

    /**
     * Pembaca SNMP tiruan dua-tabel: memilih inventori atau optik berdasar kolom yang
     * diminta, meniru [SnmpSession] yang men-walk tiap tabel dengan indeksnya sendiri
     * (inventori = `<onuIdx>`, optik = `<onuIdx>.0.0`).
     */
    private fun readerOf(
        inventory: Map<String, Map<String, String>>,
        optical: Map<String, Map<String, String>>,
    ): SnmpReaderFactory =
        SnmpReaderFactory { _, _, _ ->
            object : SnmpReader {
                override fun get(oid: String) = "tiruan sysDescr HSGQ"
                override fun walkTable(columnOids: List<String>): Map<String, Map<String, String>> =
                    if (MAC_OID in columnOids) inventory else optical
                override fun close() {}
            }
        }

    private fun adapter(factory: SnmpReaderFactory) =
        HsgqEponSnmpAdapter(factory, clock = { Instant.parse("2026-08-01T00:00:00Z") })

    @Test
    fun `ONU online - MAC dinormalkan, redaman digabung dari tabel optik, PON dari indeks`() {
        // Baris nyata HSGQ-E04I: PON1/ONU1, MAC C0:FD:84:65:FD:12, online (status 1),
        // RX -2552 = -25,52 dBm, TX 236 = 2,36 dBm.
        val inventory = mapOf(
            "16777473" to mapOf(
                NAME_OID to "ONU01/01",
                MAC_OID to "c0:fd:84:65:fd:12",
                STATUS_OID to "1",
            ),
        )
        val optical = mapOf(
            "16777473.0.0" to mapOf(RX_POWER_OID to "-2552", TX_POWER_OID to "236"),
        )

        val onu = adapter(readerOf(inventory, optical)).pollOnus(target).single()

        assertEquals("C0FD8465FD12", onu.serialNumber)
        assertEquals(OnuOperationalStatus.ONLINE, onu.status)
        assertEquals(-25.52, onu.rxPowerDbm)
        assertEquals(2.36, onu.txPowerDbm)
        assertEquals("PON1", onu.ponPortLabel)
        assertEquals("OLT-EPON-01", onu.oltCode)
    }

    @Test
    fun `ONU offline absen dari tabel optik - tetap terbaca, redaman null, bukan alarm palsu`() {
        // PON1/ONU2 offline (status 2). Perangkat tak melaporkan baris optik untuknya;
        // ONU harus tetap muncul (agar server tahu ia offline), redaman null.
        val inventory = mapOf(
            "16777474" to mapOf(
                NAME_OID to "ONU01/02",
                MAC_OID to "d4:d5:1b:69:71:82",
                STATUS_OID to "2",
            ),
        )
        val onu = adapter(readerOf(inventory, optical = emptyMap())).pollOnus(target).single()

        assertEquals("D4D51B697182", onu.serialNumber)
        assertEquals(OnuOperationalStatus.OFFLINE, onu.status)
        assertNull(onu.rxPowerDbm)
        assertNull(onu.txPowerDbm)
    }

    @Test
    fun `PON port diturunkan dari byte indeks ONU`() {
        // PON2/ONU1 = indeks 16777729 (0x01000201) → PON2.
        val inventory = mapOf(
            "16777729" to mapOf(MAC_OID to "66:f8:fd:8e:6e:97", STATUS_OID to "1"),
        )
        val optical = mapOf("16777729.0.0" to mapOf(RX_POWER_OID to "-613"))

        val onu = adapter(readerOf(inventory, optical)).pollOnus(target).single()

        assertEquals("PON2", onu.ponPortLabel)
        assertEquals(-6.13, onu.rxPowerDbm)
    }

    @Test
    fun `sentinel optik menjadi null, bukan redaman ekstrem`() {
        // Modul optik port PON melaporkan -2147483648 saat tak ada pembacaan.
        val inventory = mapOf(
            "16777473" to mapOf(MAC_OID to "c0:fd:84:65:fd:12", STATUS_OID to "1"),
        )
        val optical = mapOf("16777473.0.0" to mapOf(RX_POWER_OID to "-2147483648"))

        val onu = adapter(readerOf(inventory, optical)).pollOnus(target).single()

        assertNull(onu.rxPowerDbm)
    }

    @Test
    fun `baris tanpa MAC dibuang, tidak mengotori data server`() {
        val inventory = mapOf(
            "16777473" to mapOf(MAC_OID to "c0:fd:84:65:fd:12", STATUS_OID to "1"),
            "16777474" to mapOf(STATUS_OID to "2"), // tanpa MAC
        )
        val readings = adapter(readerOf(inventory, optical = emptyMap())).pollOnus(target)

        assertEquals(1, readings.size)
        assertEquals("C0FD8465FD12", readings.single().serialNumber)
    }

    @Test
    fun `status tak dikenal jatuh ke UNKNOWN, bukan gagal`() {
        val inventory = mapOf(
            "16777473" to mapOf(MAC_OID to "c0:fd:84:65:fd:12", STATUS_OID to "9"),
        )
        val onu = adapter(readerOf(inventory, optical = emptyMap())).pollOnus(target).single()

        assertEquals(OnuOperationalStatus.UNKNOWN, onu.status)
    }

    @Test
    fun `probe melaporkan tidak terjangkau saat community kosong`() {
        val result = adapter(readerOf(emptyMap(), emptyMap()))
            .probe(target.copy(snmpCommunity = null))

        assertEquals(
            true,
            result is ProbeResult.Unreachable,
        )
    }
}
