package com.karuhun.ftth.audit.application.port.outbound

import com.karuhun.ftth.audit.domain.model.AuditEntry
import com.karuhun.ftth.common.domain.Page
import com.karuhun.ftth.common.domain.PageRequest

interface AuditRepository {

    fun save(entry: AuditEntry): AuditEntry

    fun findAll(pageRequest: PageRequest): Page<AuditEntry>
}
