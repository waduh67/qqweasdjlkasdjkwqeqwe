package com.duluin.ftth.iam.application.port.inbound

/** Query katalog izin untuk UI role-builder. */
interface PermissionQuery {

    fun catalog(): PermissionCatalogView

    fun listAll(): List<PermissionView>
}
