package com.duluin.ftth.simulator.olt

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter
import com.duluin.ftth.snmp.ProbeResult
import java.net.DatagramSocket
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bukti kesetiaan protokol: adapter [HsgqEponSnmpAdapter] **produksi** (yang OID-nya
 * diverifikasi lapangan) diarahkan ke agen simulator lewat soket UDP nyata. Kalau
 * adapter bisa menemukan & membaca ONU dari agen ini, berarti agen berbicara SNMP
 * persis seperti yang app harapkan dari OLT sungguhan — inti dari "lab protokol nyata".
 */
class HsgqOltSnmpAgentTest {

    private var agent: HsgqOltSnmpAgent? = null

    @AfterTest
    fun tearDown() {
        agent?.stop()
    }

    @Test
    fun `adapter HSGQ produksi menemukan dan membaca ONU dari agen simulator`() {
        val port = freeUdpPort()
        val onus = OltSimModel.populate(ponCount = 1, onusPerPon = 4)
        val model = OltSimModel("HSGQ-E04I lab", onus)
        agent = HsgqOltSnmpAgent("127.0.0.1", port, "public") { model.snapshot(Instant.now()) }.also { it.start() }

        val adapter = HsgqEponSnmpAdapter()
        val target = OltTarget(
            oltId = "olt-lab",
            oltCode = "OLT-LAB",
            vendor = "HSGQ",
            host = "127.0.0.1",
            snmpPort = port,
            snmpCommunity = "public",
        )

        // 1) probe → sysDescr terbaca.
        val probe = adapter.probe(target)
        assertTrue(probe is ProbeResult.Reachable, "probe harus Reachable, dapat: $probe")
        assertEquals("HSGQ-E04I lab", probe.systemDescription)

        // 2) pollOnus → keempat ONU (termasuk yang offline) terbaca dari dua tabel.
        val readings = adapter.pollOnus(target).sortedBy { it.serialNumber }
        assertEquals(4, readings.size, "4 ONU harus terbaca, dapat: ${readings.map { it.serialNumber }}")

        // Serial = MAC ternormalisasi: 12 heksa huruf besar, ber-OUI C0FD84 milik lab.
        assertTrue(
            readings.all { it.serialNumber.matches(Regex("^C0FD84[0-9A-F]{6}$")) },
            "serial harus MAC C0FD84xxxxxx: ${readings.map { it.serialNumber }}",
        )

        // 3) status: 3 online + 1 offline (profil populasi).
        val online = readings.filter { it.status == OnuOperationalStatus.ONLINE }
        val offline = readings.filter { it.status == OnuOperationalStatus.OFFLINE }
        assertEquals(3, online.size, "harus 3 ONU online")
        assertEquals(1, offline.size, "harus 1 ONU offline")

        // 4) optik: online punya RX masuk akal; offline absen dari tabel optik → RX null.
        assertTrue(
            online.all { it.rxPowerDbm != null && it.rxPowerDbm!! in -50.0..10.0 },
            "ONU online harus punya RX valid: ${online.map { it.serialNumber to it.rxPowerDbm }}",
        )
        assertTrue(offline.all { it.rxPowerDbm == null }, "ONU offline tak boleh punya RX")

        // 5) label PON dari indeks (semua di PON1).
        assertTrue(readings.all { it.ponPortLabel == "PON1" }, "label PON: ${readings.map { it.ponPortLabel }}")
    }

    /**
     * Regresi GETBULK multi-PDU: 2 PON × 8 ONU = 16 ONU seperti populasi lab. Penting karena
     * `TableUtils` (sisi adapter) memakai max-repetitions ~10 → tabel 16 baris **melintasi
     * lebih dari satu GETBULK**, dan tabel optik (yang terakhir di MIB) memicu cabang
     * "repeater habis". Bila agen men-SKIP slot yang habis (bukan mengisi endOfMibView),
     * grid response jadi tak-persegi → snmp4j menolak dengan "not in lexicographic order"
     * dan pollOnus melempar. Tes 4-ONU tak menangkap ini karena walk-nya muat satu PDU.
     */
    @Test
    fun `adapter membaca seluruh ONU saat walk melintasi banyak GETBULK`() {
        val port = freeUdpPort()
        val onus = OltSimModel.populate(ponCount = 2, onusPerPon = 8)
        val model = OltSimModel("HSGQ-E04I lab", onus)
        agent = HsgqOltSnmpAgent("127.0.0.1", port, "public") { model.snapshot(Instant.now()) }.also { it.start() }

        val adapter = HsgqEponSnmpAdapter()
        val target = OltTarget(
            oltId = "olt-lab",
            oltCode = "OLT-LAB",
            vendor = "HSGQ",
            host = "127.0.0.1",
            snmpPort = port,
            snmpCommunity = "public",
        )

        // Walk inventori (3 kolom) + optik (2 kolom) sama-sama > max-repetitions → multi-PDU.
        val readings = adapter.pollOnus(target)
        assertEquals(16, readings.size, "16 ONU harus terbaca utuh, dapat: ${readings.map { it.serialNumber }}")

        // Profil per-PON: onu#8 offline, sisanya (1..7) online → 14 online + 2 offline.
        assertEquals(14, readings.count { it.status == OnuOperationalStatus.ONLINE }, "harus 14 ONU online")
        assertEquals(2, readings.count { it.status == OnuOperationalStatus.OFFLINE }, "harus 2 ONU offline")

        // Kolom tak tergeser: tiap online punya RX valid; tiap offline absen dari optik (RX null).
        assertTrue(
            readings.filter { it.status == OnuOperationalStatus.ONLINE }.all { it.rxPowerDbm != null },
            "semua ONU online harus punya RX (kolom optik tak boleh tergeser)",
        )
        assertTrue(
            readings.filter { it.status == OnuOperationalStatus.OFFLINE }.all { it.rxPowerDbm == null },
            "ONU offline tak boleh punya RX",
        )

        // Label PON terbaca benar dari indeks: 8 di PON1, 8 di PON2.
        assertEquals(8, readings.count { it.ponPortLabel == "PON1" }, "harus 8 ONU di PON1")
        assertEquals(8, readings.count { it.ponPortLabel == "PON2" }, "harus 8 ONU di PON2")
    }

    /**
     * Armada OLT: `macSlot` berbeda WAJIB menghasilkan serial ONU yang disjoint. Serial =
     * identitas ONU di app; bila dua OLT memakai macSlot sama, pon/onu yang sama melahirkan
     * serial kembar dan app mengira satu ONU. Prefix tetap OUI lab `C0FD84`.
     */
    @Test
    fun `macSlot berbeda membuat serial ONU unik antar-OLT`() {
        fun macHex(o: SimOnu) = o.mac.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val olt1 = OltSimModel.populate(ponCount = 2, onusPerPon = 8, macSlot = 1).map(::macHex).toSet()
        val olt2 = OltSimModel.populate(ponCount = 2, onusPerPon = 8, macSlot = 2).map(::macHex).toSet()

        assertTrue(olt1.intersect(olt2).isEmpty(), "MAC dua OLT (macSlot beda) tak boleh beririsan")
        assertTrue(olt1.all { it.startsWith("C0FD8401") }, "OLT macSlot=1 → prefix C0FD8401: $olt1")
        assertTrue(olt2.all { it.startsWith("C0FD8402") }, "OLT macSlot=2 → prefix C0FD8402: $olt2")
    }

    /** Ambil port UDP bebas lalu lepas — dipakai agen agar tes deterministik tanpa tabrakan 161. */
    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }
}
