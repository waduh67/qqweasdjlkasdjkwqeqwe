package com.duluin.ftth.cpe.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.domain.model.CpeDevice
import com.duluin.ftth.customer.CustomerObservationApi
import com.duluin.ftth.customer.ObservationEpisode
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class CpeObservationBindingStore(private val entityManager: EntityManager, private val episodes: CustomerObservationApi,
    private val devices: CpeDeviceJpaRepository) {
    fun existing(genieacsId: String, onuId: java.util.UUID): CpeDevice? =
        devices.findByGenieacsIdAndOnuId(genieacsId, onuId)?.toDomain()

    fun current(device: CpeDevice): ObservationEpisode? = episodes.currentEpisode(device.serialNumber)?.takeIf {
        it.onu.id == device.onuId && it.onu.customerId == device.customerId &&
            (it.legacy || device.lastInformAt?.let { time -> !time.isBefore(it.startedAt) && !time.isAfter(Instant.now().plusSeconds(300)) } == true)
    }

    fun visible(device: CpeDevice): Boolean {
        val episode = current(device) ?: return false
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT EXISTS(SELECT FROM cpe_episode_snapshot s JOIN cpe_device d ON d.tenant_id=s.tenant_id AND d.id=s.device_id
                WHERE s.tenant_id=? AND s.device_id=? AND s.episode_revision=? AND s.assignment_id IS NOT DISTINCT FROM ?
                AND s.assignment_revision=? AND s.snapshot=to_jsonb(d))""").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, device.id); query.setLong(3, episode.episodeRevision)
                query.setObject(4, episode.assignmentId); query.setLong(5, episode.assignmentRevision)
                query.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
            }
        }
    }

    fun record(device: CpeDevice, episode: ObservationEpisode) = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("""INSERT INTO cpe_episode_snapshot(tenant_id,device_id,onu_id,assignment_id,assignment_revision,episode_revision,snapshot)
            SELECT tenant_id,id,onu_id,?,?,?,to_jsonb(d) FROM cpe_device d WHERE tenant_id=? AND id=?
            ON CONFLICT DO NOTHING""").use { query ->
            query.setObject(1, episode.assignmentId); query.setLong(2, episode.assignmentRevision); query.setLong(3, episode.episodeRevision)
            query.setObject(4, TenantContext.tenantId()); query.setObject(5, device.id); query.executeUpdate()
        }
    }
}
