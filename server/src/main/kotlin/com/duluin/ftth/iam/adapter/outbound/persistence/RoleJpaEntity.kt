package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.util.UUID

@Entity
@Table(name = "role")
class RoleJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 255)
    var description: String?,

    @Column(name = "system_role", nullable = false)
    var systemRole: Boolean,

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_permission", joinColumns = [JoinColumn(name = "role_id")])
    @Column(name = "permission_id", nullable = false)
    @BatchSize(size = 50)
    var permissionIds: MutableSet<UUID> = mutableSetOf(),
) : TenantAwareJpaEntity(id)
