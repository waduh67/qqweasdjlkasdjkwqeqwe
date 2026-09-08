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
            try { action(sql) } catch (failure: java.sql.SQLException) {
                when (failure.sqlState) {
                    "40001", "40P01", "55P03" -> sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.STALE_REVISION)
                    "23505" -> sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
                    "23503" -> sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.NOT_FOUND)
                    "23514" -> sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    else -> throw failure
                }
            }
        }
    }
}
