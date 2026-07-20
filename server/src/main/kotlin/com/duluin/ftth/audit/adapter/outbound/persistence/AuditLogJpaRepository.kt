package com.duluin.ftth.audit.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuditLogJpaRepository : JpaRepository<AuditLogJpaEntity, UUID>
