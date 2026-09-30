package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.NetworkObservationApi
import com.duluin.ftth.network.NetworkObservationPath
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

@Repository
@Transactional(readOnly = true)
class NetworkObservationStore(private val entityManager: EntityManager) : NetworkObservationApi {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockView() = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("SELECT pg_advisory_xact_lock_shared(hashtextextended(current_schema()||':network-observation:'||?,0))").use { query ->
            query.setString(1, TenantContext.tenantId().toString()); query.execute()
        }
    }

    override fun pathAt(odpId: UUID, observedAt: Instant): NetworkObservationPath? = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        connection.prepareStatement("""WITH edge AS (SELECT * FROM network_observation_edge WHERE tenant_id=? AND effective_at<=?),
            selected_odp AS (SELECT * FROM edge WHERE node_kind='odp' AND node_id=? ORDER BY id DESC LIMIT 1)
            SELECT port.parent_id,port.node_id,port.label,distribution.id,cabinet.id,port.id,
                distribution.deleted,cabinet.deleted,port.deleted,distribution.parent_id,cabinet.parent_id
            FROM selected_odp distribution
            LEFT JOIN LATERAL(SELECT * FROM edge WHERE node_kind='odc' AND node_id=distribution.parent_id ORDER BY id DESC LIMIT 1) cabinet ON true
            LEFT JOIN LATERAL(SELECT * FROM edge WHERE node_kind='pon_port' AND node_id=cabinet.parent_id ORDER BY id DESC LIMIT 1) port ON true""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setTimestamp(2, Timestamp.from(observedAt)); query.setObject(3, odpId)
            query.executeQuery().use { rows ->
                if (!rows.next() || rows.getBoolean(7) || rows.getBoolean(8) || rows.getBoolean(9)) return@doReturningWork null
                if ((rows.getObject(10) != null && rows.getObject(5) == null) || (rows.getObject(11) != null && rows.getObject(6) == null)) return@doReturningWork null
                NetworkObservationPath(rows.getObject(1, UUID::class.java), rows.getObject(2, UUID::class.java), rows.getString(3),
                    (4..6).mapNotNull { index -> rows.getObject(index)?.let { rows.getLong(index) } })
            }
        }
    }
}
