package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Entitas JPA untuk node/server FreeRADIUS platform (multi-server cluster).
 * Kredensial rahasia [dbPassword] disimpan dalam bentuk terenkripsi.
 */
@Entity
@Table(name = "radius_server")
class RadiusServerJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 100, unique = true)
    var name: String,

    @Column(nullable = false, length = 255)
    var host: String,

    @Column(name = "auth_port", nullable = false)
    var authPort: Int,

    @Column(name = "acct_port", nullable = false)
    var acctPort: Int,

    @Column(name = "coa_port", nullable = false)
    var coaPort: Int,

    @Column(name = "shared_secret", nullable = false, length = 255)
    var sharedSecret: String,

    @Column(name = "db_url", nullable = false, length = 500)
    var dbUrl: String,

    @Column(name = "db_user", nullable = false, length = 100)
    var dbUser: String,

    @Column(name = "db_password", columnDefinition = "text", nullable = false)
    var dbPassword: String,

    @Column(name = "max_tenants", nullable = false)
    var maxTenants: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: RadiusServerStatus,
) : BaseJpaEntity(id)

/**
 * Pemetaan tenant ke node RADIUS yang dialokasikan untuknya.
 */
@Entity
@Table(name = "tenant_radius_server")
class TenantRadiusAssignmentJpaEntity(
    @Id
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID,

    @Column(name = "radius_server_id", nullable = false)
    var radiusServerId: UUID,
) {
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
