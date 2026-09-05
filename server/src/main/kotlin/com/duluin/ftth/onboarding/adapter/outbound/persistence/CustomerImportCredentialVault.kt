package com.duluin.ftth.onboarding.adapter.outbound.persistence

import com.duluin.ftth.bng.CredentialHandle
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.common.security.SecretCipher
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

enum class CustomerImportCredentialState { SEALED, CONSUMED, PURGED }

@Entity
@Table(name = "customer_import_credential")
class CustomerImportCredentialJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    lateinit var ciphertext: String
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    lateinit var state: CustomerImportCredentialState
    @Column(name = "consumed_at")
    var consumedAt: Instant? = null
}

interface CustomerImportCredentialJpaRepository : JpaRepository<CustomerImportCredentialJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerImportCredentialJpaEntity c where c.id = :id")
    fun findForUpdate(id: UUID): CustomerImportCredentialJpaEntity?
}

@Component
class CustomerImportCredentialVault(
    private val credentials: CustomerImportCredentialJpaRepository,
    private val cipher: SecretCipher,
) {
    @Transactional
    fun seal(secret: String?): CredentialHandle? = secret?.trim()?.takeIf { it.isNotEmpty() }?.let {
        val entity = CustomerImportCredentialJpaEntity().apply {
            ciphertext = cipher.encrypt(it)
            state = CustomerImportCredentialState.SEALED
        }
        credentials.save(entity)
        CredentialHandle(entity.id)
    }

    @Transactional
    fun resolve(handle: CredentialHandle): String {
        val entity = credentials.findForUpdate(handle.id) ?: throw IllegalStateException("Credential handle tidak ditemukan")
        check(entity.state == CustomerImportCredentialState.SEALED) { "Credential handle sudah tidak aktif" }
        return cipher.decrypt(entity.ciphertext)
    }

    @Transactional
    fun consume(handle: CredentialHandle) {
        val entity = credentials.findForUpdate(handle.id) ?: return
        if (entity.state == CustomerImportCredentialState.SEALED) {
            entity.state = CustomerImportCredentialState.CONSUMED
            entity.consumedAt = Instant.now()
            credentials.save(entity)
        }
    }

    @Transactional
    fun purge(handle: CredentialHandle) {
        val entity = credentials.findForUpdate(handle.id) ?: return
        entity.state = CustomerImportCredentialState.PURGED
        entity.consumedAt = Instant.now()
        credentials.save(entity)
    }
}
