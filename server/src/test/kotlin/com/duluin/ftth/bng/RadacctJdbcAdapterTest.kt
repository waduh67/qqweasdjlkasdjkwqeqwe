package com.duluin.ftth.bng

import com.duluin.ftth.bng.adapter.outbound.radius.RadacctJdbcAdapter
import com.duluin.ftth.bng.adapter.outbound.radius.RadiusConnectionResolver
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * Menguji pembaca `radacct` server-side ([RadacctJdbcAdapter]) lewat koneksi PEREKAM
 * berbasis proxy (tanpa DB nyata). Yang ditegaskan: query menyaring per kode tenant
 * (`username LIKE 'acme:%'`, `acctstoptime IS NULL`), prefiks tenant DIKUPAS kembali ke
 * username bare, `nasipaddress`/`framedipaddress` bertopeng `/32` dibersihkan, baris basi
 * per akun (username sama) di-dedup, dan koneksi ditutup.
 */
class RadacctJdbcAdapterTest {

    private val tenantId: UUID = UuidV7.generate()

    private fun adapter(conn: Connection) = RadacctJdbcAdapter(RecordingConnections(conn))

    @Test
    fun `menyaring per kode tenant, mengupas prefiks, membersihkan topeng, dan dedup`() {
        val db = RecordingDb(
            rows = listOf(
                row("acme:budi", nasIp = "10.0.0.1/32", framedIp = "100.64.0.5/32", sessionId = "s-budi", uptime = 3600, up = 111, down = 222),
                row("acme:andi", nasIp = "10.0.0.1", framedIp = null, sessionId = "s-andi", uptime = null, up = null, down = null),
                // Baris basi untuk budi (acctstarttime lebih lama) → dibuang oleh dedup.
                row("acme:budi", nasIp = "10.0.0.9/32", framedIp = "100.64.0.99/32", sessionId = "s-budi-old", uptime = 10, up = 1, down = 2),
            ),
        )

        val out = adapter(db.connection()).activeSessions(tenantId, "acme")

        // Satu statement, disaring per prefiks tenant.
        assertThat(db.sql).isEqualTo(
            "SELECT username, framedipaddress, nasipaddress, acctsessionid, callingstationid, " +
                "acctsessiontime, acctinputoctets, acctoutputoctets FROM radacct " +
                "WHERE acctstoptime IS NULL AND username LIKE ? ORDER BY acctstarttime DESC",
        )
        assertThat(db.params).containsExactly("acme:%")
        assertThat(db.closed).isTrue()

        // budi menang (baris pertama), andi ikut; kedua username sudah BARE.
        assertThat(out.map { it.username }).containsExactly("budi", "andi")

        val budi = out.first { it.username == "budi" }
        assertThat(budi.online).isTrue()
        assertThat(budi.nasIp).isEqualTo("10.0.0.1")
        assertThat(budi.framedIp).isEqualTo("100.64.0.5")
        assertThat(budi.sessionId).isEqualTo("s-budi")
        assertThat(budi.uptimeSeconds).isEqualTo(3600)
        assertThat(budi.inOctets).isEqualTo(111)
        assertThat(budi.outOctets).isEqualTo(222)

        // Kolom NULL → null (bukan 0/kosong).
        val andi = out.first { it.username == "andi" }
        assertThat(andi.framedIp).isNull()
        assertThat(andi.uptimeSeconds).isNull()
        assertThat(andi.inOctets).isNull()
        assertThat(andi.outOctets).isNull()
    }

    @Test
    fun `isConfigured meneruskan status resolver`() {
        assertThat(adapter(RecordingDb().connection()).isConfigured()).isTrue()
    }

    private fun row(
        username: String,
        nasIp: String?,
        framedIp: String?,
        sessionId: String?,
        uptime: Long?,
        up: Long?,
        down: Long?,
    ): Map<String, Any?> = mapOf(
        "username" to username,
        "nasipaddress" to nasIp,
        "framedipaddress" to framedIp,
        "acctsessionid" to sessionId,
        "callingstationid" to null,
        "acctsessiontime" to uptime,
        "acctinputoctets" to up,
        "acctoutputoctets" to down,
    )

    /** Resolver koneksi tiruan (lihat catatan di [FreeRadiusJdbcAdapterTest]). */
    private class RecordingConnections(private val conn: Connection) :
        RadiusConnectionResolver(RadiusProperties()) {
        override val configured: Boolean get() = true
        override fun connectionFor(tenantId: UUID): Connection = conn
    }

    /**
     * Koneksi JDBC perekam berbasis proxy yang MEMBALIKKAN baris kalengan dari `executeQuery`,
     * menangkap SQL + params, dan mengungkap penutupan — cukup untuk menegaskan bentuk query
     * & pemetaan baris [RadacctJdbcAdapter] tanpa basis data.
     */
    private class RecordingDb(private val rows: List<Map<String, Any?>> = emptyList()) {
        var sql: String? = null
        val params = mutableListOf<String?>()
        var closed = false

        fun connection(): Connection = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args!![0] as String)
                    "close" -> { closed = true; null }
                    "toString" -> "RecordingConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as Connection

        private fun statement(sql: String): PreparedStatement {
            this.sql = sql
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(PreparedStatement::class.java),
                InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "setString" -> { params += args!![1] as String?; null }
                        "executeQuery" -> resultSet()
                        "close" -> null
                        "toString" -> "RecordingStatement"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> defaultReturn(method.returnType)
                    }
                },
            ) as PreparedStatement
        }

        private fun resultSet(): ResultSet {
            var idx = -1
            var lastWasNull = false
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(ResultSet::class.java),
                InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "next" -> { idx++; idx < rows.size }
                        "getString" -> rows[idx][args!![0] as String].also { lastWasNull = it == null }
                        "getLong" -> {
                            val v = rows[idx][args!![0] as String]
                            lastWasNull = v == null
                            (v as? Long) ?: 0L
                        }
                        "wasNull" -> lastWasNull
                        "close" -> null
                        "toString" -> "RecordingResultSet"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> defaultReturn(method.returnType)
                    }
                },
            ) as ResultSet
        }

        private fun defaultReturn(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    }
}
