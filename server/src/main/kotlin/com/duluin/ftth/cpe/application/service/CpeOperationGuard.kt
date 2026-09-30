package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component

@Component
class CpeOperationGuard(private val entityManager: EntityManager) {
    fun lock(genieacsId: String): Boolean = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        check(!connection.autoCommit)
        val immediate = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(current_schema()||':cpe-operation:'||?,0))").use { query ->
            query.setString(1, genieacsId); query.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
        }
        if (immediate) return@doReturningWork true
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(current_schema()||':cpe-operation:'||?,0))").use { query ->
            query.setString(1, genieacsId); query.execute()
        }
        false
    }
}
