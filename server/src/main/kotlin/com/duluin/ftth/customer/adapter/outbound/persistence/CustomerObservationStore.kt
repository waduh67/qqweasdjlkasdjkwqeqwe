package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.ObservationEpisode
import com.duluin.ftth.customer.OnuRef
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class CustomerObservationStore(private val entityManager: EntityManager) {
    private fun <T> jdbc(block: (Connection) -> T): T = entityManager.unwrap(Session::class.java).doReturningWork(block)

    fun lock(serials: Set<String>) = jdbc { connection ->
        connection.prepareStatement("""SELECT id FROM onu WHERE tenant_id=? AND warehouse_canonical_serial(serial_number)=ANY(?)
            ORDER BY id FOR UPDATE""").use { query ->
            query.setObject(1, TenantContext.tenantId())
            query.setArray(2, connection.createArrayOf("text", serials.toTypedArray()))
            query.executeQuery().use { rows -> while (rows.next()) Unit }
        }
    }

    fun episodes(serial: String): List<ObservationEpisode> = jdbc { connection ->
        connection.prepareStatement("""SELECT o.id,o.serial_number,o.customer_id,c.name,o.odp_id,o.status,o.assignment_id,
            o.episode_revision,coalesce(o.started_at,o.created_at),o.retired_at,o.warehouse_admission,
            CASE WHEN o.retired_at IS NULL THEN 0 ELSE 1 END
            FROM onu o JOIN customer c ON c.tenant_id=o.tenant_id AND c.id=o.customer_id
            WHERE o.tenant_id=? AND warehouse_canonical_serial(o.serial_number)=? ORDER BY o.id""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setString(2, serial)
            query.executeQuery().use { rows -> buildList {
                while (rows.next()) add(ObservationEpisode(
                    OnuRef(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getObject(3, UUID::class.java),
                        rows.getString(4), rows.getObject(5, UUID::class.java), rows.getString(6)),
                    rows.getObject(7, UUID::class.java), rows.getLong(12), rows.getLong(8), rows.getTimestamp(9).toInstant(),
                    rows.getTimestamp(10)?.toInstant(), rows.getString(11) == "LEGACY_UNRESOLVED"))
            } }
        }.also { episodes -> episodes.filterNot { it.legacy }.forEach { connection.validateOnuEpisode(it.onu.id) } }
    }

    data class PathSnapshot(val hasOdp: Boolean, val oltId: UUID?, val ponId: UUID?, val label: String?, val unverified: Boolean)

    fun historicalPath(episode: ObservationEpisode, at: Instant): PathSnapshot? = jdbc { connection ->
        connection.prepareStatement("""SELECT has_odp,olt_id,pon_port_id,pon_port_label,baseline AND captured_at>? AS unverified
            FROM customer_onu_observation_path WHERE tenant_id=? AND onu_id=? AND (topology_revision=0 OR effective_at<=?)
            ORDER BY topology_revision DESC LIMIT 1""").use { query ->
            query.setTimestamp(1, Timestamp.from(at)); query.setObject(2, TenantContext.tenantId())
            query.setObject(3, episode.onu.id); query.setTimestamp(4, Timestamp.from(at))
            query.executeQuery().use { rows -> if (rows.next()) PathSnapshot(rows.getBoolean(1), rows.getObject(2, UUID::class.java),
                rows.getObject(3, UUID::class.java), rows.getString(4), rows.getBoolean(5)) else null }
        }
    }

    fun advance(episode: ObservationEpisode, observedAt: Instant): Boolean = jdbc { connection ->
        connection.prepareStatement("""INSERT INTO customer_onu_observation_state(tenant_id,onu_id,last_live_at)
            SELECT tenant_id,id,? FROM onu WHERE tenant_id=? AND id=? AND retired_at IS NULL AND episode_revision=?
            ON CONFLICT(tenant_id,onu_id) DO UPDATE SET last_live_at=EXCLUDED.last_live_at
            WHERE customer_onu_observation_state.last_live_at<EXCLUDED.last_live_at RETURNING onu_id""").use { query ->
            query.setTimestamp(1, Timestamp.from(observedAt)); query.setObject(2, TenantContext.tenantId())
            query.setObject(3, episode.onu.id); query.setLong(4, episode.episodeRevision)
            query.executeQuery().use { it.next() }
        }
    }
}
