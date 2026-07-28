package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.RadiusSessionReading
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private fun adapter(reader: RadacctReader, daePort: Int, writer: RadiusWriter = JdbcRadiusWriter()) =
        FreeRadiusSqlAdapter(
            reader = reader,
            writer = writer,
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

    // --- Jalur provisioning SQL: adapter mendelegasikan ke RadiusWriter (bukan DAE) ---

    /** Penulis tiruan: merekam pemanggilan agar keputusan routing adapter teruji tanpa DB. */
    private class RecordingWriter : RadiusWriter {
        val calls = mutableListOf<String>()
        override fun provision(target: NasTarget, username: String, password: String, groupname: String) {
            calls += "provision($username,$password,$groupname)"
        }
        override fun deprovision(target: NasTarget, username: String) {
            calls += "deprovision($username)"
        }
        override fun syncGroup(
            target: NasTarget,
            groupname: String,
            rateLimit: String,
            simultaneousUse: Int?,
            fupGroupname: String?,
            fupRateLimit: String?,
        ) {
            calls += "syncGroup($groupname,$rateLimit,$simultaneousUse,$fupGroupname,$fupRateLimit)"
        }
    }

    @Test
    fun `PROVISION mendelegasikan kredensial dan grup ke penulis, tak butuh Secret CoA`() {
        val writer = RecordingWriter()
        // coaSecret & host sengaja null: jalur SQL bukan DAE, harus tetap jalan.
        adapter(FakeReader(), 3799, writer).execute(
            target(host = null, coaSecret = null),
            BngActionCommand("act-1", "nas-1", BngActionKind.PROVISION, "budi@isp", groupname = "plan:p1", password = "s3cr3t"),
        )
        assertEquals(listOf("provision(budi@isp,s3cr3t,plan:p1)"), writer.calls)
    }

    @Test
    fun `PROVISION tanpa password ditolak sebelum menulis`() {
        val writer = RecordingWriter()
        val ex = assertFailsWith<IllegalStateException> {
            adapter(FakeReader(), 3799, writer).execute(
                target(),
                BngActionCommand("act-1", "nas-1", BngActionKind.PROVISION, "budi@isp", groupname = "plan:p1"),
            )
        }
        assertTrue(ex.message!!.contains("password"), ex.message)
        assertTrue(writer.calls.isEmpty(), "tak boleh menyentuh penulis")
    }

    @Test
    fun `PROVISION tanpa grup ditolak sebelum menulis`() {
        val ex = assertFailsWith<IllegalStateException> {
            adapter(FakeReader(), 3799, RecordingWriter()).execute(
                target(),
                BngActionCommand("act-1", "nas-1", BngActionKind.PROVISION, "budi@isp", password = "s3cr3t"),
            )
        }
        assertTrue(ex.message!!.contains("grup"), ex.message)
    }

    @Test
    fun `DEPROVISION mendelegasikan penghapusan otorisasi akun ke penulis`() {
        val writer = RecordingWriter()
        adapter(FakeReader(), 3799, writer).execute(
            target(host = null, coaSecret = null),
            BngActionCommand("act-1", "nas-1", BngActionKind.DEPROVISION, "budi@isp"),
        )
        assertEquals(listOf("deprovision(budi@isp)"), writer.calls)
    }

    @Test
    fun `SYNC_GROUP meneruskan rate-limit, batas sesi, dan grup FUP ke penulis`() {
        val writer = RecordingWriter()
        adapter(FakeReader(), 3799, writer).execute(
            target(host = null, coaSecret = null),
            BngActionCommand(
                "act-1", "nas-1", BngActionKind.SYNC_GROUP, "",
                groupname = "plan:p1", rateLimit = "30M/100M",
                simultaneousUse = 1, fupGroupname = "plan:p1:fup", fupRateLimit = "5M/10M",
            ),
        )
        assertEquals(listOf("syncGroup(plan:p1,30M/100M,1,plan:p1:fup,5M/10M)"), writer.calls)
    }

    @Test
    fun `SYNC_GROUP tanpa rate-limit ditolak sebelum menulis`() {
        val ex = assertFailsWith<IllegalStateException> {
            adapter(FakeReader(), 3799, RecordingWriter()).execute(
                target(),
                BngActionCommand("act-1", "nas-1", BngActionKind.SYNC_GROUP, "", groupname = "plan:p1"),
            )
        }
        assertTrue(ex.message!!.contains("rate-limit"), ex.message)
    }

    // --- JdbcRadiusWriter: bentuk SQL yang ditulis, diuji lewat koneksi perekam (tanpa DB) ---

    @Test
    fun `JdbcRadiusWriter provision menulis radcheck dan radusergroup dalam satu transaksi`() {
        val db = RecordingDb()
        JdbcRadiusWriter(connect = { db.connection() }).provision(target(), "budi@isp", "s3cr3t", "plan:p1")

        assertEquals(false, db.autoCommitDuringBody, "harus nonaktifkan auto-commit selama body")
        assertTrue(db.committed && !db.rolledBack, "sukses = commit, tanpa rollback")
        assertTrue(db.closed, "koneksi ditutup")
        assertEquals(
            listOf<Pair<String, List<String?>>>(
                "DELETE FROM radcheck WHERE username = ? AND attribute = 'Cleartext-Password'" to listOf("budi@isp"),
                "INSERT INTO radcheck (username, attribute, op, value) VALUES (?, 'Cleartext-Password', ':=', ?)"
                    to listOf("budi@isp", "s3cr3t"),
                "DELETE FROM radusergroup WHERE username = ?" to listOf("budi@isp"),
                "INSERT INTO radusergroup (username, groupname, priority) VALUES (?, ?, 1)"
                    to listOf("budi@isp", "plan:p1"),
            ),
            db.executed,
        )
    }

    @Test
    fun `JdbcRadiusWriter deprovision menghapus tiga tabel otorisasi akun`() {
        val db = RecordingDb()
        JdbcRadiusWriter(connect = { db.connection() }).deprovision(target(), "budi@isp")

        assertEquals(
            listOf<Pair<String, List<String?>>>(
                "DELETE FROM radcheck WHERE username = ?" to listOf("budi@isp"),
                "DELETE FROM radreply WHERE username = ?" to listOf("budi@isp"),
                "DELETE FROM radusergroup WHERE username = ?" to listOf("budi@isp"),
            ),
            db.executed,
        )
        assertTrue(db.committed)
    }

    @Test
    fun `JdbcRadiusWriter syncGroup menulis rate-limit, Simultaneous-Use, dan grup FUP`() {
        val db = RecordingDb()
        JdbcRadiusWriter(connect = { db.connection() })
            .syncGroup(target(), "plan:p1", "30M/100M", simultaneousUse = 1, fupGroupname = "plan:p1:fup", fupRateLimit = "5M/10M")

        assertEquals(
            listOf<Pair<String, List<String?>>>(
                "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'" to listOf("plan:p1"),
                "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                    to listOf("plan:p1", "30M/100M"),
                "DELETE FROM radgroupcheck WHERE groupname = ? AND attribute = 'Simultaneous-Use'" to listOf("plan:p1"),
                "INSERT INTO radgroupcheck (groupname, attribute, op, value) VALUES (?, 'Simultaneous-Use', ':=', ?)"
                    to listOf("plan:p1", "1"),
                "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'" to listOf("plan:p1:fup"),
                "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                    to listOf("plan:p1:fup", "5M/10M"),
            ),
            db.executed,
        )
    }

    @Test
    fun `JdbcRadiusWriter syncGroup tanpa batas sesi maupun FUP hanya menulis rate-limit normal`() {
        val db = RecordingDb()
        JdbcRadiusWriter(connect = { db.connection() })
            .syncGroup(target(), "plan:p1", "30M/100M", simultaneousUse = null, fupGroupname = null, fupRateLimit = null)

        assertEquals(
            listOf<Pair<String, List<String?>>>(
                "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'" to listOf("plan:p1"),
                "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                    to listOf("plan:p1", "30M/100M"),
                // Tetap hapus Simultaneous-Use (null = tanpa batas), tapi tak menulis ulang & tak sentuh FUP.
                "DELETE FROM radgroupcheck WHERE groupname = ? AND attribute = 'Simultaneous-Use'" to listOf("plan:p1"),
            ),
            db.executed,
        )
        // Tanpa INSERT Simultaneous-Use, tanpa satu pun statement grup FUP.
        assertFalse(db.executed.any { it.first.startsWith("INSERT INTO radgroupcheck") })
        assertFalse(db.executed.any { it.second.contains("plan:p1:fup") })
    }

    /**
     * Koneksi JDBC perekam berbasis proxy: menangkap tiap (SQL, params) terurut dan
     * transisi transaksi (auto-commit/commit/rollback/close) tanpa basis data sungguhan —
     * cukup untuk menegaskan bentuk SQL & atomisitas yang ditulis [JdbcRadiusWriter].
     */
    private class RecordingDb {
        val executed = mutableListOf<Pair<String, List<String?>>>()
        var committed = false
        var rolledBack = false
        var closed = false
        var autoCommitDuringBody: Boolean? = null
        private var autoCommit = true

        fun connection(): Connection = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args!![0] as String)
                    "setAutoCommit" -> { autoCommit = args!![0] as Boolean; null }
                    "getAutoCommit" -> autoCommit
                    "commit" -> { committed = true; null }
                    "rollback" -> { rolledBack = true; null }
                    "close" -> { closed = true; null }
                    "toString" -> "RecordingConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as Connection

        private fun statement(sql: String): PreparedStatement {
            val params = sortedMapOf<Int, String?>()
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(PreparedStatement::class.java),
                InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "setString" -> { params[args!![0] as Int] = args[1] as String?; null }
                        "executeUpdate" -> {
                            if (autoCommitDuringBody == null) autoCommitDuringBody = autoCommit
                            executed += sql to params.values.toList()
                            0
                        }
                        "close" -> null
                        "toString" -> "RecordingStatement"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> defaultReturn(method.returnType)
                    }
                },
            ) as PreparedStatement
        }

        private fun defaultReturn(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
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
