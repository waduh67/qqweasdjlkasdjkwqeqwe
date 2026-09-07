package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.tenancy.TenantAuthorityDirectory
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager as Transactions
import java.sql.Connection

@Component
class CatalogAuthorityFence(
    private val entityManager: EntityManager,
    private val tenants: TenantAuthorityDirectory,
    private val tenantJdbc: TenantTransactionJdbc,
) {
    fun lock() {
        check(Transactions.isActualTransactionActive() && !Transactions.isCurrentTransactionReadOnly())
        if (Transactions.getSynchronizations().any { it is CatalogChange }) return
        check(Transactions.getSynchronizations().none { it is TenantAuthorityTransaction }) { "Catalog mutation must precede tenant authority locks" }
        entityManager.unwrap(Session::class.java).doWork { connection: Connection ->
            connection.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(hashtextextended(current_schema()||':iam.catalog',0))") }
        }
        Transactions.registerSynchronization(CatalogChange())
    }

    fun changed() {
        lock()
        val change = Transactions.getSynchronizations().filterIsInstance<CatalogChange>().single()
        if (change.incremented) return
        val ids = tenants.allTenantIds()
        ids.forEach { tenant -> tenantJdbc.withinTenant(tenant) { connection ->
            connection.prepareStatement("SELECT epoch FROM iam_authorization_epoch WHERE tenant_id=? FOR UPDATE").use {
                it.setObject(1, tenant); it.executeQuery().use { rows -> check(rows.next()) }
            }
        } }
        ids.forEach { tenant -> tenantJdbc.withinTenant(tenant) { connection ->
            connection.prepareStatement("UPDATE iam_authorization_epoch SET epoch=epoch+1,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=?").use {
                it.setObject(1, tenant); check(it.executeUpdate() == 1)
            }
        } }
        change.incremented = true
        change.tenants = ids.toSet()
    }

    private class CatalogChange(var incremented: Boolean = false, var tenants: Set<java.util.UUID> = emptySet()) : TransactionSynchronization
    companion object {
        internal fun incremented(tenant: java.util.UUID): Boolean = Transactions.getSynchronizations()
            .filterIsInstance<CatalogChange>().any { tenant in it.tenants }
    }
}

internal interface TenantAuthorityTransaction : TransactionSynchronization
