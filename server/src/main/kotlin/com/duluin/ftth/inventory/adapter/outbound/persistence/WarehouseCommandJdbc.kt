package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection

@Component
class WarehouseCommandJdbc(private val entityManager: EntityManager) {
    internal fun <T> execute(action: (PostingSql) -> T): T {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            check(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            val sql = PostingSql(connection, TenantContext.tenantId())
            check(sql.value("SELECT current_setting('app.tenant_id',true)") == sql.tenant.toString())
            action(sql)
        }
    }
}
