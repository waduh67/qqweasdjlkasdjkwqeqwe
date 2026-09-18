package com.duluin.ftth.bng

import com.duluin.ftth.bng.adapter.outbound.radius.RadiusConnectionResolver
import com.duluin.ftth.bng.application.port.inbound.CreateRadiusServerCommand
import com.duluin.ftth.bng.application.port.inbound.UpdateRadiusServerCommand
import com.duluin.ftth.bng.application.port.outbound.RadiusServerRepository
import com.duluin.ftth.bng.application.service.RadiusServerAdminService
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.bng.domain.model.RadiusServer
import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

class RadiusServerAdminServiceTest {

    private val currentUser = object : CurrentUserProvider {
        override fun currentOrNull() = AuthenticatedUser(
            userId = UuidV7.generate(), tenantId = UuidV7.generate(), email = "admin@platform.id",
            name = "Admin", platformAdmin = true, permissions = setOf("radius.server.manage"), areaIds = emptySet(),
        )
    }
    private val auditor = AuditRecorder(ApplicationEventPublisher { }, currentUser)

    @Test
    fun `create server baru berhasil dan muncul di list`() {
        val repo = InMemoryRadiusServerRepo()
        val resolver = RadiusConnectionResolver(RadiusProperties())
        val service = RadiusServerAdminService(repo, resolver, auditor)

        val created = service.create(
            CreateRadiusServerCommand(
                name = "VPS SG 1",
                host = "103.10.10.1",
                sharedSecret = "s3cr3t",
                dbUrl = "jdbc:postgresql://103.10.10.1/radius",
                dbUser = "radius",
                dbPassword = "password",
                maxTenants = 2,
            ),
        )

        assertThat(created.name).isEqualTo("VPS SG 1")
        assertThat(created.host).isEqualTo("103.10.10.1")
        assertThat(created.tenantCount).isEqualTo(0)
        assertThat(service.list()).hasSize(1)
    }

    @Test
    fun `create menolak nama yang sudah ada`() {
        val repo = InMemoryRadiusServerRepo()
        val resolver = RadiusConnectionResolver(RadiusProperties())
        val service = RadiusServerAdminService(repo, resolver, auditor)

        service.create(
            CreateRadiusServerCommand(
                name = "VPS SG 1", host = "103.10.10.1", sharedSecret = "s3cr3t",
                dbUrl = "jdbc:postgresql://103.10.10.1/radius", dbUser = "radius", dbPassword = "password",
            ),
        )

        assertThatThrownBy {
            service.create(
                CreateRadiusServerCommand(
                    name = "VPS SG 1", host = "103.10.10.2", sharedSecret = "diff",
                    dbUrl = "jdbc:postgresql://103.10.10.2/radius", dbUser = "radius", dbPassword = "password",
                ),
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `delete menolak server yang masih menaungi tenant`() {
        val repo = InMemoryRadiusServerRepo()
        val resolver = RadiusConnectionResolver(RadiusProperties())
        val service = RadiusServerAdminService(repo, resolver, auditor)

        val created = service.create(
            CreateRadiusServerCommand(
                name = "VPS SG 1", host = "103.10.10.1", sharedSecret = "s3cr3t",
                dbUrl = "jdbc:postgresql://103.10.10.1/radius", dbUser = "radius", dbPassword = "password",
            ),
        )

        // Simulasikan ada 1 tenant tertaut
        repo.assignTenant(UuidV7.generate(), created.id)

        assertThatThrownBy { service.delete(created.id) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("masih menaungi 1 tenant")
    }

    @Test
    fun `delete berhasil jika 0 tenant`() {
        val repo = InMemoryRadiusServerRepo()
        val resolver = RadiusConnectionResolver(RadiusProperties())
        val service = RadiusServerAdminService(repo, resolver, auditor)

        val created = service.create(
            CreateRadiusServerCommand(
                name = "VPS SG 1", host = "103.10.10.1", sharedSecret = "s3cr3t",
                dbUrl = "jdbc:postgresql://103.10.10.1/radius", dbUser = "radius", dbPassword = "password",
            ),
        )

        service.delete(created.id)
        assertThat(service.list()).isEmpty()
    }

    private class InMemoryRadiusServerRepo : RadiusServerRepository {
        private val servers = mutableMapOf<UUID, RadiusServer>()
        private val assignments = mutableMapOf<UUID, UUID>()

        override fun findAll(): List<RadiusServer> = servers.values.toList()
        override fun findById(id: UUID): RadiusServer? = servers[id]
        override fun existsByName(name: String): Boolean = servers.values.any { it.name.equals(name.trim(), ignoreCase = true) }
        override fun save(server: RadiusServer): RadiusServer { servers[server.id] = server; return server }
        override fun deleteById(id: UUID) { servers.remove(id) }
        override fun countActive(): Long = servers.values.count { it.status == RadiusServerStatus.ACTIVE }.toLong()
        override fun countAssignedTenants(serverId: UUID): Int = assignments.values.count { it == serverId }
        override fun countAllAssignedTenants(): Map<UUID, Int> = assignments.values.groupingBy { it }.eachCount()
        override fun findAssignedServerForTenant(tenantId: UUID): RadiusServer? = assignments[tenantId]?.let { servers[it] }
        override fun assignTenant(tenantId: UUID, serverId: UUID) { assignments[tenantId] = serverId }
        override fun unassignTenant(tenantId: UUID) { assignments.remove(tenantId) }
        override fun findAssignedTenantIds(serverId: UUID): List<UUID> = assignments.filterValues { it == serverId }.keys.toList()
    }
}
