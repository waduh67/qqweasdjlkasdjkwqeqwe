package com.duluin.ftth.bng

import com.duluin.ftth.bng.adapter.outbound.radius.FreeRadiusJdbcAdapter
import com.duluin.ftth.bng.adapter.outbound.radius.RadiusConnectionResolver
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Menguji bentuk SQL yang ditulis [FreeRadiusJdbcAdapter] ke radius-db platform lewat
 * koneksi PEREKAM berbasis proxy (tanpa DB nyata). Yang ditegaskan: rangkaian statement
 * radcheck/radusergroup/radgroupreply/
 * radgroupcheck yang tepat, atomisitas (auto-commit dimatikan lalu commit), dan bahwa
 * username yang DITERIMA adapter dipakai apa adanya (prefix slug urusan pemanggil, bukan
 * adapter — adapter murni SQL, tenancy-agnostik).
 */
class FreeRadiusJdbcAdapterTest {

    private val tenantId: UUID = UuidV7.generate()

    private fun adapter(conn: Connection) = FreeRadiusJdbcAdapter(RecordingConnections(conn))

    @Test
    fun `provision PPPoE menulis radcheck, radusergroup, dan membersihkan reservasi IP dalam satu transaksi`() {
        val db = RecordingDb()
        adapter(db.connection()).provision(tenantId, "acme:budi", "s3cr3t", "plan:p1", framedIp = null)

        assertThat(db.autoCommitDuringBody).isEqualTo(false)
        assertThat(db.committed).isTrue()
        assertThat(db.rolledBack).isFalse()
        assertThat(db.closed).isTrue()
        assertThat(db.executed).containsExactly(
            "DELETE FROM radcheck WHERE username = ? AND attribute = 'Cleartext-Password'" to listOf("acme:budi"),
            "INSERT INTO radcheck (username, attribute, op, value) VALUES (?, 'Cleartext-Password', ':=', ?)"
                to listOf("acme:budi", "s3cr3t"),
            "DELETE FROM radusergroup WHERE username = ?" to listOf("acme:budi"),
            "INSERT INTO radusergroup (username, groupname, priority) VALUES (?, ?, 1)"
                to listOf("acme:budi", "plan:p1"),
            // Reservasi IP dibersihkan tanpa syarat (idempoten); tanpa framedIp tak ada INSERT.
            "DELETE FROM radreply WHERE username = ? AND attribute = 'Framed-IP-Address'" to listOf("acme:budi"),
        )
    }

    @Test
    fun `provision DHCP-Static dengan framedIp menulis reservasi Framed-IP-Address`() {
        val db = RecordingDb()
        // Identitas MAC apa adanya (bukan slug-prefix) — pemanggil yang memutuskan; adapter murni SQL.
        adapter(db.connection()).provision(tenantId, "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF", "plan:p1", framedIp = "100.64.0.10")

        assertThat(db.executed).containsExactly(
            "DELETE FROM radcheck WHERE username = ? AND attribute = 'Cleartext-Password'" to listOf("AA:BB:CC:DD:EE:FF"),
            "INSERT INTO radcheck (username, attribute, op, value) VALUES (?, 'Cleartext-Password', ':=', ?)"
                to listOf("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF"),
            "DELETE FROM radusergroup WHERE username = ?" to listOf("AA:BB:CC:DD:EE:FF"),
            "INSERT INTO radusergroup (username, groupname, priority) VALUES (?, ?, 1)"
                to listOf("AA:BB:CC:DD:EE:FF", "plan:p1"),
            "DELETE FROM radreply WHERE username = ? AND attribute = 'Framed-IP-Address'" to listOf("AA:BB:CC:DD:EE:FF"),
            "INSERT INTO radreply (username, attribute, op, value) VALUES (?, 'Framed-IP-Address', ':=', ?)"
                to listOf("AA:BB:CC:DD:EE:FF", "100.64.0.10"),
        )
        assertThat(db.committed).isTrue()
    }

    @Test
    fun `deprovision menghapus tiga tabel otorisasi akun`() {
        val db = RecordingDb()
        adapter(db.connection()).deprovision(tenantId, "acme:budi")

        assertThat(db.executed).containsExactly(
            "DELETE FROM radcheck WHERE username = ?" to listOf("acme:budi"),
            "DELETE FROM radreply WHERE username = ?" to listOf("acme:budi"),
            "DELETE FROM radusergroup WHERE username = ?" to listOf("acme:budi"),
        )
        assertThat(db.committed).isTrue()
    }

    @Test
    fun `syncGroup menulis rate-limit, Simultaneous-Use, dan grup FUP`() {
        val db = RecordingDb()
        adapter(db.connection()).syncGroup(
            tenantId, "plan:p1", "30M/100M", simultaneousUse = 1, fupGroupname = "plan:p1:fup", fupRateLimit = "5M/10M",
        )

        assertThat(db.executed).containsExactly(
            "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'" to listOf("plan:p1"),
            "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                to listOf("plan:p1", "30M/100M"),
            "DELETE FROM radgroupcheck WHERE groupname = ? AND attribute = 'Simultaneous-Use'" to listOf("plan:p1"),
            "INSERT INTO radgroupcheck (groupname, attribute, op, value) VALUES (?, 'Simultaneous-Use', ':=', ?)"
                to listOf("plan:p1", "1"),
            "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'" to listOf("plan:p1:fup"),
            "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                to listOf("plan:p1:fup", "5M/10M"),
        )
        assertThat(db.committed).isTrue()
    }

    @Test
    fun `syncGroup tanpa batas sesi maupun FUP hanya menulis rate-limit normal`() {
        val db = RecordingDb()
        adapter(db.connection()).syncGroup(
            tenantId, "plan:p1", "30M/100M", simultaneousUse = null, fupGroupname = null, fupRateLimit = null,
        )

        assertThat(db.executed).containsExactly(
            "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'" to listOf("plan:p1"),
            "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                to listOf("plan:p1", "30M/100M"),
            // Tetap hapus Simultaneous-Use (null = tanpa batas), tapi tak menulis ulang & tak sentuh FUP.
            "DELETE FROM radgroupcheck WHERE groupname = ? AND attribute = 'Simultaneous-Use'" to listOf("plan:p1"),
        )
        assertThat(db.executed.any { it.first.startsWith("INSERT INTO radgroupcheck") }).isFalse()
        assertThat(db.executed.any { it.second.contains("plan:p1:fup") }).isFalse()
    }

    @Test
    fun `isConfigured meneruskan status resolver`() {
        assertThat(adapter(RecordingDb().connection()).isConfigured()).isTrue()
    }

    /**
     * Resolver koneksi tiruan: selalu "dikonfigurasi" dan mengembalikan koneksi perekam.
     * Konstruktor induk dipanggil dengan properti kosong → tak membangun pool Hikari (url
     * kosong), lalu kedua anggota di-override. Bisa disubclass karena plugin kotlin-spring
     * membuat @Component & anggotanya `open`.
     */
    private class RecordingConnections(private val conn: Connection) :
        RadiusConnectionResolver(RadiusProperties()) {
        override val configured: Boolean get() = true
        override fun connectionFor(tenantId: UUID): Connection = conn
    }

    /**
     * Koneksi JDBC perekam berbasis proxy: menangkap tiap (SQL, params) terurut dan transisi
     * transaksi (auto-commit/commit/rollback/close) tanpa basis data sungguhan — cukup untuk
     * menegaskan bentuk SQL & atomisitas yang ditulis [FreeRadiusJdbcAdapter].
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
}
