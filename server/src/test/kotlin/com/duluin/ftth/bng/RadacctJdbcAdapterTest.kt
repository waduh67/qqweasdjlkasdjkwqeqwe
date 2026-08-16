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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Menguji pembaca `radacct` server-side ([RadacctJdbcAdapter]) lewat koneksi PEREKAM
 * berbasis proxy (tanpa DB nyata). Yang ditegaskan: query menyaring per kode tenant
 * (`username LIKE 'acme:%'`, `acctstoptime IS NULL`) sekaligus membuang baris bangkai yang
 * interim-update-nya berhenti, prefiks tenant DIKUPAS kembali ke username bare,
 * `nasipaddress`/`framedipaddress` bertopeng `/32` dibersihkan, baris basi per akun
 * (username sama) di-dedup, dan koneksi ditutup.
 */
class RadacctJdbcAdapterTest {

    private val tenantId: UUID = UuidV7.generate()

    private fun adapter(conn: Connection) =
        RadacctJdbcAdapter(RecordingConnections(conn), INTERIM_STALE_AFTER)

    @Test
    fun `menyaring per kode tenant, mengupas prefiks, membersihkan topeng, dan dedup`() {
        val db = RecordingDb(
            rows = listOf(
                row("acme:budi", nasIp = "10.0.0.1/32", framedIp = "100.64.0.5/32", sessionId = "s-budi", uptime = 3600, up = 111, down = 222, updatedAt = INTERIM_AT),
                row("acme:andi", nasIp = "10.0.0.1", framedIp = null, sessionId = "s-andi", uptime = null, up = null, down = null),
                // Baris basi untuk budi (acctstarttime lebih lama) → dibuang oleh dedup.
                row("acme:budi", nasIp = "10.0.0.9/32", framedIp = "100.64.0.99/32", sessionId = "s-budi-old", uptime = 10, up = 1, down = 2),
            ),
        )

        val out = adapter(db.connection()).activeSessions(tenantId, "acme")

        // Satu statement, disaring per prefiks tenant + daftar MAC (di sini kosong) + buang
        // bangkai (baris yang interim-update-nya sudah berhenti).
        assertThat(db.sql).isEqualTo(
            "SELECT username, framedipaddress, nasipaddress, acctsessionid, callingstationid, " +
                "acctsessiontime, acctinputoctets, acctoutputoctets, acctinputgigawords, acctoutputgigawords, " +
                "acctupdatetime " +
                "FROM radacct WHERE acctstoptime IS NULL " +
                "AND (username LIKE ? OR username = ANY(?)) " +
                "AND (acctupdatetime IS NULL OR acctupdatetime <= acctstarttime OR acctupdatetime >= ?) " +
                "ORDER BY acctstarttime DESC",
        )
        assertThat(db.params).containsExactly("acme:%")
        assertThat(db.arrayElements).isEmpty()
        assertThat(db.closed).isTrue()

        // Ambang bangkai diikat sebagai "sekarang − interimStaleAfter"; router yang tak
        // memasang interim-update tak ikut kena (cabang acctupdatetime <= acctstarttime).
        val cutoff = db.timestamps.single().toInstant()
        assertThat(cutoff).isBetween(
            Instant.now().minus(INTERIM_STALE_AFTER).minusSeconds(60),
            Instant.now().minus(INTERIM_STALE_AFTER).plusSeconds(60),
        )

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
        // Penghitung dicap waktu NAS (acctupdatetime), bukan waktu baca — dasar laju Mbps
        // yang benar walau interim-update jauh lebih jarang daripada periode poll.
        assertThat(budi.countersAt).isEqualTo(INTERIM_AT)

        // Kolom NULL → null (bukan 0/kosong).
        val andi = out.first { it.username == "andi" }
        assertThat(andi.framedIp).isNull()
        assertThat(andi.uptimeSeconds).isNull()
        assertThat(andi.inOctets).isNull()
        assertThat(andi.outOctets).isNull()
        // Router tanpa interim-update tak punya waktu penghitung → penyerap jatuh ke waktu baca.
        assertThat(andi.countersAt).isNull()
    }

