package com.duluin.ftth.customer

import java.time.Instant
import java.util.UUID

interface CustomerObservationApi {
    fun lockOwnershipView()
    fun lockEpisodes(serials: Set<String>)
    fun currentEpisode(serial: String): ObservationEpisode?
    fun activeObservationSerials(serials: Set<String>): List<String>
    fun resolveObservation(serial: String, observedAt: Instant, context: ObservationPath? = null): ObservationAttribution
    fun advanceLiveObservation(episode: ObservationEpisode, observedAt: Instant): Boolean
}

data class ObservationPath(val oltId: UUID?, val oltCode: String, val ponPortLabel: String?, val ponPortId: UUID? = null)
data class ObservationEpisode(val onu: OnuRef, val assignmentId: UUID?, val assignmentRevision: Long,
    val episodeRevision: Long, val startedAt: Instant, val endedAt: Instant?, val legacy: Boolean)
data class ObservationAttribution(val episode: ObservationEpisode?, val reason: String?)
