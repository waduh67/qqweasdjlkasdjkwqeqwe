package com.karuhun.ftth.onboarding.adapter.outbound.persistence

import com.karuhun.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

@Entity
@Table(name = "customer_migration")
class CustomerMigrationJpaEntity : TenantAwareJpaEntity(UUID.randomUUID()) {
    @Column(nullable = false) lateinit var customerId: UUID
    @Column(nullable = false) lateinit var subscriptionId: UUID
    @Column(nullable = false, length = 240) lateinit var operationKey: String
    @Column(nullable = false, length = 64) lateinit var requestHash: String
    @Column(nullable = false, length = 240) lateinit var sourceKey: String
    @Column(nullable = false, length = 16) lateinit var source: String
    @Column(length = 200) var username: String? = null
    @Column(columnDefinition = "text") var secretCiphertext: String? = null
    @Column(nullable = false, length = 16) var authType: String = "PPPOE"
    var nasId: UUID? = null
    @Column(length = 45) var framedIp: String? = null
    var accessId: UUID? = null
}

interface CustomerMigrationJpaRepository : JpaRepository<CustomerMigrationJpaEntity, UUID> {
    fun findByOperationKey(operationKey: String): CustomerMigrationJpaEntity?
    fun findBySourceKey(sourceKey: String): CustomerMigrationJpaEntity?
    fun findByCustomerId(customerId: UUID): CustomerMigrationJpaEntity?
}
