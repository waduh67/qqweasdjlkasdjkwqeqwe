package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.InventoryTenantCutoverApi
import com.duluin.ftth.inventory.WarehouseOperationClass
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class WarehouseDeliveryLease(val eventId: UUID, val owner: UUID, val token: UUID, val attempt: Int, val expiresAt: Instant)
enum class WarehouseDeliveryFailure { RETRYABLE, NO_HANDLER, RECONCILIATION_REQUIRED }

@Service
class WarehouseOutboxDeliveryStore(private val jdbc: WarehouseCommandJdbc, private val cutover: InventoryTenantCutoverApi) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(owner: UUID): WarehouseDeliveryLease? {
        cutover.lockForCommand(cutover.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        return jdbc.execute { sql ->
            sql.update("""INSERT INTO inventory_outbox_delivery(id,tenant_id)
                SELECT id,tenant_id FROM inventory_outbox WHERE tenant_id=? ON CONFLICT (tenant_id,id) DO NOTHING""", sql.tenant)
            sql.update("""UPDATE inventory_outbox_delivery SET state='TERMINAL',lease_owner=NULL,lease_token=NULL,
                lease_until=NULL,last_error='ATTEMPTS_EXHAUSTED',revision=revision+1,updated_at=clock_timestamp()
                WHERE tenant_id=? AND state='LEASED' AND attempts=8 AND lease_until<=clock_timestamp()""", sql.tenant)
            val id = sql.value("""SELECT id FROM inventory_outbox_delivery WHERE tenant_id=? AND attempts<8 AND
                ((state='PENDING' AND next_attempt_at<=clock_timestamp()) OR (state='LEASED' AND lease_until<=clock_timestamp()))
                ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1""", sql.tenant)?.let(UUID::fromString) ?: return@execute null
            val token = UUID.randomUUID()
            sql.query("""UPDATE inventory_outbox_delivery SET state='LEASED',lease_owner=?,lease_token=?,
                lease_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1,revision=revision+1,updated_at=clock_timestamp()
                WHERE tenant_id=? AND id=? RETURNING attempts,lease_until""", owner, token, sql.tenant, id) {
                WarehouseDeliveryLease(id, owner, token, it.getInt("attempts"), it.getTimestamp("lease_until").toInstant())
            }.single()
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun delivered(lease: WarehouseDeliveryLease): Boolean = finish(lease, null)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failed(lease: WarehouseDeliveryLease, failure: WarehouseDeliveryFailure): Boolean = finish(lease, failure)

    private fun finish(lease: WarehouseDeliveryLease, failure: WarehouseDeliveryFailure?): Boolean {
        cutover.lockForCommand(cutover.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        return jdbc.execute { sql ->
            val state = when {
                failure == null -> "DELIVERED"
                failure != WarehouseDeliveryFailure.RETRYABLE || lease.attempt >= 8 -> "TERMINAL"
                else -> "PENDING"
            }
            sql.update("""UPDATE inventory_outbox_delivery SET state=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                delivered_at=CASE WHEN ?='DELIVERED' THEN clock_timestamp() ELSE NULL END,last_error=?,
                next_attempt_at=clock_timestamp()+make_interval(secs=>least(300,power(2,attempts))::integer),
                revision=revision+1,updated_at=clock_timestamp()
                WHERE tenant_id=? AND id=? AND state='LEASED' AND lease_owner=? AND lease_token=? AND attempts=? AND lease_until>clock_timestamp()""",
                state, state, failure?.name, sql.tenant, lease.eventId, lease.owner, lease.token, lease.attempt) == 1
        }
    }
}
