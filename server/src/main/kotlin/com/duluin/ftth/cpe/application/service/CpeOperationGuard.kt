package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component

@Component
class CpeOperationGuard(private val entityManager: EntityManager) {
    fun lock(genieacsId: String) = entityManager.unwrap(Session::class.java).doWork { connection ->
        check(!connection.autoCommit)
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(current_schema()||':cpe-operation:'||?||':'||?,0))").use { query ->
            query.setString(1, TenantContext.tenantId().toString()); query.setString(2, genieacsId); query.execute()
        }
    }
}
