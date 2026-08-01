package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.application.port.inbound.ModulePermissionsView
import com.duluin.ftth.iam.application.port.inbound.PermissionCatalogView
import com.duluin.ftth.iam.application.port.inbound.PermissionQuery
import com.duluin.ftth.iam.application.port.inbound.PermissionView
import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PermissionService(
    private val permissionRepository: PermissionRepository,
    private val currentUser: CurrentUserProvider,
) : PermissionQuery {

    override fun listAll(): List<PermissionView> =
        permissionRepository.findAll().map { it.toView() }.sortedBy { it.code }

    /**
     * Katalog untuk role-builder, DISARING sesuai aktor: admin tenant biasa tak melihat
     * izin platform (mis. `vpn.server.*`, modul `platform.tenant.*`) yang memang tak bisa
     * ia berikan — guard simpan di [RoleService] menolaknya. Admin platform tetap melihat
     * semuanya. Cermin dari guard simpan itu, jadi keputusan "boleh di-grant" hidup di satu
     * pemahaman yang sama; tanpa ini matriks menampilkan seluruh katalog sistem ke semua
     * orang sehingga checkbox yang mustahil di-grant tetap muncul (baru gagal saat save).
     */
    override fun catalog(): PermissionCatalogView {
        val platformAdmin = currentUser.currentOrNull()?.platformAdmin ?: false
        val modules = listAll()
            .filter { platformAdmin || !it.platformOnly }
            .groupBy { it.module }
            .map { (module, permissions) -> ModulePermissionsView(module, permissions) }
            .sortedBy { it.module }
        return PermissionCatalogView(modules)
    }
}
