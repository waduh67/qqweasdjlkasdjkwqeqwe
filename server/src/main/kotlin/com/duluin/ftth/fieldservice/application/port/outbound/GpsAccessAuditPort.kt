package com.duluin.ftth.fieldservice.application.port.outbound

import java.time.Instant
import java.util.UUID

data class GpsAccessAudit(
    val tenantId: UUID,
    val actorId: UUID,
    val pointId: UUID,
    val purpose: String,
    val exactFields: Boolean,
    val occurredAt: Instant,
)

fun interface GpsAccessAuditPort { fun record(audit: GpsAccessAudit) }
fun interface GpsLegalHoldPort { fun heldPointIds(tenantId: UUID): Set<UUID> }
