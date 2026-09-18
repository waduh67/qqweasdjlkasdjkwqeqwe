package com.duluin.ftth.bng.application.port.inbound

import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import java.util.UUID

data class RadiusServerView(
    val id: UUID,
    val name: String,
    val host: String,
    val authPort: Int,
    val acctPort: Int,
    val coaPort: Int,
    val sharedSecret: String,
    val dbUrl: String,
    val dbUser: String,
    val maxTenants: Int,
    val tenantCount: Int,
    val status: RadiusServerStatus,
)

data class RadiusServerDetailView(
    val server: RadiusServerView,
    val assignedTenantIds: List<UUID>,
)

data class CreateRadiusServerCommand(
    val name: String,
    val host: String,
    val authPort: Int = 1812,
    val acctPort: Int = 1813,
    val coaPort: Int = 3799,
    val sharedSecret: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val maxTenants: Int = 2,
    val status: RadiusServerStatus = RadiusServerStatus.ACTIVE,
)

data class UpdateRadiusServerCommand(
    val name: String,
    val host: String,
    val authPort: Int = 1812,
    val acctPort: Int = 1813,
    val coaPort: Int = 3799,
    val sharedSecret: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String?,
    val maxTenants: Int = 2,
    val status: RadiusServerStatus = RadiusServerStatus.ACTIVE,
)

data class TestConnectionCommand(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
)

data class TestConnectionResult(
    val success: Boolean,
    val message: String,
)

interface ManageRadiusServerUseCase {
    fun list(): List<RadiusServerView>
    fun get(id: UUID): RadiusServerDetailView
    fun create(command: CreateRadiusServerCommand): RadiusServerView
    fun update(id: UUID, command: UpdateRadiusServerCommand): RadiusServerView
    fun delete(id: UUID)
    fun testConnection(command: TestConnectionCommand): TestConnectionResult
    fun testServerConnection(id: UUID): TestConnectionResult
}
