package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.RadiusSessionReading
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Adapter BRAS nyata untuk tumpukan FreeRADIUS: baca sesi dari tabel akunting SQL,
 * kendalikan sesi lewat RADIUS DAE (RFC 5176) langsung ke BRAS/NAS.
 *
 * Dua jalur sengaja lewat kanal berbeda karena begitulah FreeRADIUS bekerja:
 *  - **baca**: `radacct` (`acctstoptime IS NULL` = masih hidup) via JDBC — octet & durasi
 *    sesegar Interim-Update terakhir yang dikirim NAS; server menghitung laju dari deltanya;
 *  - **kendali**: Disconnect/CoA bukan ke server RADIUS melainkan ke BRAS yang memegang
 *    sesi ([NasTarget.host], port [RadiusDae.DEFAULT_PORT]) memakai [NasTarget.coaSecret].
 *
 * CoA memakai VSA Mikrotik-Rate-Limit — BRAS ber-FreeRADIUS paling lazim di pasar ID
 * adalah MikroTik. NAS vendor lain butuh atribut kecepatan berbeda (perluasan adapter).
 * Disconnect memakai atribut standar sehingga berlaku umum.
 */
class FreeRadiusSqlAdapter(
    private val reader: RadacctReader = JdbcRadacctReader(),
    private val dae: RadiusDaeClient = RadiusDaeClient(),
    private val daePort: Int = RadiusDae.DEFAULT_PORT,
) : BngAdapter {

    override val vendor: String = VENDOR

    private val log = LoggerFactory.getLogger(javaClass)

    override fun pollSessions(target: NasTarget): List<RadiusSessionReading> = reader.activeSessions(target)

    override fun execute(target: NasTarget, action: BngActionCommand) {
        val host = target.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${target.name}: alamat NAS (tujuan DAE) belum diisi")
        val secret = target.coaSecret?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${target.name}: Secret CoA (DAE) belum diisi")
        val session = reader.findActive(target, action.username)
        when (action.kind) {
            BngActionKind.DISCONNECT -> disconnect(target, host, secret, action, session)
            BngActionKind.COA -> changeRate(target, host, secret, action, session)
        }
    }

    private fun disconnect(
        target: NasTarget,
        host: String,
        secret: String,
        action: BngActionCommand,
        session: ActiveSession?,
    ) {
        if (session == null) {
            log.info("FreeRADIUS {}: sesi {} tak ada di radacct — DISCONNECT dianggap selesai", target.name, action.username)
            return
        }
        val attributes = buildList {
            add(RadiusDae.userName(action.username))
            session.acctSessionId?.let { add(RadiusDae.acctSessionId(it)) }
            RadiusDae.nasIpAddress(session.nasIp ?: host)?.let(::add)
        }
        val result = dae.send(host, daePort, secret, RadiusDae.DISCONNECT_REQUEST, identifierFor(action), attributes)
        when (result.code) {
            RadiusDae.DISCONNECT_ACK ->
                log.info("FreeRADIUS {}: memutus sesi {}", target.name, action.username)
            // Sesi hilang antara poll dan DAE — sudah tercapai, jangan gagalkan.
            RadiusDae.DISCONNECT_NAK -> if (result.errorCause == SESSION_NOT_FOUND) {
                log.info("FreeRADIUS {}: sesi {} sudah tak ada di NAS — DISCONNECT selesai", target.name, action.username)
            } else {
                throw IllegalStateException(
                    "Disconnect ${action.username} ditolak BRAS $host: ${RadiusDae.errorCauseLabel(result.errorCause)}",
                )
            }
            else -> throw IllegalStateException("Balasan DAE tak terduga (kode ${result.code}) dari $host")
        }
    }

    private fun changeRate(
        target: NasTarget,
        host: String,
        secret: String,
        action: BngActionCommand,
        session: ActiveSession?,
    ) {
        val down = action.downMbps
        val up = action.upMbps
        require(down != null && up != null) { "CoA butuh downMbps & upMbps" }
        if (session == null) {
            throw IllegalStateException("CoA gagal: sesi ${action.username} tak aktif di ${target.name}")
        }
        val attributes = buildList {
            add(RadiusDae.userName(action.username))
            session.acctSessionId?.let { add(RadiusDae.acctSessionId(it)) }
            add(RadiusDae.mikrotikRateLimit(up, down))
        }
        val result = dae.send(host, daePort, secret, RadiusDae.COA_REQUEST, identifierFor(action), attributes)
        when (result.code) {
            RadiusDae.COA_ACK ->
                log.info("FreeRADIUS {}: CoA {} → {}/{} Mbps", target.name, action.username, down, up)
            RadiusDae.COA_NAK -> throw IllegalStateException(
                "CoA ${action.username} ditolak BRAS $host: ${RadiusDae.errorCauseLabel(result.errorCause)}",
            )
            else -> throw IllegalStateException("Balasan DAE tak terduga (kode ${result.code}) dari $host")
        }
    }

    // Identifier RADIUS diturunkan dari actionId agar kiriman ulang (at-least-once)
    // membawa identifier yang sama — sejalan dengan idempotensi yang dituntut kontrak.
    private fun identifierFor(action: BngActionCommand): Int = action.actionId.hashCode() and 0xFF

    companion object {
        const val VENDOR = "FREERADIUS"
        private const val SESSION_NOT_FOUND = 503
    }
}

