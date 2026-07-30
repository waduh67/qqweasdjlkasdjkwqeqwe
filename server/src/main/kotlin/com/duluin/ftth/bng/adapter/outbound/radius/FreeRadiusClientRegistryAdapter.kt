package com.duluin.ftth.bng.adapter.outbound.radius

import com.duluin.ftth.bng.application.port.outbound.RadiusClientRegistryPort
import org.springframework.stereotype.Component
import java.sql.Connection
import java.util.UUID

/**
 * Menulis tabel `nas` (klien RADIUS) di radius-db platform lewat JDBC — sumber
 * "dynamic clients" FreeRADIUS. Sama seperti [FreeRadiusJdbcAdapter], memakai pool
 * platform lewat [RadiusConnectionResolver] dan menjalankan tiap operasi dalam satu
 * transaksi eksplisit; boilerplate JDBC sengaja diduplikasi (bukan diabstraksi lintas
 * adapter) mengikuti pola yang sudah ada agar tiap adapter berdiri sendiri.
 *
 * Idempoten: register = DELETE berdasar `nasname` lalu INSERT (satu baris per alamat);
 * deregister = DELETE. `type='other'` — FreeRADIUS mengizinkan semua vendor sebagai klien;
 * yang penting hanya (nasname, secret, shortname).
 */
@Component
class FreeRadiusClientRegistryAdapter(
    private val connections: RadiusConnectionResolver,
) : RadiusClientRegistryPort {

    override fun isConfigured(): Boolean = connections.configured

    override fun register(tenantId: UUID, nasname: String, shortname: String, secret: String) =
        inTransaction(tenantId) { conn ->
            conn.replace(
                "DELETE FROM nas WHERE nasname = ?" to listOf(nasname),
                "INSERT INTO nas (nasname, shortname, type, secret, description) VALUES (?, ?, 'other', ?, ?)"
                    to listOf(nasname, shortname, secret, "tenant $shortname"),
            )
        }

    override fun deregister(tenantId: UUID, nasname: String) =
        inTransaction(tenantId) { conn ->
            conn.replace("DELETE FROM nas WHERE nasname = ?" to listOf(nasname))
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
