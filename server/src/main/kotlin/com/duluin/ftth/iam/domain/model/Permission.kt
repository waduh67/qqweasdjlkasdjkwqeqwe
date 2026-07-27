package com.duluin.ftth.iam.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.iam.domain.model.vo.PermissionCode
import java.util.UUID

/**
 * Izin atomik yang bisa dirangkai ke dalam role. Data referensi platform-level
 * (tidak per-tenant) yang di-seed dari [com.duluin.ftth.iam.domain.catalog.PermissionCatalog].
 */
class Permission private constructor(
    val id: UUID,
    val code: PermissionCode,
    description: String?,
    platformOnly: Boolean,
    active: Boolean,
) {
    var description: String? = description
        private set

    var platformOnly: Boolean = platformOnly
        private set

    var active: Boolean = active
        private set

    val module: String get() = code.module

    /**
     * Selaraskan metadata dengan definisi katalog terbaru saat seeding — termasuk
     * `platformOnly`, agar kode yang berpindah klasifikasi (mis. tenant → platform)
     * ikut terkoreksi, bukan cuma saat baru dibuat.
     */
    fun syncWith(description: String?, platformOnly: Boolean) {
        this.description = description
        this.platformOnly = platformOnly
        this.active = true
    }

    fun deactivate() {
        active = false
    }

    companion object {
        fun create(code: PermissionCode, description: String?, platformOnly: Boolean): Permission =
            Permission(UuidV7.generate(), code, description, platformOnly, active = true)

        fun rehydrate(
            id: UUID,
            code: PermissionCode,
            description: String?,
            platformOnly: Boolean,
            active: Boolean,
        ): Permission = Permission(id, code, description, platformOnly, active)
    }
}
