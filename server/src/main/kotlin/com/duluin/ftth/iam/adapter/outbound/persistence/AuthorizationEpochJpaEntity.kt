package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

@Entity
@Table(name = "iam_authorization_epoch")
class AuthorizationEpochJpaEntity(
    id: UUID,
    @Column(nullable = false) var epoch: Long = 0,
) : TenantAwareJpaEntity(id) {
    @Version @Column(nullable = false) var revision: Long = 0
}
