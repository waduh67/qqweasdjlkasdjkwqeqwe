package com.duluin.ftth.audit.application.port.outbound

import com.duluin.ftth.audit.domain.model.AuditEntry
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest

interface AuditRepository {

    fun save(entry: AuditEntry): AuditEntry

    fun findAll(pageRequest: PageRequest): Page<AuditEntry>
}
