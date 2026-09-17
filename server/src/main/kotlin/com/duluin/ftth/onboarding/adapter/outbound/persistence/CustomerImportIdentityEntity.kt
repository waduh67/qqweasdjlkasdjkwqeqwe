package com.duluin.ftth.onboarding.adapter.outbound.persistence

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Transient
import org.hibernate.annotations.TenantId
import org.springframework.data.domain.Persistable
import java.util.UUID

@MappedSuperclass
abstract class CustomerImportIdentityEntity(
    @Id @Column(name = "id", nullable = false, updatable = false) private val primaryKey: UUID,
) : Persistable<UUID> {
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID? = null
        protected set

    @Transient
    private var persisted: Boolean = false

    override fun getId(): UUID = primaryKey
    override fun isNew(): Boolean = !persisted

    @PostLoad
    @PostPersist
    fun markPersisted() {
        persisted = true
    }
}
