package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.PermissionCatalogView
import com.duluin.ftth.iam.application.port.inbound.PermissionQuery
import com.duluin.ftth.iam.application.port.inbound.PermissionView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/permissions")
@Tag(name = "Permissions")
@SecurityRequirement(name = "bearer-jwt")
class PermissionController(
    private val permissionQuery: PermissionQuery,
) {
    @GetMapping
    @PreAuthorize("@authz.can('iam.permission.view')")
    fun list(): List<PermissionView> = permissionQuery.listAll()

    /** Katalog izin dikelompokkan per module — untuk matriks role-builder. */
    @GetMapping("/catalog")
    @PreAuthorize("@authz.can('iam.permission.view')")
    fun catalog(): PermissionCatalogView = permissionQuery.catalog()
}
