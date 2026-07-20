package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PermissionJpaRepository : JpaRepository<PermissionJpaEntity, UUID>
