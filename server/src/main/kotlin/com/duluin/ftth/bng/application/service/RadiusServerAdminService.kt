package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.adapter.outbound.radius.RadiusConnectionResolver
import com.duluin.ftth.bng.application.port.inbound.CreateRadiusServerCommand
import com.duluin.ftth.bng.application.port.inbound.ManageRadiusServerUseCase
import com.duluin.ftth.bng.application.port.inbound.RadiusServerDetailView
import com.duluin.ftth.bng.application.port.inbound.RadiusServerView
import com.duluin.ftth.bng.application.port.inbound.TestConnectionCommand
import com.duluin.ftth.bng.application.port.inbound.TestConnectionResult
import com.duluin.ftth.bng.application.port.inbound.UpdateRadiusServerCommand
import com.duluin.ftth.bng.application.port.outbound.RadiusServerRepository
import com.duluin.ftth.bng.domain.model.RadiusServer
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.duluin.ftth.common.security.CurrentUserProvider
import java.sql.DriverManager
import java.util.UUID

@Service
@Transactional
class RadiusServerAdminService(
    private val serverRepository: RadiusServerRepository,
    private val connectionResolver: RadiusConnectionResolver,
    private val auditor: AuditRecorder,
    private val currentUser: CurrentUserProvider? = null,
) : ManageRadiusServerUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun auditorTenantId(): UUID =
        currentUser?.currentOrNull()?.tenantId ?: TenantContext.tenantIdOrNull() ?: UUID(0, 0)

    @Transactional(readOnly = true)
    override fun list(): List<RadiusServerView> {
        val all = serverRepository.findAll()
        val counts = serverRepository.countAllAssignedTenants()
        return all.map { it.toView(counts[it.id] ?: 0) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): RadiusServerDetailView {
        val server = require(id)
        val count = serverRepository.countAssignedTenants(id)
        val tenantIds = serverRepository.findAssignedTenantIds(id)
        return RadiusServerDetailView(
            server = server.toView(count),
            assignedTenantIds = tenantIds,
        )
    }

    override fun create(command: CreateRadiusServerCommand): RadiusServerView {
        val name = command.name.trim()
        if (serverRepository.existsByName(name)) {
            throw ConflictException("Server RADIUS '$name' sudah ada")
        }
        val server = RadiusServer.create(
            name = command.name,
            host = command.host,
            authPort = command.authPort,
            acctPort = command.acctPort,
            coaPort = command.coaPort,
            sharedSecret = command.sharedSecret,
            dbUrl = command.dbUrl,
            dbUser = command.dbUser,
            dbPassword = command.dbPassword,
            maxTenants = command.maxTenants,
            status = command.status,
        )
        val saved = serverRepository.save(server)
        val actorTenantId = auditorTenantId()
        auditor.record(
            "bng.radius-server.created",
            "RadiusServer",
            saved.id,
            actorTenantId,
            mapOf("name" to saved.name, "host" to saved.host, "maxTenants" to saved.maxTenants),
        )
        return saved.toView(0)
    }

    override fun update(id: UUID, command: UpdateRadiusServerCommand): RadiusServerView {
        val server = require(id)
        val newName = command.name.trim()
        if (newName != server.name && serverRepository.existsByName(newName)) {
            throw ConflictException("Server RADIUS '$newName' sudah ada")
        }
        server.update(
            name = command.name,
            host = command.host,
            authPort = command.authPort,
            acctPort = command.acctPort,
            coaPort = command.coaPort,
            sharedSecret = command.sharedSecret,
            dbUrl = command.dbUrl,
            dbUser = command.dbUser,
            dbPassword = command.dbPassword,
            maxTenants = command.maxTenants,
            status = command.status,
        )
        val saved = serverRepository.save(server)
        connectionResolver.evictPool(saved.id)
        val actorTenantId = auditorTenantId()
        auditor.record(
            "bng.radius-server.updated",
            "RadiusServer",
            saved.id,
            actorTenantId,
            mapOf("name" to saved.name, "host" to saved.host, "status" to saved.status.name),
        )
        val count = serverRepository.countAssignedTenants(saved.id)
        return saved.toView(count)
    }

    override fun delete(id: UUID) {
        val server = require(id)
        val count = serverRepository.countAssignedTenants(id)
        if (count > 0) {
            throw ConflictException("Server RADIUS '${server.name}' masih menaungi $count tenant, pindahkan tenant dulu sebelum menghapus")
        }
        serverRepository.deleteById(id)
        connectionResolver.evictPool(id)
        val actorTenantId = auditorTenantId()
        auditor.record(
            "bng.radius-server.deleted",
            "RadiusServer",
            id,
            actorTenantId,
            mapOf("name" to server.name),
        )
    }

    @Transactional(readOnly = true)
    override fun testConnection(command: TestConnectionCommand): TestConnectionResult {
        return try {
            DriverManager.setLoginTimeout(5)
            DriverManager.getConnection(command.dbUrl.trim(), command.dbUser.trim(), command.dbPassword.trim()).use { conn ->
                val valid = conn.isValid(5)
                if (valid) {
                    TestConnectionResult(true, "Koneksi ke database RADIUS berhasil terhubung!")
                } else {
                    TestConnectionResult(false, "Koneksi database dibuka namun tidak valid.")
                }
            }
        } catch (ex: Exception) {
            log.warn("Test koneksi radius-db gagal: {}", ex.message)
            TestConnectionResult(false, "Gagal terhubung: ${ex.message}")
        }
    }

    @Transactional(readOnly = true)
    override fun testServerConnection(id: UUID): TestConnectionResult {
        val server = require(id)
        return testConnection(
            TestConnectionCommand(
                dbUrl = server.dbUrl,
                dbUser = server.dbUser,
                dbPassword = server.dbPassword,
            ),
        )
    }

    private fun require(id: UUID): RadiusServer =
        serverRepository.findById(id) ?: throw NotFoundException("Server RADIUS $id tidak ditemukan")
}

private fun RadiusServer.toView(tenantCount: Int) = RadiusServerView(
    id = id,
    name = name,
    host = host,
    authPort = authPort,
    acctPort = acctPort,
    coaPort = coaPort,
    sharedSecret = sharedSecret,
    dbUrl = dbUrl,
    dbUser = dbUser,
    maxTenants = maxTenants,
    tenantCount = tenantCount,
    status = status,
)
