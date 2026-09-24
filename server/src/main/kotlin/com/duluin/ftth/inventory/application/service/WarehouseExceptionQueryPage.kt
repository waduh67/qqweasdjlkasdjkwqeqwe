package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/** Current WO ownership is checked before totals; a denied public-port call must roll back its own transaction. */
internal fun <T : Any> exceptionQueryPage(transactions: PlatformTransactionManager, page: WarehousePageRequest,
    candidates: (Int) -> WarehousePage<T>, authorize: (T) -> T, checkAccess: () -> Unit): WarehousePage<T> {
    check(!TransactionSynchronizationManager.isActualTransactionActive())
    val transaction = TransactionTemplate(transactions).apply { timeout = 30 }
    val selected = mutableListOf<T>()
    val offset = page.page.toLong() * page.size
    var total = 0L
    var candidatePage = 0
    do {
        val batch = requireNotNull(transaction.execute { candidates(candidatePage) })
        for (candidate in batch.items) {
            val visible = try { transaction.execute { authorize(candidate) } }
            catch (failure: WarehouseContractException) {
                if (failure.error.code !in setOf(WarehouseErrorCode.NOT_FOUND, WarehouseErrorCode.FORBIDDEN)) throw failure
                null
            }
            if (visible != null) {
                if (total >= offset && selected.size < page.size) selected += visible
                total++
            }
        }
        candidatePage++
    } while (candidatePage.toLong() * 100 < batch.totalElements)
    transaction.executeWithoutResult { checkAccess() }
    return WarehousePage(selected, page.page, page.size, total)
}
