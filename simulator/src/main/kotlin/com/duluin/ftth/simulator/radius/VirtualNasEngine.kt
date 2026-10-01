package com.duluin.ftth.simulator.radius

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.math.abs
import kotlin.math.sin

/**
 * Virtual-NAS: sumber baris `radacct` yang app baca, sekaligus [NasSessionControl] untuk DAE.
 *
 * Tiap denyut [reconcile]:
 *  1. Baca user ber-otorisasi = `radcheck.username` ber-`Cleartext-Password` (PPPoE ber-prefix
 *     `slug:`, akun MAC polos — keduanya ditulis provisioning app).
 *  2. Untuk sesi hidup milik user yang masih ter-otorisasi → tumbuhkan octet (meniru trafik).
 *     Milik user yang otorisasinya dicabut (deprovision) → tutup.
 *  3. Untuk user ter-otorisasi yang belum punya sesi & tak dalam jeda dial-ulang → buka sesi
 *     (meniru CPE dial-in).
 *
 * Sumber kebenaran octet = DB (octet kumulatif dibaca-tulis tiap denyut), jadi engine nyaris
 * stateless; satu-satunya state memori adalah jeda dial-ulang pasca-Disconnect.
 */
class VirtualNasEngine(
    private val dataSource: DataSource,
    private val props: RadiusSimProperties,
) : NasSessionControl {

    private val log = LoggerFactory.getLogger(javaClass)

    /** username → jangan sambungkan lagi sebelum waktu ini (diisi saat Disconnect). */
    private val holdDown = ConcurrentHashMap<String, Instant>()

    @Synchronized
    fun reconcile(now: Instant) {
        dataSource.connection.use { conn ->
            val authorized = authorizedUsers(conn)
            val open = openSessions(conn)

            for (session in open) {
                if (session.username in authorized) grow(conn, session)
                else close(conn, session.acctUniqueId, "User-Request") // otorisasi dicabut
            }

            val onlineUsers = open.mapTo(HashSet()) { it.username }
            for (user in authorized) {
                if (user in onlineUsers) continue
                val until = holdDown[user]
                if (until != null && until.isAfter(now)) continue
                holdDown.remove(user)
                create(conn, user, now)
            }
        }
    }

    // ---- NasSessionControl (dipanggil DaeResponder) ----

    @Synchronized
    override fun disconnect(username: String, acctSessionId: String?): Boolean =
        dataSource.connection.use { conn ->
            val scopedUsername = resolveSessionUsername(conn, username, acctSessionId) ?: return false
            val closed = closeOpen(conn, scopedUsername, acctSessionId, "Admin-Reset")
            if (closed > 0) holdDown[scopedUsername] = Instant.now().plus(props.reconnectAfter)
            closed > 0
        }

    override fun changeRate(username: String, acctSessionId: String?): Boolean =
        dataSource.connection.use { conn -> resolveSessionUsername(conn, username, acctSessionId) != null }

    // ---- Jalur baca ----

    private fun authorizedUsers(conn: Connection): Set<String> =
        conn.prepareStatement(SQL_AUTHORIZED).use { st ->
            st.executeQuery().use { rs ->
                val out = HashSet<String>()
                while (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() }?.let(out::add)
                out
            }
        }

    private fun openSessions(conn: Connection): List<OpenSession> =
        conn.prepareStatement(SQL_OPEN_SESSIONS).use { st ->
            st.executeQuery().use { rs ->
                val out = ArrayList<OpenSession>()
                val seen = HashSet<String>()
                // ORDER BY acctstarttime DESC → hanya sesi terbaru per user diproses; duplikat
                // basi (jika ada) dibiarkan apa adanya, tak digandakan.
                while (rs.next()) {
                    val username = rs.getString("username") ?: continue
                    if (!seen.add(username)) continue
                    out += OpenSession(
                        username = username,
                        acctUniqueId = rs.getString("acctuniqueid"),
                        inOctets = cumulative(rs.getLong("acctinputoctets"), rs.getLong("acctinputgigawords")),
                        outOctets = cumulative(rs.getLong("acctoutputoctets"), rs.getLong("acctoutputgigawords")),
                    )
                }
                out
            }
        }

    /** RADIUS stores tenant:username; DAE carries the NAS-facing username and session ID.
     * Reject ambiguous matches and never control a session belonging to another NAS. */
    private fun resolveSessionUsername(conn: Connection, username: String, sessionId: String?): String? =
        conn.prepareStatement(SQL_RESOLVE_SESSION).use { st ->
            st.setString(1, props.nasIp)
            st.setString(2, username)
            st.setString(3, username)
            st.setString(4, sessionId)
            st.setString(5, sessionId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                val scoped = rs.getString(1)
                if (rs.next()) null else scoped
            }
        }

    // ---- Jalur tulis ----

    private fun create(conn: Connection, username: String, now: Instant) {
        val rate = rateFor(username)
        // Sebagian user "sudah lama online" → mulai di atas 2³² agar jalur gigawords app teruji
        // sejak poll pertama, bukan menunggu ~10 menit sesi mencapai 4 GB.
        val outBase = if (abs(username.hashCode()) % 3 == 0) OCTETS_PER_GIGAWORD + 3_000_000_000L else 0L
        val inBase = outBase / 4
        conn.prepareStatement(SQL_CREATE).use { st ->
            st.setString(1, UUID.randomUUID().toString().replace("-", "").take(16)) // acctsessionid
            st.setString(2, UUID.randomUUID().toString()) // acctuniqueid (UNIQUE)
            st.setString(3, username)
            st.setString(4, props.nasIp)
            st.setLong(5, low32(inBase)); st.setLong(6, gigawords(inBase))
            st.setLong(7, low32(outBase)); st.setLong(8, gigawords(outBase))
            st.setString(9, callingStationId(username))
            st.setString(10, framedIp(username))
            st.executeUpdate()
        }
        log.debug("Sesi virtual-NAS dibuka untuk {} ({}↓/{}↑ Mbps)", username, rate.downMbps, rate.upMbps)
    }

    private fun grow(conn: Connection, session: OpenSession) {
        val rate = rateFor(session.username)
        val secs = props.tickInterval.seconds.toDouble()
        // Variasi ±40% berayun pelan agar grafik hidup, bukan garis lurus.
        val wave = 1.0 + 0.4 * sin(Instant.now().epochSecond / 300.0 + session.username.hashCode())
        val inNext = session.inOctets + bytesFor(rate.upMbps, secs, wave)
        val outNext = session.outOctets + bytesFor(rate.downMbps, secs, wave)
        conn.prepareStatement(SQL_GROW).use { st ->
            st.setLong(1, low32(inNext)); st.setLong(2, gigawords(inNext))
            st.setLong(3, low32(outNext)); st.setLong(4, gigawords(outNext))
            st.setString(5, session.acctUniqueId)
            st.executeUpdate()
        }
    }

    private fun close(conn: Connection, acctUniqueId: String, cause: String): Int =
        conn.prepareStatement(SQL_CLOSE_BY_ID).use { st ->
            st.setString(1, cause)
            st.setString(2, acctUniqueId)
            st.executeUpdate()
        }

    private fun closeOpen(conn: Connection, username: String, acctSessionId: String?, cause: String): Int =
        conn.prepareStatement(SQL_CLOSE_OPEN).use { st ->
            st.setString(1, cause)
            st.setString(2, username)
            st.setString(3, acctSessionId) // null → cabang IS NULL menyapu semua sesi user
            st.setString(4, acctSessionId)
            st.setString(5, props.nasIp)
            st.executeUpdate()
        }

    // ---- Sintesis nilai (deterministik per-username agar stabil antar-denyut) ----

    private data class OpenSession(val username: String, val acctUniqueId: String, val inOctets: Long, val outOctets: Long)

    private data class Rate(val downMbps: Int, val upMbps: Int)

    private fun rateFor(username: String): Rate {
        val h = abs(username.hashCode())
        return Rate(downMbps = 10 + h % 51, upMbps = 2 + (h / 7) % 11) // 10..60 ↓, 2..12 ↑
    }

    private fun bytesFor(mbps: Int, seconds: Double, wave: Double): Long =
        (mbps * 1_000_000.0 / 8.0 * seconds * wave).toLong().coerceAtLeast(0L)

    private fun framedIp(username: String): String {
        val h = abs(username.hashCode())
        return "10.66.${(h ushr 8) and 0xFF}.${1 + h % 254}"
    }

    private fun callingStationId(username: String): String {
        val h = abs(username.hashCode())
        return "AA:BB:%02X:%02X:%02X:%02X".format((h ushr 24) and 0xFF, (h ushr 16) and 0xFF, (h ushr 8) and 0xFF, h and 0xFF)
    }

    private fun cumulative(low: Long, gig: Long): Long = low + gig * OCTETS_PER_GIGAWORD
    private fun low32(cumulative: Long): Long = cumulative % OCTETS_PER_GIGAWORD
    private fun gigawords(cumulative: Long): Long = cumulative / OCTETS_PER_GIGAWORD

    companion object {
        private const val OCTETS_PER_GIGAWORD = 1L shl 32

        private const val SQL_AUTHORIZED =
            "SELECT DISTINCT username FROM radcheck WHERE attribute = 'Cleartext-Password'"

        private const val SQL_OPEN_SESSIONS =
            "SELECT username, acctuniqueid, acctinputoctets, acctinputgigawords, " +
                "acctoutputoctets, acctoutputgigawords FROM radacct " +
                "WHERE acctstoptime IS NULL ORDER BY acctstarttime DESC"

        private const val SQL_RESOLVE_SESSION =
            "SELECT DISTINCT username FROM radacct WHERE acctstoptime IS NULL AND nasipaddress = ?::inet " +
                "AND (username = ? OR substring(username from position(':' in username) + 1) = ?) " +
                "AND (CAST(? AS text) IS NULL OR acctsessionid = ?) LIMIT 2"

        private const val SQL_CREATE =
            "INSERT INTO radacct (acctsessionid, acctuniqueid, username, nasipaddress, nasporttype, " +
                "acctstarttime, acctupdatetime, acctsessiontime, " +
                "acctinputoctets, acctinputgigawords, acctoutputoctets, acctoutputgigawords, " +
                "callingstationid, framedipaddress, servicetype, framedprotocol) " +
                "VALUES (?, ?, ?, ?::inet, 'Ethernet', now(), now(), 0, ?, ?, ?, ?, ?, ?::inet, 'Framed-User', 'PPP')"

        private const val SQL_GROW =
            "UPDATE radacct SET acctinputoctets = ?, acctinputgigawords = ?, " +
                "acctoutputoctets = ?, acctoutputgigawords = ?, " +
                "acctsessiontime = EXTRACT(EPOCH FROM (now() - acctstarttime))::bigint, acctupdatetime = now() " +
                "WHERE acctuniqueid = ?"

        private const val SQL_CLOSE_BY_ID =
            "UPDATE radacct SET acctstoptime = now(), acctterminatecause = ? WHERE acctuniqueid = ? AND acctstoptime IS NULL"

        private const val SQL_CLOSE_OPEN =
            "UPDATE radacct SET acctstoptime = now(), acctterminatecause = ? " +
                "WHERE acctstoptime IS NULL AND username = ? AND (CAST(? AS text) IS NULL OR acctsessionid = ?) " +
                "AND nasipaddress = ?::inet"
    }
}
