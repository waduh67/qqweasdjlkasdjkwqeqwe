package com.duluin.ftth.network

import java.time.Instant
import java.util.UUID

interface NetworkObservationApi {
    fun lockView()
    fun pathAt(odpId: UUID, observedAt: Instant): NetworkObservationPath?
}

data class NetworkObservationPath(val oltId: UUID?, val ponPortId: UUID?, val label: String?, val edgeIds: List<Long>)
