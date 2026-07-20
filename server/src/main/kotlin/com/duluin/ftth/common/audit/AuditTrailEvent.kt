package com.duluin.ftth.common.audit

import java.util.UUID

/**
 * Event jejak-audit yang dipublikasikan module mana pun lewat Spring
 * `ApplicationEventPublisher` dan dikonsumsi module `audit`.
 *
 * Diletakkan di shared kernel `common` supaya publisher tidak perlu bergantung
 * pada module audit — hanya bergantung pada `common` (diizinkan) plus Spring.
 */
data class AuditTrailEvent(
    val tenantId: UUID,
    val actorId: UUID?,
    val actorEmail: String?,
    val action: String,
    val entityType: String? = null,
    val entityId: String? = null,
    val detail: Map<String, Any?> = emptyMap(),
)
