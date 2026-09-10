package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

@Repository
class WarehousePostingPersistence(private val entityManager: EntityManager) : WarehousePostingStore {
    override fun write(command: WarehousePost, cutoverEpoch: Long): WarehousePostResult = within { sql ->
        val documents = PostingDocuments(sql)
        documents.lock(command,cutoverEpoch)
        val stock = PostingStock(sql)
        stock.lock(command)
        command.approval?.let { approval ->
            require(command.kind == com.duluin.ftth.inventory.domain.model.MovementKind.RECEIVE && command.splits.isEmpty() && command.reservations.isEmpty())
            stock.lockBalances(command, command.operation.recordedAt)
            assertReceiptApproval(sql, approval, true)
        }
        val result = WarehousePostResult(command.operation.postingId,command.operation.id,Math.addExact(command.expectedRevision,1),command.operation.recordedAt)
        documents.advance(command,result,cutoverEpoch)
        try { documents.header(command,result) }
        catch (failure: SQLException) {
            if (command.approval != null && failure.sqlState == "23514" && failure.message.orEmpty().contains("posting requires live document-bound approval"))
                throw ApprovalPostingStopped(command.approval, com.duluin.ftth.inventory.WarehouseApprovalStatus.EXPIRED)
            throw failure
        }
        stock.split(command)
        stock.legs(command,result)
        stock.balances(command,result.recordedAt)
        PostingReservations(sql).apply(command,result)
        stock.custody(command)
        PostingFacts(sql).write(command,result)
        documents.events(command,result)
        result
    }

    override fun rebuild(): List<PostingBalance> = within { sql -> PostingProjection(sql).rebuild() }

    private fun <T> within(block: (PostingSql) -> T): T {
        check(TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        entityManager.flush()
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            val sql = PostingSql(connection,TenantContext.tenantId())
            check(!connection.autoCommit)
            check(sql.value("SELECT current_setting('app.tenant_id',true)") == sql.tenant.toString()) { "Posting tenant context mismatch" }
            if(connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) sql.fail(WarehouseErrorCode.STALE_REVISION)
            try { block(sql) } catch(failure: SQLException) {
                when(failure.sqlState) {
                    "23505" -> sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
                    "40001", "40P01" -> sql.fail(WarehouseErrorCode.STALE_REVISION)
                    else -> throw failure
                }
            }
        }
    }
}
