package com.duluin.ftth.bng.adapter.inbound.web

import com.duluin.ftth.bng.application.port.inbound.CreateRadiusServerCommand
import com.duluin.ftth.bng.application.port.inbound.ManageRadiusServerUseCase
import com.duluin.ftth.bng.application.port.inbound.RadiusServerDetailView
import com.duluin.ftth.bng.application.port.inbound.RadiusServerView
import com.duluin.ftth.bng.application.port.inbound.TestConnectionCommand
import com.duluin.ftth.bng.application.port.inbound.TestConnectionResult
import com.duluin.ftth.bng.application.port.inbound.UpdateRadiusServerCommand
import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/platform/radius-servers")
@Tag(name = "RADIUS — server platform multi-cluster (admin platform)")
@SecurityRequirement(name = "bearer-jwt")
class RadiusServerController(
    private val servers: ManageRadiusServerUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('radius.server.view')")
    @Operation(summary = "Daftar node server RADIUS platform")
    fun list(): List<RadiusServerView> = servers.list()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('radius.server.view')")
    @Operation(summary = "Detail satu node server RADIUS beserta tenant yang terhubung")
    fun get(@PathVariable id: UUID): RadiusServerDetailView = servers.get(id)

    @PostMapping
    @PreAuthorize("@authz.can('radius.server.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Daftarkan node server RADIUS baru")
    fun create(@Valid @RequestBody request: CreateRadiusServerRequest): RadiusServerView =
        servers.create(request.toCommand())

    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('radius.server.manage')")
    @Operation(summary = "Perbarui konfigurasi node server RADIUS")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateRadiusServerRequest,
    ): RadiusServerView = servers.update(id, request.toCommand())

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.can('radius.server.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Hapus node server RADIUS (ditolak bila masih ada tenant)")
    fun delete(@PathVariable id: UUID) = servers.delete(id)

    @PostMapping("/test-connection")
    @PreAuthorize("@authz.can('radius.server.manage')")
    @Operation(summary = "Uji koneksi JDBC database radius-db")
    fun testConnection(@Valid @RequestBody request: TestConnectionRequest): TestConnectionResult =
        servers.testConnection(request.toCommand())

    @PostMapping("/{id}/test-connection")
    @PreAuthorize("@authz.can('radius.server.manage')")
    @Operation(summary = "Uji koneksi JDBC database node RADIUS yang sudah tersimpan")
    fun testServerConnection(@PathVariable id: UUID): TestConnectionResult =
        servers.testServerConnection(id)
}

data class CreateRadiusServerRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val host: String,
    @field:Min(1) @field:Max(65535) val authPort: Int = 1812,
    @field:Min(1) @field:Max(65535) val acctPort: Int = 1813,
    @field:Min(1) @field:Max(65535) val coaPort: Int = 3799,
    @field:NotBlank val sharedSecret: String,
    @field:NotBlank val dbUrl: String,
    @field:NotBlank val dbUser: String,
    @field:NotBlank val dbPassword: String,
    @field:Min(1) val maxTenants: Int = 2,
    val status: RadiusServerStatus = RadiusServerStatus.ACTIVE,
) {
    fun toCommand() = CreateRadiusServerCommand(
        name = name,
        host = host,
        authPort = authPort,
        acctPort = acctPort,
        coaPort = coaPort,
        sharedSecret = sharedSecret,
        dbUrl = dbUrl,
        dbUser = dbUser,
        dbPassword = dbPassword,
        maxTenants = maxTenants,
        status = status,
    )
}

data class UpdateRadiusServerRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val host: String,
    @field:Min(1) @field:Max(65535) val authPort: Int = 1812,
    @field:Min(1) @field:Max(65535) val acctPort: Int = 1813,
    @field:Min(1) @field:Max(65535) val coaPort: Int = 3799,
    @field:NotBlank val sharedSecret: String,
    @field:NotBlank val dbUrl: String,
    @field:NotBlank val dbUser: String,
    val dbPassword: String? = null,
    @field:Min(1) val maxTenants: Int = 2,
    val status: RadiusServerStatus = RadiusServerStatus.ACTIVE,
) {
    fun toCommand() = UpdateRadiusServerCommand(
        name = name,
        host = host,
        authPort = authPort,
        acctPort = acctPort,
        coaPort = coaPort,
        sharedSecret = sharedSecret,
        dbUrl = dbUrl,
        dbUser = dbUser,
        dbPassword = dbPassword,
        maxTenants = maxTenants,
        status = status,
    )
}

data class TestConnectionRequest(
    @field:NotBlank val dbUrl: String,
    @field:NotBlank val dbUser: String,
    @field:NotBlank val dbPassword: String,
) {
    fun toCommand() = TestConnectionCommand(dbUrl, dbUser, dbPassword)
}
