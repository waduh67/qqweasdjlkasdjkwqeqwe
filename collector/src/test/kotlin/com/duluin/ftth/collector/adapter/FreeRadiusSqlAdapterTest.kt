package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.RadiusSessionReading
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Menguji keputusan adapter FreeRADIUS terhadap NAS DAE tiruan: paket yang benar
 * dirakit dari sesi yang ditemukan (User-Name + Acct-Session-Id, plus VSA rate untuk
 * CoA), idempotensi Disconnect (sesi tak ada / NAK 503 = selesai, bukan gagal), dan
 * kegagalan yang tegas (NAK lain, CoA pada sesi mati, kredensial kurang).
 *
 * Pembacaan `radacct` (JDBC) dipalsukan lewat [RadacctReader] — jalur SQL murni diuji
 * di lab terhadap profil docker `radius`, seperti adapter SNMP diuji di logika, bukan OLT.
 */
class FreeRadiusSqlAdapterTest {

    private class FakeReader(
        val sessions: List<RadiusSessionReading> = emptyList(),
        val active: ActiveSession? = null,
    ) : RadacctReader {
        override fun activeSessions(target: NasTarget) = sessions
        override fun findActive(target: NasTarget, username: String) = active
    }

    private fun target(host: String? = "127.0.0.1", coaSecret: String? = "dae-secret") = NasTarget(
        nasId = "nas-1",
        name = "BRAS-FR-01",
        vendor = "FREERADIUS",
        host = host,
        adapterType = "FREERADIUS",
        coaSecret = coaSecret,
    )

    private fun adapter(reader: RadacctReader, daePort: Int) = FreeRadiusSqlAdapter(
        reader = reader,
        dae = RadiusDaeClient(timeout = Duration.ofMillis(300), retries = 1),
        daePort = daePort,
    )

    private fun disconnect(user: String) =
        BngActionCommand("act-$user", "nas-1", BngActionKind.DISCONNECT, user)

    private fun coa(user: String, down: Int, up: Int) =
        BngActionCommand("act-$user", "nas-1", BngActionKind.COA, user, downMbps = down, upMbps = up)

    @Test
    fun `pollSessions meneruskan hasil pembaca radacct`() {
        val reading = RadiusSessionReading("budi@isp", online = true, inOctets = 10, outOctets = 90)
        val sessions = adapter(FakeReader(sessions = listOf(reading)), 3799).pollSessions(target())
        assertEquals(listOf("budi@isp"), sessions.map { it.username })
    }

    @Test
    fun `DISCONNECT merakit Disconnect-Request dengan User-Name dan Acct-Session-Id`() {
        RadiusNasStub("dae-secret") { RadiusNasStub.Reply(RadiusDae.DISCONNECT_ACK) }.start().use { nas ->
            val reader = FakeReader(active = ActiveSession(acctSessionId = "0xSID1", nasIp = "10.20.0.1"))
            adapter(reader, nas.port).execute(target(), disconnect("budi@isp"))

            val req = nas.received!!
            assertEquals(RadiusDae.DISCONNECT_REQUEST, req[0].toInt() and 0xFF)
            assertEquals("budi@isp", RadiusNasStub.stringAttr(req, RadiusDae.ATTR_USER_NAME))
            assertEquals("0xSID1", RadiusNasStub.stringAttr(req, RadiusDae.ATTR_ACCT_SESSION_ID))
        }
    }

    @Test
    fun `DISCONNECT tanpa sesi aktif tak mengirim paket dan tak melempar`() {
        RadiusNasStub("dae-secret") { RadiusNasStub.Reply(RadiusDae.DISCONNECT_ACK) }.start().use { nas ->
            adapter(FakeReader(active = null), nas.port).execute(target(), disconnect("ghost@isp"))
            assertEquals(0, nas.requestCount)
        }
    }

