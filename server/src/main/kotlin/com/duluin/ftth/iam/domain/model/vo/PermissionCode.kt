package com.duluin.ftth.iam.domain.model.vo

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Value object kode izin dengan format `module.resource.action`
 * (contoh: `iam.role.create`). Format yang terstruktur ini yang membuat RBAC
 * bisa dirender rapi sebagai matriks di UI role-builder.
 */
@JvmInline
value class PermissionCode private constructor(val value: String) {

    val module: String get() = value.split('.')[0]
    val resource: String get() = value.split('.')[1]
    val action: String get() = value.split('.')[2]

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[a-z]+\\.[a-z]+\\.[a-z]+$")

        fun of(raw: String): PermissionCode {
            val normalized = raw.trim().lowercase()
            if (!PATTERN.matches(normalized)) {
                throw ValidationException("Kode izin tidak valid: '$raw' (harus module.resource.action)")
            }
            return PermissionCode(normalized)
        }
    }
}
