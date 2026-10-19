package com.karuhun.ftth.common.infrastructure.persistence

import com.karuhun.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection

@Component
class WarehouseCommandJdbc(private val entityManager: EntityManager) {
    fun <T> execute(action: (PostingSql) -> T): T {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            check(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            val sql = PostingSql(connection, TenantContext.tenantId())
            check(sql.value("SELECT current_setting('app.tenant_id',true)") == sql.tenant.toString())
            try { action(sql) } catch (failure: java.sql.SQLException) {
                when (failure.sqlState) {
                    "40001", "40P01", "55P03" -> sql.fail(com.karuhun.ftth.common.warehouse.WarehouseErrorCode.STALE_REVISION, failure)
                    "23505" -> sql.fail(com.karuhun.ftth.common.warehouse.WarehouseErrorCode.IDEMPOTENCY_CONFLICT, failure)
                    "23503" -> sql.fail(com.karuhun.ftth.common.warehouse.WarehouseErrorCode.NOT_FOUND, failure)
                    "23514" -> sql.fail(if (failure.isDraftExpired()) com.karuhun.ftth.common.warehouse.WarehouseErrorCode.DRAFT_EXPIRED
                        else com.karuhun.ftth.common.warehouse.WarehouseErrorCode.SOURCE_NOT_VERIFIED, failure)
                    else -> throw failure
                }
            }
        }
    }
}

fun java.sql.SQLException.isDraftExpired(): Boolean = sqlState == "23514" &&
    (this as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint == "warehouse_draft_expired_ck"
