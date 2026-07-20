package com.duluin.ftth.common.infrastructure.persistence

import com.duluin.ftth.common.domain.UuidV7
import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

/**
 * Superclass semua JPA entity (bukan model domain — ini murni detail
 * persistence di lapisan adapter). Id UUIDv7 di-assign aplikasi.
 *
 * Mengimplementasikan [Persistable] agar Spring Data tetap memakai `persist`
 * (bukan `merge`) walau id sudah terisi sebelum disimpan.
 */
@MappedSuperclass
abstract class BaseJpaEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private val entityId: UUID = UuidV7.generate(),
) : Persistable<UUID> {

    @Transient
    private var persisted: Boolean = false

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
        protected set

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
        protected set

    override fun getId(): UUID = entityId

    override fun isNew(): Boolean = !persisted

    @PostPersist
    @PostLoad
    fun markPersisted() {
        persisted = true
    }

    @PreUpdate
    fun touchUpdatedAt() {
        updatedAt = Instant.now()
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is BaseJpaEntity && other.javaClass == javaClass && other.entityId == entityId)

    override fun hashCode(): Int = entityId.hashCode()
}
