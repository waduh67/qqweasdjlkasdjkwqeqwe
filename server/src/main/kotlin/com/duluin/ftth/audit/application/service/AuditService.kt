package com.duluin.ftth.audit.application.service

import com.duluin.ftth.audit.application.port.inbound.AuditEntryView
import com.duluin.ftth.audit.application.port.inbound.AuditQuery
import com.duluin.ftth.audit.application.port.outbound.AuditRepository
import com.duluin.ftth.audit.domain.model.AuditEntry
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuditService(
    private val auditRepository: AuditRepository,
) : AuditQuery {

    override fun list(pageRequest: PageRequest): Page<AuditEntryView> =
        auditRepository.findAll(pageRequest).map { it.toView() }
}

private fun AuditEntry.toView() = AuditEntryView(
    id = id,
    actorId = actorId,
    actorEmail = actorEmail,
    action = action,
    entityType = entityType,
    entityId = entityId,
    detail = detail,
    occurredAt = occurredAt,
)
