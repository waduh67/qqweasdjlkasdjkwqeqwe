package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class CustomerAssetRetentionStore(private val entityManager: EntityManager) {
    fun assertCustomerRemovable(customerId: UUID) = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("""SELECT 1 FROM customer_asset_installation WHERE tenant_id=? AND customer_id=?
            UNION ALL SELECT 1 FROM onu WHERE tenant_id=? AND customer_id=? LIMIT 1""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, customerId)
            query.setObject(3, TenantContext.tenantId()); query.setObject(4, customerId)
            query.executeQuery().use { row -> if (row.next()) throw ConflictException("ASSET_HISTORY_RETAINED") }
        }
    }

    fun assertOnuRemovable(onuId: UUID) = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("SELECT 1 FROM onu_topology_history WHERE tenant_id=? AND onu_id=? LIMIT 1").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, onuId)
            query.executeQuery().use { row -> if (row.next()) throw ConflictException("ASSET_HISTORY_RETAINED") }
        }
    }
}
