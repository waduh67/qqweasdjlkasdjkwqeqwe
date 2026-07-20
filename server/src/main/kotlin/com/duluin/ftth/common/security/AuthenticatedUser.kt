package com.duluin.ftth.common.security

import java.util.UUID

/**
 * Identitas pengguna yang sudah terautentikasi untuk request saat ini —
 * konsep lintas-module (dipakai controller, service, audit), sehingga tinggal
 * di shared kernel `common`.
 *
 * [areaIds] kosong berarti TIDAK dibatasi area (akses seluruh tenant); kalau
 * ada isinya, akses data dibatasi ke area tersebut (di-enforce di query layer
 * masing-masing module bisnis). [platformAdmin] melewati semua pengecekan izin.
 */
data class AuthenticatedUser(
    val userId: UUID,
    val tenantId: UUID,
    val email: String,
    val name: String,
    val platformAdmin: Boolean,
    val permissions: Set<String>,
    val areaIds: Set<UUID>,
) {
    fun hasPermission(code: String): Boolean = platformAdmin || code in permissions

    val areaRestricted: Boolean get() = !platformAdmin && areaIds.isNotEmpty()
}
