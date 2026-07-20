package com.duluin.ftth.audit.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/** Satu baris jejak audit (append-only). Murni domain. */
class AuditEntry private constructor(
    val id: UUID,
    val tenantId: UUID,
    val actorId: UUID?,
    val actorEmail: String?,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val detail: Map<String, Any?>,
    val occurredAt: Instant,
) {
    companion object {
        @Suppress("LongParameterList")
        fun record(
            tenantId: UUID,
            actorId: UUID?,
            actorEmail: String?,
            action: String,
            entityType: String?,
            entityId: String?,
            detail: Map<String, Any?>,
        ): AuditEntry = AuditEntry(
            UuidV7.generate(), tenantId, actorId, actorEmail, action, entityType, entityId, detail, Instant.now(),
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            actorId: UUID?,
            actorEmail: String?,
            action: String,
            entityType: String?,
            entityId: String?,
            detail: Map<String, Any?>,
            occurredAt: Instant,
        ): AuditEntry = AuditEntry(
            id, tenantId, actorId, actorEmail, action, entityType, entityId, detail, occurredAt,
        )
    }
}
