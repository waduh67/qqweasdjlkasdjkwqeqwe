package com.duluin.ftth.common.infrastructure.audit

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Pencatat jejak audit yang dipakai seluruh module bisnis.
 *
 * Menyatukan pengisian aktor dan bentuk event di satu tempat. Kalau tiap module
 * menyusun [AuditTrailEvent] sendiri, cepat atau lambat ada yang lupa mengisi
 * aktor atau memakai nama aksi berbeda untuk hal yang sama — dan jejak audit
 * yang tidak konsisten lebih berbahaya daripada tidak ada, karena tetap dipercaya.
 *
 * Publikasinya sinkron; module `audit` yang memutuskan kapan menulisnya (setelah
 * commit) dan menjamin kegagalan menulis audit tidak menggagalkan operasi bisnis.
 */
@Component
class AuditRecorder(
    private val events: ApplicationEventPublisher,
    private val currentUser: CurrentUserProvider,
) {
    fun record(
        action: String,
        entityType: String,
        entityId: UUID,
        tenantId: UUID,
        detail: Map<String, Any?> = emptyMap(),
    ) {
        val actor = currentUser.currentOrNull()
        events.publishEvent(
            AuditTrailEvent(
                tenantId = tenantId,
                actorId = actor?.userId,
                actorEmail = actor?.email,
                action = action,
                entityType = entityType,
                entityId = entityId.toString(),
                detail = detail,
            ),
        )
    }
}
