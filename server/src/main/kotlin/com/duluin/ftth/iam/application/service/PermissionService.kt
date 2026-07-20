package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.application.port.inbound.ModulePermissionsView
import com.duluin.ftth.iam.application.port.inbound.PermissionCatalogView
import com.duluin.ftth.iam.application.port.inbound.PermissionQuery
import com.duluin.ftth.iam.application.port.inbound.PermissionView
import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PermissionService(
    private val permissionRepository: PermissionRepository,
) : PermissionQuery {

    override fun listAll(): List<PermissionView> =
        permissionRepository.findAll().map { it.toView() }.sortedBy { it.code }

    override fun catalog(): PermissionCatalogView {
        val modules = listAll()
            .groupBy { it.module }
            .map { (module, permissions) -> ModulePermissionsView(module, permissions) }
            .sortedBy { it.module }
        return PermissionCatalogView(modules)
    }
}
