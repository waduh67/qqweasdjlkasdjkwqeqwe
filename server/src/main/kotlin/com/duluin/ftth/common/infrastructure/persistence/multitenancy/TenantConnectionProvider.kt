package com.duluin.ftth.common.infrastructure.persistence.multitenancy

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Meneruskan tenant aktif ke Postgres sebagai GUC `app.tenant_id` setiap kali
 * connection dipinjam dari pool — inilah yang mengaktifkan Row-Level Security.
 * Saat dikembalikan ke pool, GUC di-RESET agar tidak bocor antar request.
 *
 * Memakai `set_config(...)` ber-parameter (bukan string concatenation) agar
 * kebal SQL injection dari nilai tenant identifier.
 */
class TenantConnectionProvider(
    private val dataSource: DataSource,
) : MultiTenantConnectionProvider<UUID> {

    override fun getAnyConnection(): Connection = dataSource.connection

    override fun releaseAnyConnection(connection: Connection) = connection.close()

    override fun getConnection(tenantIdentifier: UUID): Connection {
        val connection = dataSource.connection
        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)").use { stmt ->
            stmt.setString(1, tenantIdentifier.toString())
            stmt.execute()
        }
        return connection
    }

    override fun releaseConnection(tenantIdentifier: UUID, connection: Connection) {
        try {
            connection.createStatement().use { it.execute("RESET app.tenant_id") }
        } finally {
            connection.close()
        }
    }

    override fun supportsAggressiveRelease(): Boolean = false

    override fun isUnwrappableAs(unwrapType: Class<*>): Boolean = false

    override fun <T> unwrap(unwrapType: Class<T>): T = throw UnsupportedOperationException()
}
