package com.duluin.ftth.bng.adapter.outbound.radius

import com.duluin.ftth.bng.application.port.outbound.RadiusProvisioningPort
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID

/**
 * Menulis tabel otorisasi FreeRADIUS (`radcheck`/`radreply`/`radusergroup`) lewat JDBC ke
 * radius-db platform. Di model RADIUS-as-a-service jalur-tulis ini server-side sepenuhnya:
 * koneksi datang dari pool platform lewat [RadiusConnectionResolver], bukan lagi URL JDBC
 * per-NAS milik tenant.
 *
 * Tiap operasi satu transaksi eksplisit agar sekumpulan baris (kredensial + grup) tampil
 * atomik ke FreeRADIUS — auth tak pernah melihat akun separuh-terpasang.
 */
@Component
class FreeRadiusJdbcAdapter(
    private val connections: RadiusConnectionResolver,
) : RadiusProvisioningPort {

    override fun isConfigured(): Boolean = connections.configured

    override fun provision(tenantId: UUID, scopedUsername: String, password: String, groupname: String, framedIp: String?) =
        inTransaction(tenantId) { conn ->
            conn.replace(
                "DELETE FROM radcheck WHERE username = ? AND attribute = 'Cleartext-Password'" to listOf(scopedUsername),
                "INSERT INTO radcheck (username, attribute, op, value) VALUES (?, 'Cleartext-Password', ':=', ?)"
                    to listOf(scopedUsername, password),
                "DELETE FROM radusergroup WHERE username = ?" to listOf(scopedUsername),
                "INSERT INTO radusergroup (username, groupname, priority) VALUES (?, ?, 1)"
                    to listOf(scopedUsername, groupname),
                // Reservasi IP (DHCP/Static): selalu hapus dulu — idempoten; tulis ulang hanya bila diminta.
                "DELETE FROM radreply WHERE username = ? AND attribute = 'Framed-IP-Address'" to listOf(scopedUsername),
            )
            if (framedIp != null) {
                conn.replace(
                    "INSERT INTO radreply (username, attribute, op, value) VALUES (?, 'Framed-IP-Address', ':=', ?)"
                        to listOf(scopedUsername, framedIp),
                )
            }
        }

    override fun deprovision(tenantId: UUID, scopedUsername: String) =
        inTransaction(tenantId) { conn ->
            conn.replace(
                "DELETE FROM radcheck WHERE username = ?" to listOf(scopedUsername),
                "DELETE FROM radreply WHERE username = ?" to listOf(scopedUsername),
                "DELETE FROM radusergroup WHERE username = ?" to listOf(scopedUsername),
            )
        }

    override fun syncGroup(
        tenantId: UUID,
        groupname: String,
        rateLimit: String,
        simultaneousUse: Int?,
        fupGroupname: String?,
        fupRateLimit: String?,
    ) = inTransaction(tenantId) { conn ->
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

    private inline fun inTransaction(tenantId: UUID, body: (Connection) -> Unit) {
        connections.connectionFor(tenantId).use { conn ->
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
