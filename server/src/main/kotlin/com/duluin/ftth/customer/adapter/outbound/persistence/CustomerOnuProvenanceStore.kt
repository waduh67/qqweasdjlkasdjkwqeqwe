package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.AssetProvenance
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class OnuProvenanceProjection(
    val assetId: UUID?,
    val assignmentId: UUID?,
    val provenance: AssetProvenance,
    val retiredAt: Instant?,
)

@Repository
class CustomerOnuProvenanceStore(private val entityManager: EntityManager) {
    fun forOnus(ids: Set<UUID>): Map<UUID, OnuProvenanceProjection> {
        if (ids.isEmpty()) return emptyMap()
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            val selectedIds = connection.createArrayOf("uuid", ids.toTypedArray())
            try {
                connection.prepareStatement("""SELECT id,asset_id,assignment_id,provenance,retired_at FROM onu
                    WHERE tenant_id=? AND id=ANY(?)""").use { statement ->
                    statement.queryTimeout = 20
                    statement.setObject(1, TenantContext.tenantId())
                    statement.setArray(2, selectedIds)
                    statement.executeQuery().use { rows -> buildMap {
                        while (rows.next()) put(rows.getObject("id", UUID::class.java), OnuProvenanceProjection(
                            rows.getObject("asset_id", UUID::class.java), rows.getObject("assignment_id", UUID::class.java),
                            AssetProvenance.valueOf(rows.getString("provenance")), rows.getTimestamp("retired_at")?.toInstant()))
                    } }
                }
            } finally {
                selectedIds.free()
            }
        }
    }
}
