package com.karuhun.ftth.iam.domain.catalog

import com.karuhun.ftth.iam.domain.model.vo.PermissionCode

/**
 * Definisi satu izin di katalog kode. Menjadi sumber kebenaran yang di-seed ke
 * tabel `permission`; UI role-builder membacanya untuk menyusun matriks izin.
 */
data class PermissionDefinition(
    val code: PermissionCode,
    val description: String,
    val platformOnly: Boolean = false,
) {
    val module: String get() = code.module
}