    @Test
    fun `menyertakan akun MAC lewat ANY dan menjumlah gigawords`() {
        val db = RecordingDb(
            rows = listOf(
                // Akun MAC ditulis POLOS (tanpa prefiks) — tertangkap lewat daftar = ANY(?).
                // Octet melewati batas 32-bit: nilai sebenarnya = octet bawah + gigawords × 2³².
                row("aa:bb:cc:dd:ee:ff", up = 5, down = 7, upGig = 2, downGig = 3),
            ),
        )

        val out = adapter(db.connection()).activeSessions(tenantId, "acme", listOf("aa:bb:cc:dd:ee:ff"))

        // Daftar MAC diikat sebagai array param kedua untuk cabang `username = ANY(?)`.
        assertThat(db.arrayElements).containsExactly("aa:bb:cc:dd:ee:ff")

        val mac = out.single()
        // Username MAC polos → tak ada prefiks tenant yang dikupas.
        assertThat(mac.username).isEqualTo("aa:bb:cc:dd:ee:ff")
        // Octet kumulatif sebenarnya menjumlah gigawords (anti under-report sesi high-volume).
        assertThat(mac.inOctets).isEqualTo(5 + 2 * (1L shl 32))
        assertThat(mac.outOctets).isEqualTo(7 + 3 * (1L shl 32))
    }

    @Test
    fun `isConfigured meneruskan status resolver`() {
        assertThat(adapter(RecordingDb().connection()).isConfigured()).isTrue()
    }

    private fun row(
        username: String,
        nasIp: String? = null,
        framedIp: String? = null,
        sessionId: String? = null,
        uptime: Long? = null,
        up: Long? = null,
        down: Long? = null,
        upGig: Long? = null,
        downGig: Long? = null,
        updatedAt: Instant? = null,
    ): Map<String, Any?> = mapOf(
        "username" to username,
        "nasipaddress" to nasIp,
        "framedipaddress" to framedIp,
        "acctsessionid" to sessionId,
        "callingstationid" to null,
        "acctsessiontime" to uptime,
        "acctinputoctets" to up,
        "acctoutputoctets" to down,
        "acctinputgigawords" to upGig,
        "acctoutputgigawords" to downGig,
        "acctupdatetime" to updatedAt,
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
        val timestamps = mutableListOf<java.sql.Timestamp>()
        var arrayElements: List<Any?> = emptyList()
        var closed = false

        fun connection(): Connection = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "prepareStatement" -> statement(args!![0] as String)
                    // createArrayOf(typeName, elements) → tangkap elemen array MAC untuk asersi.
                    "createArrayOf" -> {
                        arrayElements = (args!![1] as Array<*>).toList()
                        sqlArray()
                    }
                    "close" -> { closed = true; null }
                    "toString" -> "RecordingConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as Connection

        private fun sqlArray(): java.sql.Array = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(java.sql.Array::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "toString" -> "RecordingArray"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> defaultReturn(method.returnType)
                }
            },
        ) as java.sql.Array

        private fun statement(sql: String): PreparedStatement {
            this.sql = sql
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(PreparedStatement::class.java),
                InvocationHandler { proxy, method, args ->
                    when (method.name) {
                        "setString" -> { params += args!![1] as String?; null }
                        "setTimestamp" -> { timestamps += args!![1] as java.sql.Timestamp; null }
                        // Array MAC sudah ditangkap saat createArrayOf; setArray cukup diabaikan.
                        "setArray" -> null
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
                        "getTimestamp" -> (rows[idx][args!![0] as String] as? Instant)
                            ?.let { java.sql.Timestamp.from(it) }
                            .also { lastWasNull = it == null }
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

    private companion object {
        /** Sama dengan bawaan `ftth.radius.acct-interim-stale-after`. */
        val INTERIM_STALE_AFTER: Duration = Duration.ofHours(1)

        /** Waktu Interim-Update terakhir menurut NAS — dibulatkan ke detik seperti `timestamptz`. */
        val INTERIM_AT: Instant = Instant.parse("2026-08-16T16:05:00Z")
    }
}
