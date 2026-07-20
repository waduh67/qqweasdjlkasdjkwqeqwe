package com.duluin.ftth.iam.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * DTO hasil (read model) lapisan application — netral terhadap web/persistence.
 * Controller memetakannya ke response HTTP; service menghasilkannya dari domain.
 */

data class PermissionView(
    val id: UUID,
    val code: String,
    val module: String,
    val resource: String,
    val action: String,
    val description: String?,
    val platformOnly: Boolean,
)

/** Katalog izin dikelompokkan per module — bentuk yang enak dipakai UI role-builder. */
data class PermissionCatalogView(
    val modules: List<ModulePermissionsView>,
)

data class ModulePermissionsView(
    val module: String,
    val permissions: List<PermissionView>,
)

data class RoleView(
    val id: UUID,
    val name: String,
    val description: String?,
    val systemRole: Boolean,
    val permissionIds: List<UUID>,
)

data class AreaView(
    val id: UUID,
    val code: String,
    val name: String,
    val parentId: UUID?,
)

data class UserView(
    val id: UUID,
    val email: String,
    val name: String,
    val status: String,
    val platformAdmin: Boolean,
    val roleIds: List<UUID>,
    val areaIds: List<UUID>,
    val createdAt: Instant,
)

/** Profil ringkas untuk endpoint `/me` dan payload login — apa yang perlu diketahui UI. */
data class AuthUserView(
    val id: UUID,
    val email: String,
    val name: String,
    val tenantId: UUID,
    val tenantSlug: String,
    val platformAdmin: Boolean,
    val roleIds: List<UUID>,
    val permissions: List<String>,
    val areaIds: List<UUID>,
)
