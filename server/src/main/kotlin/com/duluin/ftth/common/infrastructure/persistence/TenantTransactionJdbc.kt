package com.duluin.ftth.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

@Component
class TenantTransactionJdbc(private val entityManager: EntityManager) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun withinTenant(tenantId: UUID, action: (Connection) -> Unit) {
        entityManager.flush()
        entityManager.unwrap(Session::class.java).doWork { connection: Connection ->
            val previous = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_setting('app.tenant_id',true)").use { rows ->
                    check(rows.next())
                    rows.getString(1).orEmpty()
                }
            }
            setTenant(connection, tenantId.toString())
            var failure: Throwable? = null
            try {
                action(connection)
            } catch (original: Throwable) {
                failure = original
                throw original
            } finally {
                try {
                    setTenant(connection, previous)
                } catch (restore: SQLException) {
                    if (failure == null) throw restore else failure.addSuppressed(restore)
                }
            }
        }
    }

    private fun setTenant(connection: Connection, tenant: String) {
        connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)").use {
            it.setString(1, tenant)
            it.execute()
        }
    }
}
