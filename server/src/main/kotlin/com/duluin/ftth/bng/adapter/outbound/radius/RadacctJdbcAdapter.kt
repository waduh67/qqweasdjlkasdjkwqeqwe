package com.duluin.ftth.bng.adapter.outbound.radius

import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.domain.model.SessionObservation
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
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
    /**
     * Umur maksimal `acctupdatetime` sebelum baris radacct yang masih menganga dianggap
     * bangkai. Longgar (1 jam) dengan sengaja — lihat [ACTIVE_SESSIONS_SQL].
     */
    @Value("\${ftth.radius.acct-interim-stale-after:PT1H}") private val interimStaleAfter: Duration,
) : RadiusAccountingReadPort {

    override fun isConfigured(): Boolean = connections.configured

    override fun activeSessions(
        tenantId: UUID,
        tenantCode: String,
        macUsernames: List<String>,
    ): List<SessionObservation> {
        val prefix = "$tenantCode:"
        return connections.connectionFor(tenantId).use { conn ->
            conn.prepareStatement(ACTIVE_SESSIONS_SQL).use { st ->
                // Slug/kode tenant hanya [a-z0-9-] → tak ada metakarakter LIKE; ':' menambat
                // pemisah sehingga "acme:%" tak keliru menyapu tenant lain (mis. "acme-x").
                st.setString(1, "$prefix%")
                // Akun berbasis MAC (DHCP/Static) ditulis POLOS tanpa prefiks → tak tertangkap
                // LIKE; saring balik lewat daftar eksplisit. Kosong → ANY('{}') selalu false.
                st.setArray(2, conn.createArrayOf("text", macUsernames.toTypedArray()))
                st.setTimestamp(3, Timestamp.from(Instant.now().minus(interimStaleAfter)))
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
        // Akun berbasis MAC ditulis polos → removePrefix no-op, tetap MAC apa adanya.
        username = rs.getString("username").removePrefix(prefix),
        online = true,
        nasIp = stripMask(rs.getString("nasipaddress")),
        framedIp = stripMask(rs.getString("framedipaddress")),
        sessionId = rs.getString("acctsessionid"),
        callingStationId = rs.getString("callingstationid"),
        uptimeSeconds = rs.getLong("acctsessiontime").takeUnless { rs.wasNull() },
        inOctets = octets(rs, "acctinputoctets", "acctinputgigawords"),
        outOctets = octets(rs, "acctoutputoctets", "acctoutputgigawords"),
        // Jam NAS, bukan jam kita: penghitung di baris ini terakhir benar saat Interim-Update
        // terakhir tiba, yang jaraknya bisa jauh lebih lebar dari periode poll. Lihat
        // [SessionObservation.countersAt].
        countersAt = rs.getTimestamp("acctupdatetime")?.toInstant(),
    )

    companion object {
        private const val COLUMNS =
            "username, framedipaddress, nasipaddress, acctsessionid, callingstationid, " +
                "acctsessiontime, acctinputoctets, acctoutputoctets, acctinputgigawords, acctoutputgigawords, " +
                "acctupdatetime"

        /**
         * Sesi hidup tenant. Selain `acctstoptime IS NULL`, baris yang interim-update-nya
         * BERHENTI ikut dibuang — itu bangkai, bukan sesi.
         *
         * Kejadiannya di lapangan: BRAS kehilangan jalur ke RADIUS (uplink ISP putus, router
         * dimatikan paksa) sehingga Acct-Stop tak pernah terkirim; barisnya menganga selamanya
         * dan pelanggan yang sudah lama mati tetap terbaca "Online". (Reboot router yang normal
         * TIDAK termasuk: FreeRADIUS menutup sesinya sendiri saat menerima Accounting-On.)
         *
         * Syarat `acctupdatetime > acctstarttime` menjaga agar router yang TAK memasang
         * `interim-update` tak ikut kena: di sana `acctupdatetime` memang membeku sama dengan
         * waktu mulai, jadi tak ada yang bisa disimpulkan dari kebasiannya. Ambangnya longgar
         * (bawaan 1 jam) supaya interim yang jarang (mis. 30 menit) tak pernah salah dinyatakan
         * putus — salah-offline jauh lebih mahal daripada telat-offline: yang satu mengirim
         * teknisi ke pelanggan yang baik-baik saja.
         */
        private const val ACTIVE_SESSIONS_SQL =
            "SELECT $COLUMNS FROM radacct WHERE acctstoptime IS NULL " +
                "AND (username LIKE ? OR username = ANY(?)) " +
                "AND (acctupdatetime IS NULL OR acctupdatetime <= acctstarttime OR acctupdatetime >= ?) " +
                "ORDER BY acctstarttime DESC"

        /** Batas wrap penghitung octet 32-bit RADIUS; tiap kelipatan dicatat di kolom gigawords. */
        private const val OCTETS_PER_GIGAWORD = 1L shl 32

        /**
         * Octet kumulatif sebenarnya = octet 32-bit bawah + `gigawords × 2³²`. FreeRADIUS mencatat
         * jumlah wrap penghitung di kolom `*gigawords`; tanpa menjumlahnya, sesi high-volume yang
         * sudah wrap akan under-report. Kolom octet null → null (tak terlaporkan); gigawords
         * null → 0 (sesi kecil tak pernah wrap, hasil = octet bawah, sama seperti sebelumnya).
         */
        private fun octets(rs: ResultSet, octetCol: String, gigawordCol: String): Long? {
            val low = rs.getLong(octetCol).takeUnless { rs.wasNull() } ?: return null
            val gigawords = rs.getLong(gigawordCol).takeUnless { rs.wasNull() } ?: 0L
            return low + gigawords * OCTETS_PER_GIGAWORD
        }

        /** inet Postgres bisa terbaca "10.0.0.1/32"; UI hanya butuh alamatnya. */
        private fun stripMask(value: String?): String? = value?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }
}
