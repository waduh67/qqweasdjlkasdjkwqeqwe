package com.duluin.ftth.bng.adapter.outbound.radius

import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.domain.model.SessionObservation
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.util.UUID

/**
 * Membaca `radacct` FreeRADIUS lewat JDBC ke radius-db platform. Di model RADIUS-as-a-service
 * jalur-baca ini server-side sepenuhnya, dengan dua ciri khas: (1) koneksi dari pool platform
 * [RadiusConnectionResolver] alih-alih URL JDBC per-NAS milik tenant; (2) query DISARING per
 * kode tenant sebab satu radius-db memuat semua tenant — `radacct.username` ber-prefix
 * `{kodeTenant}:` (S0), yang dikupas kembali ke username bare di sini agar cocok akun.
 */
@Component
class RadacctJdbcAdapter(
    private val connections: RadiusConnectionResolver,
) : RadiusAccountingReadPort {

    override fun isConfigured(): Boolean = connections.configured

    override fun activeSessions(tenantId: UUID, tenantCode: String): List<SessionObservation> {
        val prefix = "$tenantCode:"
        return connections.connectionFor(tenantId).use { conn ->
            conn.prepareStatement(ACTIVE_SESSIONS_SQL).use { st ->
                // Slug/kode tenant hanya [a-z0-9-] → tak ada metakarakter LIKE; ':' menambat
                // pemisah sehingga "acme:%" tak keliru menyapu tenant lain (mis. "acme-x").
                st.setString(1, "$prefix%")
                st.executeQuery().use { rs ->
                    val seen = HashSet<String>()
                    val out = ArrayList<SessionObservation>()
                    // ORDER BY acctstarttime DESC → baris terbaru per akun menang; baris basi
                    // (stop yang terlewat) untuk user sama diabaikan.
                    while (rs.next()) {
                        val observation = mapRow(rs, prefix)
                        if (seen.add(observation.username)) out += observation
                    }
                    out
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet, prefix: String): SessionObservation = SessionObservation(
        // Kupas prefiks tenant → username bare (radcheck memakai scoped; akun kita bare).
        username = rs.getString("username").removePrefix(prefix),
        online = true,
        nasIp = stripMask(rs.getString("nasipaddress")),
        framedIp = stripMask(rs.getString("framedipaddress")),
        sessionId = rs.getString("acctsessionid"),
        callingStationId = rs.getString("callingstationid"),
        uptimeSeconds = rs.getLong("acctsessiontime").takeUnless { rs.wasNull() },
        inOctets = rs.getLong("acctinputoctets").takeUnless { rs.wasNull() },
        outOctets = rs.getLong("acctoutputoctets").takeUnless { rs.wasNull() },
    )

    companion object {
        private const val COLUMNS =
            "username, framedipaddress, nasipaddress, acctsessionid, callingstationid, " +
                "acctsessiontime, acctinputoctets, acctoutputoctets"

        private const val ACTIVE_SESSIONS_SQL =
            "SELECT $COLUMNS FROM radacct WHERE acctstoptime IS NULL AND username LIKE ? " +
                "ORDER BY acctstarttime DESC"

        /** inet Postgres bisa terbaca "10.0.0.1/32"; UI hanya butuh alamatnya. */
        private fun stripMask(value: String?): String? = value?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }
}