    @Test
    fun `DISCONNECT NAK 503 dianggap selesai (idempoten)`() {
        RadiusNasStub("dae-secret") { RadiusNasStub.Reply(RadiusDae.DISCONNECT_NAK, errorCause = 503) }.start().use { nas ->
            val reader = FakeReader(active = ActiveSession("0xSID1", "10.20.0.1"))
            adapter(reader, nas.port).execute(target(), disconnect("budi@isp")) // tak melempar = idempoten
            // Disconnect tetap dikirim; NAS membalas NAK 503 dan adapter menelannya sebagai selesai.
            assertEquals(RadiusDae.DISCONNECT_REQUEST, nas.received!![0].toInt() and 0xFF)
        }
    }

    @Test
    fun `DISCONNECT NAK selain 503 melempar dengan label sebab`() {
        RadiusNasStub("dae-secret") { RadiusNasStub.Reply(RadiusDae.DISCONNECT_NAK, errorCause = 501) }.start().use { nas ->
            val reader = FakeReader(active = ActiveSession("0xSID1", "10.20.0.1"))
            val ex = assertFailsWith<IllegalStateException> {
                adapter(reader, nas.port).execute(target(), disconnect("budi@isp"))
            }
            assertTrue(ex.message!!.contains("501"), ex.message)
        }
    }

    @Test
    fun `CoA merakit CoA-Request dengan VSA Mikrotik-Rate-Limit unggah-slash-unduh`() {
        RadiusNasStub("dae-secret") { RadiusNasStub.Reply(RadiusDae.COA_ACK) }.start().use { nas ->
            val reader = FakeReader(active = ActiveSession("0xSID1", "10.20.0.1"))
            adapter(reader, nas.port).execute(target(), coa("budi@isp", down = 100, up = 30))

            val req = nas.received!!
            assertEquals(RadiusDae.COA_REQUEST, req[0].toInt() and 0xFF)
            assertEquals("30M/100M", mikrotikRate(req))
        }
    }

    @Test
    fun `CoA pada sesi tak aktif melempar tanpa mengirim paket`() {
        RadiusNasStub("dae-secret") { RadiusNasStub.Reply(RadiusDae.COA_ACK) }.start().use { nas ->
            val ex = assertFailsWith<IllegalStateException> {
                adapter(FakeReader(active = null), nas.port).execute(target(), coa("ghost@isp", 100, 30))
            }
            assertTrue(ex.message!!.contains("tak aktif"), ex.message)
            assertEquals(0, nas.requestCount)
        }
    }

    @Test
    fun `Secret CoA kosong ditolak sebelum menyentuh NAS`() {
        val ex = assertFailsWith<IllegalStateException> {
            adapter(FakeReader(active = ActiveSession("0xSID1", "10.20.0.1")), 3799)
                .execute(target(coaSecret = null), disconnect("budi@isp"))
        }
        assertTrue(ex.message!!.contains("Secret CoA"), ex.message)
    }

    @Test
    fun `alamat NAS kosong ditolak sebelum menyentuh NAS`() {
        val ex = assertFailsWith<IllegalStateException> {
            adapter(FakeReader(active = ActiveSession("0xSID1", "10.20.0.1")), 3799)
                .execute(target(host = null), disconnect("budi@isp"))
        }
        assertTrue(ex.message!!.contains("alamat NAS"), ex.message)
    }

    /** Mengekstrak nilai VSA Mikrotik-Rate-Limit (14988/8) dari paket, bila ada. */
    private fun mikrotikRate(packet: ByteArray): String? {
        val vsas = RadiusNasStub.attributes(packet)[RadiusDae.ATTR_VENDOR_SPECIFIC] ?: return null
        for (v in vsas) {
            if (v.size < 6) continue
            val vendor = ((v[0].toInt() and 0xFF).toLong() shl 24) or ((v[1].toInt() and 0xFF).toLong() shl 16) or
                ((v[2].toInt() and 0xFF).toLong() shl 8) or (v[3].toInt() and 0xFF).toLong()
            val vendorType = v[4].toInt() and 0xFF
            if (vendor == RadiusDae.VENDOR_MIKROTIK && vendorType == RadiusDae.MIKROTIK_RATE_LIMIT) {
                return v.copyOfRange(6, v.size).toString(StandardCharsets.UTF_8)
            }
        }
        return null
    }
}
