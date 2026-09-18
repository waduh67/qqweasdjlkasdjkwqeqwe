package com.duluin.ftth.bng.application.port.outbound

import com.duluin.ftth.bng.domain.model.RadiusServer
import java.util.UUID

interface RadiusServerRepository {
    fun findAll(): List<RadiusServer>
    fun findById(id: UUID): RadiusServer?
    fun existsByName(name: String): Boolean
    fun save(server: RadiusServer): RadiusServer
    fun deleteById(id: UUID)
    fun countActive(): Long
    fun countAssignedTenants(serverId: UUID): Int
    fun countAllAssignedTenants(): Map<UUID, Int>
    fun findAssignedServerForTenant(tenantId: UUID): RadiusServer?
    fun assignTenant(tenantId: UUID, serverId: UUID)
    fun unassignTenant(tenantId: UUID)
    fun findAssignedTenantIds(serverId: UUID): List<UUID>
}
