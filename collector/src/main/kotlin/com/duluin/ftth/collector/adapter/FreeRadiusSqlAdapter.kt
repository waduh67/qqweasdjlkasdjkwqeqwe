package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.RadiusSessionReading
import com.duluin.ftth.contract.radius.RadiusDae
import com.duluin.ftth.contract.radius.RadiusDaeClient
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Adapter BRAS nyata untuk tumpukan FreeRADIUS: baca sesi dari tabel akunting SQL,
 * kendalikan sesi lewat RADIUS DAE (RFC 5176), dan provision otorisasi lewat SQL.
 *
 * TIGA jalur lewat kanal berbeda karena begitulah FreeRADIUS bekerja:
 *  - **baca**: `radacct` (`acctstoptime IS NULL` = masih hidup) via JDBC — octet & durasi
 *    sesegar Interim-Update terakhir yang dikirim NAS; server menghitung laju dari deltanya;
 *  - **kendali sesi hidup** (DAE): Disconnect/CoA bukan ke server RADIUS melainkan ke BRAS
 *    yang memegang sesi ([NasTarget.host], port [RadiusDae.DEFAULT_PORT]) memakai
 *    [NasTarget.coaSecret];
 *  - **provisioning otorisasi** (SQL): PROVISION/DEPROVISION/SYNC_GROUP menulis tabel
 *    `radcheck`/`radusergroup`/`radgroupreply`/`radgroupcheck` lewat JDBC ([NasTarget.apiDatabase]) —
 *    inilah "RADIUS jadi pusat": paket = satu grup, akun cukup diikutkan ke grupnya.
 *    Tak butuh Secret CoA (bukan DAE), jadi jalur ini jalan meski coaSecret kosong.
 *
 * CoA & grup memakai VSA Mikrotik-Rate-Limit — BRAS ber-FreeRADIUS paling lazim di pasar ID
 * adalah MikroTik. NAS vendor lain butuh atribut kecepatan berbeda (perluasan adapter).
 * Disconnect memakai atribut standar sehingga berlaku umum.
 */
class FreeRadiusSqlAdapter(
    private val reader: RadacctReader = JdbcRadacctReader(),
    private val writer: RadiusWriter = JdbcRadiusWriter(),
    private val dae: RadiusDaeClient = RadiusDaeClient(),
    private val daePort: Int = RadiusDae.DEFAULT_PORT,
) : BngAdapter {

    override val vendor: String = VENDOR

    private val log = LoggerFactory.getLogger(javaClass)

    override fun pollSessions(target: NasTarget): List<RadiusSessionReading> = reader.activeSessions(target)

    override fun execute(target: NasTarget, action: BngActionCommand) {
        when (action.kind) {
            // Jalur DAE (butuh host + Secret CoA + sesi hidup).
            BngActionKind.DISCONNECT -> withDae(target, action) { host, secret, session ->
                disconnect(target, host, secret, action, session)
            }
            BngActionKind.COA -> withDae(target, action) { host, secret, session ->
                changeRate(target, host, secret, action, session)
            }
            // Jalur SQL (butuh URL JDBC apiDatabase; tak butuh Secret CoA).
            BngActionKind.PROVISION -> writer.provision(
                target, action.username, requirePassword(target, action), requireGroup(target, action),
            )
            BngActionKind.DEPROVISION -> writer.deprovision(target, action.username)
            BngActionKind.SYNC_GROUP -> writer.syncGroup(
                target,
                requireGroup(target, action),
                requireRateLimit(target, action),
                action.simultaneousUse,
                action.fupGroupname,
                action.fupRateLimit,
            )
        }
    }

    /** Menyiapkan kanal DAE (host + Secret CoA) lalu meresolusi sesi hidup sekali untuk perintah. */
    private inline fun withDae(
        target: NasTarget,
        action: BngActionCommand,
        body: (host: String, secret: String, session: ActiveSession?) -> Unit,
    ) {
        val host = target.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${target.name}: alamat NAS (tujuan DAE) belum diisi")
        val secret = target.coaSecret?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("BRAS ${target.name}: Secret CoA (DAE) belum diisi")
        body(host, secret, reader.findActive(target, action.username))
    }

    private fun requirePassword(target: NasTarget, action: BngActionCommand): String =
        action.password?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("PROVISION ${action.username} di ${target.name}: password akun tak terbawa")

    private fun requireGroup(target: NasTarget, action: BngActionCommand): String =
        action.groupname?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("${action.kind} di ${target.name}: nama grup paket tak terbawa")

    private fun requireRateLimit(target: NasTarget, action: BngActionCommand): String =
        action.rateLimit?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("SYNC_GROUP di ${target.name}: rate-limit grup tak terbawa")

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
    private val connect: (NasTarget) -> Connection = ::openRadiusConnection,
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
    }
}

/**
 * Membuka koneksi JDBC ke basis data FreeRADIUS sebuah BRAS dari [NasTarget.apiDatabase]
 * (URL JDBC) + kredensialnya. Dipakai bersama pembaca `radacct` dan penulis otorisasi —
 * keduanya menembak DB SQL FreeRADIUS yang sama.
 */
