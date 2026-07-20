package com.duluin.ftth.audit.application.port.inbound

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import java.time.Instant
import java.util.UUID

interface AuditQuery {

    fun list(pageRequest: PageRequest): Page<AuditEntryView>
}

data class AuditEntryView(
    val id: UUID,
    val actorId: UUID?,
    val actorEmail: String?,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val detail: Map<String, Any?>,
    val occurredAt: Instant,
)
