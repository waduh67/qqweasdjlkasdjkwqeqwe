package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.application.port.inbound.AuthUserView
import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Component

/**
 * Menyusun kumpulan kode izin efektif seorang user (union izin dari seluruh
 * role-nya) dan merakit [AuthUserView]. Dipakai bersama oleh login, refresh, /me.
 * Harus dipanggil dalam tenant context yang benar (query role/permission ter-scope).
 */
@Component
class AuthViewAssembler(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val tenantApi: TenantApi,
) {
    fun permissionCodesFor(user: User): Set<String> {
        if (user.roleIds.isEmpty()) return emptySet()
        val permissionIds = roleRepository.findAllByIds(user.roleIds)
            .flatMapTo(HashSet()) { it.permissionIds }
        if (permissionIds.isEmpty()) return emptySet()
        return permissionRepository.findAllByIds(permissionIds).mapTo(HashSet()) { it.code.value }
    }

    fun toAuthUserView(user: User, permissionCodes: Set<String>): AuthUserView {
        val tenant = tenantApi.requireById(user.tenantId)
        return AuthUserView(
            id = user.id,
            email = user.email.value,
            name = user.name,
            tenantId = user.tenantId,
            tenantSlug = tenant.slug,
            platformAdmin = user.platformAdmin,
            roleIds = user.roleIds.toList(),
            permissions = permissionCodes.sorted(),
            areaIds = user.areaIds.toList(),
        )
    }
}