internal fun openRadiusConnection(target: NasTarget): Connection {
    val url = target.apiDatabase?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("BRAS ${target.name}: URL JDBC FreeRADIUS belum diisi")
    return DriverManager.getConnection(url, target.apiUsername, target.apiSecret)
}

/**
 * Penulis otorisasi FreeRADIUS. Diabstraksikan agar bentuk SQL yang ditulis adapter
 * (radcheck/radusergroup/radgroupreply/radgroupcheck) teruji tanpa basis data.
 *
 * Semua operasi IDEMPOTEN (DELETE-lalu-INSERT dalam satu transaksi): perintah dikirim
 * ulang tiap denyut sampai di-ACK, jadi menjalankannya dua kali harus menghasilkan
 * keadaan yang sama.
 */
interface RadiusWriter {
    /** Tulis kredensial akun (radcheck Cleartext-Password) + keanggotaan grup (radusergroup). */
    fun provision(target: NasTarget, username: String, password: String, groupname: String)

    /** Hapus seluruh baris otorisasi akun (radcheck/radreply/radusergroup by username). */
    fun deprovision(target: NasTarget, username: String)

    /**
     * Setel atribut grup paket: rate-limit normal (radgroupreply Mikrotik-Rate-Limit),
     * batas sesi ([simultaneousUse] → radgroupcheck Simultaneous-Use, dihapus bila null),
     * dan — bila FUP aktif — grup throttle kedua ([fupGroupname]/[fupRateLimit]).
     */
    fun syncGroup(
        target: NasTarget,
        groupname: String,
        rateLimit: String,
        simultaneousUse: Int?,
        fupGroupname: String?,
        fupRateLimit: String?,
    )
}

/**
 * Menulis tabel otorisasi FreeRADIUS lewat JDBC. Koneksi dibuka sekali per operasi
 * (collector jarang provision), tiap operasi satu transaksi eksplisit agar sekumpulan
 * baris (kredensial + grup) tampil atomik ke FreeRADIUS — auth tak pernah melihat akun
 * separuh-terpasang.
 */
class JdbcRadiusWriter(
    private val connect: (NasTarget) -> Connection = ::openRadiusConnection,
) : RadiusWriter {

    override fun provision(target: NasTarget, username: String, password: String, groupname: String) =
        inTransaction(target) { conn ->
            conn.replace(
                "DELETE FROM radcheck WHERE username = ? AND attribute = 'Cleartext-Password'" to listOf(username),
                "INSERT INTO radcheck (username, attribute, op, value) VALUES (?, 'Cleartext-Password', ':=', ?)"
                    to listOf(username, password),
                "DELETE FROM radusergroup WHERE username = ?" to listOf(username),
                "INSERT INTO radusergroup (username, groupname, priority) VALUES (?, ?, 1)"
                    to listOf(username, groupname),
            )
        }

    override fun deprovision(target: NasTarget, username: String) =
        inTransaction(target) { conn ->
            conn.replace(
                "DELETE FROM radcheck WHERE username = ?" to listOf(username),
                "DELETE FROM radreply WHERE username = ?" to listOf(username),
                "DELETE FROM radusergroup WHERE username = ?" to listOf(username),
            )
        }

    override fun syncGroup(
        target: NasTarget,
        groupname: String,
        rateLimit: String,
        simultaneousUse: Int?,
        fupGroupname: String?,
        fupRateLimit: String?,
    ) = inTransaction(target) { conn ->
        // Rate-limit grup normal.
        conn.replace(
            "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'" to listOf(groupname),
            "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                to listOf(groupname, rateLimit),
        )
        // Batas sesi simultan: hapus dulu, tulis ulang hanya bila diminta (null = tanpa batas).
        conn.replace("DELETE FROM radgroupcheck WHERE groupname = ? AND attribute = 'Simultaneous-Use'" to listOf(groupname))
        if (simultaneousUse != null) {
            conn.replace(
                "INSERT INTO radgroupcheck (groupname, attribute, op, value) VALUES (?, 'Simultaneous-Use', ':=', ?)"
                    to listOf(groupname, simultaneousUse.toString()),
            )
        }
        // Grup throttle FUP (opsional): rate-limit kedua yang di-swap saat kuota terlampaui.
        if (fupGroupname != null && fupRateLimit != null) {
            conn.replace(
                "DELETE FROM radgroupreply WHERE groupname = ? AND attribute = 'Mikrotik-Rate-Limit'"
                    to listOf(fupGroupname),
                "INSERT INTO radgroupreply (groupname, attribute, op, value) VALUES (?, 'Mikrotik-Rate-Limit', ':=', ?)"
                    to listOf(fupGroupname, fupRateLimit),
            )
        }
    }

    private inline fun inTransaction(target: NasTarget, body: (Connection) -> Unit) {
        connect(target).use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                body(conn)
                conn.commit()
            } catch (ex: Exception) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                runCatching { conn.autoCommit = previousAutoCommit }
            }
        }
    }

    /** Menjalankan sederet (SQL, params) berurutan dalam transaksi berjalan. */
    private fun Connection.replace(vararg statements: Pair<String, List<String>>) {
        for ((sql, params) in statements) {
            prepareStatement(sql).use { st ->
                params.forEachIndexed { i, value -> st.setString(i + 1, value) }
                st.executeUpdate()
            }
        }
    }
}
