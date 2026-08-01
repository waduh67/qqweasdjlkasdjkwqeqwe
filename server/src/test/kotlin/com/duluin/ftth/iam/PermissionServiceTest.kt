package com.duluin.ftth.iam

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.application.service.PermissionService
import com.duluin.ftth.iam.domain.model.Permission
import com.duluin.ftth.iam.domain.model.vo.PermissionCode
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * Katalog role-builder harus disaring sesuai aktor: admin tenant tak boleh melihat izin
 * platform yang guard simpan-nya bakal menolak (mis. `vpn.server.*`, `platform.tenant.*`).
 * Ini menutup gap UI di mana matriks dulu menampilkan seluruh katalog sistem ke semua orang.
 */
class PermissionServiceTest {

    private val permissions = listOf(
        Permission.create(PermissionCode.of("platform.tenant.view"), "Lihat tenant", platformOnly = true),
        Permission.create(PermissionCode.of("vpn.server.manage"), "Kelola server VPN", platformOnly = true),
        Permission.create(PermissionCode.of("vpn.peer.view"), "Lihat akun VPN", platformOnly = false),
        Permission.create(PermissionCode.of("catalog.plan.view"), "Lihat paket", platformOnly = false),
    )

    @Test
    fun `admin tenant tak melihat izin platform di katalog`() {
        val catalog = service(platformAdmin = false).catalog()

        val codes = catalog.modules.flatMap { it.permissions }.map { it.code }
        assertThat(codes).containsExactlyInAnyOrder("vpn.peer.view", "catalog.plan.view")
        // Modul yang izinnya seluruhnya platform (mis. `platform`) ikut hilang, bukan tampil kosong.
        assertThat(catalog.modules.map { it.module }).doesNotContain("platform")
    }

    @Test
    fun `admin platform melihat seluruh katalog`() {
        val catalog = service(platformAdmin = true).catalog()

        val codes = catalog.modules.flatMap { it.permissions }.map { it.code }
        assertThat(codes).containsExactlyInAnyOrder(
            "platform.tenant.view", "vpn.server.manage", "vpn.peer.view", "catalog.plan.view",
        )
    }

    private fun service(platformAdmin: Boolean) =
        PermissionService(FakePermissionRepository(permissions), FakeCurrentUser(platformAdmin))

    private class FakePermissionRepository(private val all: List<Permission>) : PermissionRepository {
        override fun save(permission: Permission): Permission = permission
        override fun findAll(): List<Permission> = all
        override fun findAllByIds(ids: Set<UUID>): List<Permission> = all.filter { it.id in ids }
    }

    private class FakeCurrentUser(private val platformAdmin: Boolean) : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser = AuthenticatedUser(
            userId = UuidV7.generate(),
            tenantId = UuidV7.generate(),
            email = "op@tenant.test",
            name = "Operator",
            platformAdmin = platformAdmin,
            permissions = emptySet(),
            areaIds = emptySet(),
        )
    }
}
