package com.duluin.ftth.customer.application.service

import com.duluin.ftth.customer.*
import com.duluin.ftth.customer.adapter.outbound.persistence.CustomerObservationStore
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Locale

@Service
@Transactional(readOnly = true)
class CustomerObservationService(private val store: CustomerObservationStore) : CustomerObservationApi {
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    override fun lockOwnershipView() = store.lockOwnershipView()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockEpisodes(serials: Set<String>) = store.lock(serials.mapTo(sortedSetOf()) { normalize(it) })

    override fun currentEpisode(serial: String): ObservationEpisode? =
        store.episodes(normalize(serial)).filter { it.endedAt == null }.singleOrNull()

    override fun activeObservationSerials(serials: Set<String>): List<String> =
        store.episodes(serials.mapTo(sortedSetOf()) { normalize(it) }).filter { it.endedAt == null }.map { normalize(it.onu.serialNumber) }

    override fun resolveObservation(serial: String, observedAt: Instant, context: ObservationPath?): ObservationAttribution {
        val episodes = store.episodes(normalize(serial)).filter {
            !observedAt.isBefore(it.startedAt) && (it.endedAt == null || observedAt.isBefore(it.endedAt))
        }
        if (episodes.size != 1) return ObservationAttribution(null, if (episodes.isEmpty()) "NO_EPISODE_AT_TIME" else "AMBIGUOUS_EPISODE")
        val episode = episodes.single()
        if (context != null) {
            val path = store.historicalPath(episode, observedAt)
                ?: return ObservationAttribution(null, "MISSING_PATH_HISTORY")
            if (path.hasOdp) {
                if (path.unverified) return ObservationAttribution(null, "UNVERIFIED_PATH_HISTORY")
                if (context.oltId == null || path.oltId != context.oltId ||
                    (context.ponPortId != null && path.ponId != context.ponPortId) ||
                    (context.ponPortLabel != null && path.label != context.ponPortLabel))
                    return ObservationAttribution(null, "PATH_MISMATCH")
            }
        }
        return ObservationAttribution(episode, null)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun advanceLiveObservation(episode: ObservationEpisode, observedAt: Instant): Boolean =
        episode.endedAt == null && store.advance(episode, observedAt)

    private fun normalize(serial: String) = serial.trim().uppercase(Locale.ROOT)
}
