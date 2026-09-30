package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
@Transactional(timeout = 15)
class AssetProvisioningDeliveryStore(private val entityManager: EntityManager) {
    fun claim(operationId: UUID): AssetProvisioningClaim? = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        val token = UUID.randomUUID()
        connection.prepareStatement("""UPDATE fulfillment_asset_delivery SET state='DELIVERING',lease_token=?,lease_until=clock_timestamp()+interval '30 seconds',
            attempts=attempts+1,revision=revision+1,failure_code=NULL WHERE tenant_id=? AND operation_id=?
            AND (state IN ('PENDING','RECONCILIATION_REQUIRED') OR (state='DELIVERING' AND lease_until<clock_timestamp())) RETURNING operation_id""").use { query ->
            query.setObject(1, token); query.setObject(2, TenantContext.tenantId()); query.setObject(3, operationId)
            query.executeQuery().use { row -> if (!row.next()) return@doReturningWork null }
        }
        connection.prepareStatement("SELECT * FROM fulfillment_asset_outbox WHERE tenant_id=? AND operation_id=?").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, operationId)
            query.executeQuery().use { row ->
                check(row.next())
                AssetProvisioningClaim(AssetProvisioningWork(TenantContext.tenantId(), operationId, row.getObject("customer_id", UUID::class.java),
                    row.getObject("work_order_id", UUID::class.java), row.getObject("old_onu_id", UUID::class.java), row.getObject("new_onu_id", UUID::class.java)), token)
            }
        }
    }
    fun finish(claim: AssetProvisioningClaim, outcome: AssetProvisioningOutcome): Boolean = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        val state = when (outcome) {
            AssetProvisioningOutcome.Succeeded -> "SUCCEEDED"
            is AssetProvisioningOutcome.ReconciliationRequired -> "RECONCILIATION_REQUIRED"
        }
        val failure = when (outcome) {
            AssetProvisioningOutcome.Succeeded -> null
            is AssetProvisioningOutcome.ReconciliationRequired -> outcome.code
        }
        connection.prepareStatement("""UPDATE fulfillment_asset_delivery SET state=?,failure_code=?,lease_token=NULL,lease_until=NULL,
            completed_at=CASE WHEN ?='SUCCEEDED' THEN clock_timestamp() ELSE NULL END,revision=revision+1
            WHERE tenant_id=? AND operation_id=? AND state='DELIVERING' AND lease_token=?""").use { query ->
            query.setString(1, state); query.setString(2, failure); query.setString(3, state); query.setObject(4, TenantContext.tenantId())
            query.setObject(5, claim.work.operationId); query.setObject(6, claim.token); query.executeUpdate() == 1
        }
    }
    fun pending(): List<UUID> = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        connection.prepareStatement("""SELECT operation_id FROM fulfillment_asset_delivery WHERE tenant_id=?
            AND (state='PENDING' OR (state='DELIVERING' AND lease_until<clock_timestamp())) ORDER BY operation_id LIMIT 10""").use { query ->
            query.setObject(1, TenantContext.tenantId())
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
    }
}
