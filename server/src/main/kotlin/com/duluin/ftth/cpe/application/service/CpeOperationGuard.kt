package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component

@Component
class CpeOperationGuard(private val entityManager: EntityManager) {
    fun lockDevice(id: java.util.UUID): String? {
        val identity = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("SELECT genieacs_id FROM cpe_device WHERE tenant_id=? AND id=?").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, id)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        } ?: return null
        lock(identity)
        return identity
    }

    fun lock(genieacsId: String) = entityManager.unwrap(Session::class.java).doWork { connection ->
        check(!connection.autoCommit)
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(current_schema()||':cpe-operation:'||?,0))").use { query ->
            query.setString(1, genieacsId); query.execute()
        }
    }
}
