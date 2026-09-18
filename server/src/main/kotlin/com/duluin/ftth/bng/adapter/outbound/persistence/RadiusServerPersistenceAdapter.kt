package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.application.port.outbound.RadiusServerRepository
import com.duluin.ftth.bng.domain.model.RadiusServer
import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import com.duluin.ftth.common.security.SecretCipher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class RadiusServerPersistenceAdapter(
    private val serverJpa: RadiusServerJpaRepository,
    private val assignmentJpa: TenantRadiusAssignmentJpaRepository,
    private val cipher: SecretCipher,
) : RadiusServerRepository {

    override fun findAll(): List<RadiusServer> =
        serverJpa.findAll().map { it.toDomain() }

    override fun findById(id: UUID): RadiusServer? =
        serverJpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun existsByName(name: String): Boolean =
        serverJpa.existsByName(name.trim())

    @Transactional
    override fun save(server: RadiusServer): RadiusServer {
        val encryptedPassword = cipher.encrypt(server.dbPassword)
        val entity = serverJpa.findById(server.id).orElse(null)?.apply {
            name = server.name
            host = server.host
            authPort = server.authPort
            acctPort = server.acctPort
            coaPort = server.coaPort
            sharedSecret = server.sharedSecret
            dbUrl = server.dbUrl
            dbUser = server.dbUser
            dbPassword = encryptedPassword
            maxTenants = server.maxTenants
            status = server.status
        } ?: RadiusServerJpaEntity(
            id = server.id,
            name = server.name,
            host = server.host,
            authPort = server.authPort,
            acctPort = server.acctPort,
            coaPort = server.coaPort,
            sharedSecret = server.sharedSecret,
            dbUrl = server.dbUrl,
            dbUser = server.dbUser,
            dbPassword = encryptedPassword,
            maxTenants = server.maxTenants,
            status = server.status,
        )
        val saved = serverJpa.save(entity)
        return saved.toDomain()
    }

    @Transactional
    override fun deleteById(id: UUID) {
        serverJpa.deleteById(id)
    }

    override fun countActive(): Long =
        serverJpa.countByStatus(RadiusServerStatus.ACTIVE)

    override fun countAssignedTenants(serverId: UUID): Int =
        assignmentJpa.countByRadiusServerId(serverId)

    override fun countAllAssignedTenants(): Map<UUID, Int> {
        val rows = assignmentJpa.countGroupedByRadiusServerId()
        return rows.associate {
            val serverId = it[0] as UUID
            val count = (it[1] as Number).toInt()
            serverId to count
        }
    }

    override fun findAssignedServerForTenant(tenantId: UUID): RadiusServer? {
        val assignment = assignmentJpa.findByTenantId(tenantId) ?: return null
        return findById(assignment.radiusServerId)
    }

    @Transactional
    override fun assignTenant(tenantId: UUID, serverId: UUID) {
        val existing = assignmentJpa.findByTenantId(tenantId)
        if (existing != null) {
            existing.radiusServerId = serverId
            existing.updatedAt = Instant.now()
            assignmentJpa.save(existing)
        } else {
            assignmentJpa.save(
                TenantRadiusAssignmentJpaEntity(
                    tenantId = tenantId,
                    radiusServerId = serverId,
                ),
            )
        }
    }

    @Transactional
    override fun unassignTenant(tenantId: UUID) {
        assignmentJpa.deleteByTenantId(tenantId)
    }

    override fun findAssignedTenantIds(serverId: UUID): List<UUID> =
        assignmentJpa.findByRadiusServerId(serverId).map { it.tenantId }

    private fun RadiusServerJpaEntity.toDomain(): RadiusServer {
        val decryptedPassword = runCatching { cipher.decrypt(dbPassword) }.getOrDefault(dbPassword)
        return RadiusServer.reconstitute(
            id = id,
            name = name,
            host = host,
            authPort = authPort,
            acctPort = acctPort,
            coaPort = coaPort,
            sharedSecret = sharedSecret,
            dbUrl = dbUrl,
            dbUser = dbUser,
            dbPassword = decryptedPassword,
            maxTenants = maxTenants,
            status = status,
        )
    }
}
