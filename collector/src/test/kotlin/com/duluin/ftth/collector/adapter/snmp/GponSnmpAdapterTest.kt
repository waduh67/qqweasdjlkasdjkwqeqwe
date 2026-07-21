package com.duluin.ftth.collector.adapter.snmp

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menguji penafsiran data SNMP tanpa perangkat: skala redaman per vendor, format
 * serial GPON, sentinel "tidak terbaca", pembuangan nilai mustahil, dan pemetaan
 * status. Inilah bagian yang paling rawan salah saat OLT sungguhan akhirnya
 * disambungkan (Phase 2b) — verifikasi berikutnya tinggal memastikan OID-nya benar.
 */
class GponSnmpAdapterTest {

    private val target = OltTarget(
        oltId = "olt-1",
        oltCode = "OLT-BKS-01",
        vendor = "ZTE",
        host = "10.0.0.1",
        snmpCommunity = "public",
    )

    /** Pembaca SNMP tiruan: satu baris tabel, dikunci OID → nilai mentah. */
    private fun readerOf(vararg rows: Map<String, String>): SnmpReaderFactory =
        SnmpReaderFactory { _, _, _ ->
            object : SnmpReader {
                override fun get(oid: String) = "tiruan sysDescr"
                override fun walkTable(columnOids: List<String>): Map<String, Map<String, String>> =
                    rows.mapIndexed { index, row -> "$index" to row }.toMap()
                override fun close() {}
            }
        }

    private fun adapter(profile: MibProfile, factory: SnmpReaderFactory) =
        GponSnmpAdapter(profile, factory, clock = { Instant.parse("2026-07-20T00:00:00Z") })

    @Test
    fun `ZTE - serial di-decode ke kode vendor ASCII + heksa, redaman dibagi 1000`() {
        // Serial mentah: ZTEG (5A544547) + C0FFEE01 -> "ZTEGC0FFEE01".
        // rx -22,50 dBm dilaporkan sebagai -22500 dalam satuan 0,001 dBm.
        val row = mapOf(
            MibProfiles.ZTE.serialNumberOid to "5A544547C0FFEE01",
            MibProfiles.ZTE.statusOid to "3",
            MibProfiles.ZTE.rxPowerOid to "-22500",
        )
        val readings = adapter(MibProfiles.ZTE, readerOf(row)).pollOnus(target)

        assertEquals(1, readings.size)
        val onu = readings.single()
        assertEquals("ZTEGC0FFEE01", onu.serialNumber)
        assertEquals(OnuOperationalStatus.ONLINE, onu.status)
        assertEquals(-22.5, onu.rxPowerDbm)
        assertEquals("OLT-BKS-01", onu.oltCode)
    }

    @Test
    fun `Huawei - redaman dibagi 100 dan status dipetakan berbeda dari ZTE`() {
        // Huawei memakai satuan 0,01 dBm: -2415 -> -24,15 dBm. Status 3 = LOS
        // (di ZTE status 3 justru ONLINE) — pemetaan per-vendor harus dihormati.
        val row = mapOf(
            MibProfiles.HUAWEI.serialNumberOid to "48575443ABCDEF12",
            MibProfiles.HUAWEI.statusOid to "3",
            MibProfiles.HUAWEI.rxPowerOid to "-2415",
        )
        val onu = adapter(MibProfiles.HUAWEI, readerOf(row)).pollOnus(target).single()

        assertEquals("HWTCABCDEF12", onu.serialNumber)
        assertEquals(OnuOperationalStatus.LOS, onu.status)
        assertEquals(-24.15, onu.rxPowerDbm)
    }

    @Test
    fun `nilai sentinel menjadi null, bukan redaman ekstrem yang memicu alarm palsu`() {
        // 2147483647 adalah penanda "tidak terbaca". Bila lolos, akan tampak
        // sebagai ~2 juta dBm dan langsung memicu alarm.
        val row = mapOf(
            MibProfiles.ZTE.serialNumberOid to "5A544547DEADBEEF",
            MibProfiles.ZTE.statusOid to "3",
            MibProfiles.ZTE.rxPowerOid to "2147483647",
        )
        val onu = adapter(MibProfiles.ZTE, readerOf(row)).pollOnus(target).single()

        assertNull(onu.rxPowerDbm)
    }

    @Test
    fun `redaman di luar rentang masuk akal dibuang menjadi null`() {
        // -60 dBm (dilaporkan -60000) di luar rentang -50..10; pasti salah baca.
        val row = mapOf(
            MibProfiles.ZTE.serialNumberOid to "5A544547DEADBEEF",
            MibProfiles.ZTE.statusOid to "3",
            MibProfiles.ZTE.rxPowerOid to "-60000",
        )
        val onu = adapter(MibProfiles.ZTE, readerOf(row)).pollOnus(target).single()

        assertNull(onu.rxPowerDbm)
    }

    @Test
    fun `status yang tidak dikenal jatuh ke UNKNOWN, bukan gagal`() {
        val row = mapOf(
            MibProfiles.ZTE.serialNumberOid to "5A544547DEADBEEF",
            MibProfiles.ZTE.statusOid to "99",
            MibProfiles.ZTE.rxPowerOid to "-20000",
        )
        val onu = adapter(MibProfiles.ZTE, readerOf(row)).pollOnus(target).single()

        assertEquals(OnuOperationalStatus.UNKNOWN, onu.status)
    }

    @Test
    fun `baris tanpa serial dibuang, tidak mengotori data server`() {
        val withSerial = mapOf(
            MibProfiles.ZTE.serialNumberOid to "5A5445470000AAAA",
            MibProfiles.ZTE.statusOid to "3",
            MibProfiles.ZTE.rxPowerOid to "-20000",
        )
        val withoutSerial = mapOf(
            MibProfiles.ZTE.statusOid to "3",
            MibProfiles.ZTE.rxPowerOid to "-20000",
        )
        val readings = adapter(MibProfiles.ZTE, readerOf(withSerial, withoutSerial)).pollOnus(target)

        assertEquals(1, readings.size)
        assertEquals("ZTEG0000AAAA", readings.single().serialNumber)
    }

    @Test
    fun `probe melaporkan tidak terjangkau saat community kosong, tanpa membuka soket`() {
        val result = adapter(MibProfiles.ZTE, readerOf())
            .probe(target.copy(snmpCommunity = null))

        assertTrue(result is com.duluin.ftth.collector.adapter.ProbeResult.Unreachable)
    }
}