/** Identitas sesi hidup yang diperlukan untuk menyasarnya lewat DAE. */
data class ActiveSession(val acctSessionId: String?, val nasIp: String?)

/**
 * Sumber sesi FreeRADIUS. Diabstraksikan agar keputusan adapter (bentuk paket DAE,
 * idempotensi) teruji tanpa basis data, dan agar sumber selain `radacct` bisa menyusul.
 */
interface RadacctReader {
    fun activeSessions(target: NasTarget): List<RadiusSessionReading>

    fun findActive(target: NasTarget, username: String): ActiveSession?
}

/**
 * Membaca `radacct` FreeRADIUS lewat JDBC. Koneksi dibuka sekali pakai per operasi —
 * collector jarang polling (denyut hitungan detik→menit), jadi pooling tak sepadan
 * ruwetnya. Semantik octet mengikuti RADIUS: Acct-Input = unggah (dari pelanggan) →
 * inOctets, Acct-Output = unduh → outOctets, konsisten dengan adapter lain.
 */
class JdbcRadacctReader(
    private val connect: (NasTarget) -> Connection = ::openConnection,
) : RadacctReader {

    override fun activeSessions(target: NasTarget): List<RadiusSessionReading> =
        connect(target).use { conn ->
            conn.prepareStatement(ACTIVE_SESSIONS_SQL).use { st ->
                st.executeQuery().use { rs ->
                    val seen = HashSet<String>()
                    val out = ArrayList<RadiusSessionReading>()
                    // ORDER BY acctstarttime DESC → baris terbaru per username menang;
                    // baris basi (stop yang terlewat) untuk user sama diabaikan.
                    while (rs.next()) {
                        val reading = mapRow(rs, target)
                        if (seen.add(reading.username)) out += reading
                    }
                    out
                }
            }
        }

    override fun findActive(target: NasTarget, username: String): ActiveSession? =
        connect(target).use { conn ->
            conn.prepareStatement(FIND_ACTIVE_SQL).use { st ->
                st.setString(1, username)
                st.executeQuery().use { rs ->
                    if (rs.next()) ActiveSession(rs.getString("acctsessionid"), stripMask(rs.getString("nasipaddress")))
                    else null
                }
            }
        }

    private fun mapRow(rs: ResultSet, target: NasTarget): RadiusSessionReading = RadiusSessionReading(
        username = rs.getString("username"),
        online = true,
        framedIp = stripMask(rs.getString("framedipaddress")),
        nasIp = stripMask(rs.getString("nasipaddress")) ?: target.host,
        sessionId = rs.getString("acctsessionid"),
        callingStationId = rs.getString("callingstationid"),
        uptimeSeconds = rs.getLong("acctsessiontime").takeUnless { rs.wasNull() },
        inOctets = rs.getLong("acctinputoctets").takeUnless { rs.wasNull() },
        outOctets = rs.getLong("acctoutputoctets").takeUnless { rs.wasNull() },
    )

    companion object {
        private val COLUMNS =
            "username, framedipaddress, nasipaddress, acctsessionid, callingstationid, " +
                "acctsessiontime, acctinputoctets, acctoutputoctets"

        private val ACTIVE_SESSIONS_SQL =
            "SELECT $COLUMNS FROM radacct WHERE acctstoptime IS NULL ORDER BY acctstarttime DESC"

        private const val FIND_ACTIVE_SQL =
            "SELECT acctsessionid, nasipaddress FROM radacct " +
                "WHERE acctstoptime IS NULL AND username = ? ORDER BY acctstarttime DESC LIMIT 1"

        /** inet Postgres bisa terbaca "10.0.0.1/32"; BRAS hanya butuh alamatnya. */
        private fun stripMask(value: String?): String? = value?.substringBefore('/')?.takeIf { it.isNotBlank() }

        private fun openConnection(target: NasTarget): Connection {
            val url = target.apiDatabase?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("BRAS ${target.name}: URL JDBC radacct belum diisi")
            return DriverManager.getConnection(url, target.apiUsername, target.apiSecret)
        }
    }
}
