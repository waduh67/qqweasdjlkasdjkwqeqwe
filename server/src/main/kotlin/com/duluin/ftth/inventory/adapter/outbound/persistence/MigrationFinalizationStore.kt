package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import java.util.UUID

data class StoredMigrationFinalization(val actorId: UUID, val batchId: UUID, val payloadHash: String,
    val resultingEpoch: Long, val body: String)

@Repository
class MigrationFinalizationStore(private val jdbc: WarehouseCommandJdbc) {
    fun review(batch: UUID): String = jdbc.execute { sql ->
        sql.value("SELECT warehouse_migration_finalization_review(?)::text", batch) ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun replay(key: String): StoredMigrationFinalization? = jdbc.execute { sql ->
        sql.query("""SELECT actor_id,batch_id,payload_hash,resulting_epoch,original_body
            FROM inventory_migration_finalization WHERE tenant_id=? AND operation_key=?""", sql.tenant, key) {
            StoredMigrationFinalization(it.getObject("actor_id", UUID::class.java), it.getObject("batch_id", UUID::class.java),
                it.getString("payload_hash"), it.getLong("resulting_epoch"), it.getString("original_body"))
        }.singleOrNull()
    }

    fun finalize(batch: UUID, actor: UUID, authorityEpoch: Long, key: String, payload: WarehouseCanonicalPayload): String = jdbc.execute { sql ->
        requireNotNull(sql.value("SELECT warehouse_finalize_migration(?,?,?,?,?)", batch, actor, authorityEpoch, key, payload.json))
    }
}
