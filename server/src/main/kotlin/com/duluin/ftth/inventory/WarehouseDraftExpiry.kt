package com.duluin.ftth.inventory

import java.time.Instant

/** Current terminal metadata; original command responses and source snapshots stay sealed. */
data class WarehouseDraftExpiry(val deadline: Instant, val recordedAt: Instant?, val reason: String = "IDLE_DEADLINE")
