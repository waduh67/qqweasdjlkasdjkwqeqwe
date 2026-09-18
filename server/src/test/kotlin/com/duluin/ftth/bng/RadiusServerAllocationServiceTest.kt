package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.RadiusServerRepository
import com.duluin.ftth.bng.application.service.RadiusServerAllocationService
import com.duluin.ftth.bng.domain.model.RadiusServer
import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class RadiusServerAllocationServiceTest {

    @Test
    fun `auto distribute mengalokasikan tenant ke server pertama hingga batas kapasitas`() {
        val repo = InMemoryRadiusServerRepo()
        val serverA = repo.save(
            RadiusServer.create(
                name = "Node A", host = "10.0.0.1", sharedSecret = "secretA",
                dbUrl = "jdbc:postgresql://10.0.0.1/radius", dbUser = "radius", dbPassword = "pw",
                maxTenants = 2,
            ),
        )
        val serverB = repo.save(
            RadiusServer.create(
                name = "Node B", host = "10.0.0.2", sharedSecret = "secretB",
                dbUrl = "jdbc:postgresql://10.0.0.2/radius", dbUser = "radius", dbPassword = "pw",
                maxTenants = 2,
            ),
        )
        val service = RadiusServerAllocationService(repo)

        val tenant1 = UuidV7.generate()
        val tenant2 = UuidV7.generate()
        val tenant3 = UuidV7.generate()

        val allocated1 = service.getOrAllocateServerForTenant(tenant1)
        assertThat(allocated1?.id).isEqualTo(serverA.id)

        // Tenant 2 dialokasikan ke Node B karena least-loaded (Node A sudah punya 1, Node B punya 0)
        val allocated2 = service.getOrAllocateServerForTenant(tenant2)
        assertThat(allocated2?.id).isEqualTo(serverB.id)

        // Tenant 3 dialokasikan ke Node A (keduanya punya 1/2)
        val allocated3 = service.getOrAllocateServerForTenant(tenant3)
        assertThat(allocated3?.id).isIn(serverA.id, serverB.id)
    }

    @Test
    fun `auto distribute melempar ConflictException jika semua node sudah mencapai batas kapasitas`() {
        val repo = InMemoryRadiusServerRepo()
        repo.save(
            RadiusServer.create(
                name = "Node A", host = "10.0.0.1", sharedSecret = "secretA",
                dbUrl = "jdbc:postgresql://10.0.0.1/radius", dbUser = "radius", dbPassword = "pw",
                maxTenants = 2,
            ),
        )
        val service = RadiusServerAllocationService(repo)

        val t1 = UuidV7.generate()
        val t2 = UuidV7.generate()
        val t3 = UuidV7.generate()

        service.getOrAllocateServerForTenant(t1)
        service.getOrAllocateServerForTenant(t2)

        // Node A penuh (2/2) → t3 gagal karena kapasitas habis
        assertThatThrownBy { service.getOrAllocateServerForTenant(t3) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("kapasitas maksimum")
    }

    @Test
    fun `kembalikan null bila belum ada server RADIUS terdaftar di database agar fallback ke default`() {
        val repo = InMemoryRadiusServerRepo()
        val service = RadiusServerAllocationService(repo)

        val t = UuidV7.generate()
        val allocated = service.getOrAllocateServerForTenant(t)
        assertThat(allocated).isNull()
    }

    @Test
    fun `server dengan status DRAINING atau DISABLED tidak menerima tenant baru`() {
        val repo = InMemoryRadiusServerRepo()
        repo.save(
            RadiusServer.create(
                name = "Draining Node", host = "10.0.0.1", sharedSecret = "sec",
                dbUrl = "jdbc:postgresql://10.0.0.1/radius", dbUser = "radius", dbPassword = "pw",
                maxTenants = 5, status = RadiusServerStatus.DRAINING,
            ),
        )
        val active = repo.save(
            RadiusServer.create(
                name = "Active Node", host = "10.0.0.2", sharedSecret = "sec",
                dbUrl = "jdbc:postgresql://10.0.0.2/radius", dbUser = "radius", dbPassword = "pw",
                maxTenants = 2, status = RadiusServerStatus.ACTIVE,
            ),
        )
        val service = RadiusServerAllocationService(repo)

        val t1 = UuidV7.generate()
        val allocated = service.getOrAllocateServerForTenant(t1)
        assertThat(allocated?.id).isEqualTo(active.id)
    }

    @Test
    fun `tenant yang sudah teralokasi tetap mendapat server yang sama`() {
        val repo = InMemoryRadiusServerRepo()
        val server = repo.save(
            RadiusServer.create(
                name = "Node A", host = "10.0.0.1", sharedSecret = "secretA",
                dbUrl = "jdbc:postgresql://10.0.0.1/radius", dbUser = "radius", dbPassword = "pw",
                maxTenants = 2,
            ),
        )
        val service = RadiusServerAllocationService(repo)

        val tenant = UuidV7.generate()
        val first = service.getOrAllocateServerForTenant(tenant)
        val second = service.getOrAllocateServerForTenant(tenant)

        assertThat(first?.id).isEqualTo(server.id)
        assertThat(second?.id).isEqualTo(server.id)
        assertThat(repo.countAssignedTenants(server.id)).isEqualTo(1)
    }

    private class InMemoryRadiusServerRepo : RadiusServerRepository {
        private val servers = mutableMapOf<UUID, RadiusServer>()
        private val assignments = mutableMapOf<UUID, UUID>() // tenantId -> serverId

        override fun findAll(): List<RadiusServer> = servers.values.toList()

        override fun findById(id: UUID): RadiusServer? = servers[id]

        override fun existsByName(name: String): Boolean = servers.values.any { it.name.equals(name.trim(), ignoreCase = true) }

        override fun save(server: RadiusServer): RadiusServer {
            servers[server.id] = server
            return server
        }

        override fun deleteById(id: UUID) {
            servers.remove(id)
        }

        override fun countActive(): Long = servers.values.count { it.status == RadiusServerStatus.ACTIVE }.toLong()

        override fun countAssignedTenants(serverId: UUID): Int = assignments.values.count { it == serverId }

        override fun countAllAssignedTenants(): Map<UUID, Int> =
            assignments.values.groupingBy { it }.eachCount()

        override fun findAssignedServerForTenant(tenantId: UUID): RadiusServer? =
            assignments[tenantId]?.let { servers[it] }

        override fun assignTenant(tenantId: UUID, serverId: UUID) {
            assignments[tenantId] = serverId
        }

        override fun unassignTenant(tenantId: UUID) {
            assignments.remove(tenantId)
        }

        override fun findAssignedTenantIds(serverId: UUID): List<UUID> =
            assignments.filterValues { it == serverId }.keys.toList()
    }
}
