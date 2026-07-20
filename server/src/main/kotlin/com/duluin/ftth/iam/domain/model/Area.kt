package com.duluin.ftth.iam.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Area/wilayah operasional (mis. "Bekasi", "Cikarang"). Dipakai sebagai dimensi
 * SCOPE pada RBAC: user yang dibatasi ke area tertentu hanya melihat aset & tiket
 * di area itu. Bisa hierarkis lewat [parentId].
 */
class Area private constructor(
    val id: UUID,
    val tenantId: UUID,
    code: String,
    name: String,
    parentId: UUID?,
) {
    var code: String = code
        private set

    var name: String = name
        private set

    var parentId: UUID? = parentId
        private set

    fun update(name: String, parentId: UUID?) {
        this.name = validateName(name)
        this.parentId = parentId
    }

    companion object {
        fun create(tenantId: UUID, code: String, name: String, parentId: UUID? = null): Area =
            Area(UuidV7.generate(), tenantId, validateCode(code), validateName(name), parentId)

        fun rehydrate(id: UUID, tenantId: UUID, code: String, name: String, parentId: UUID?): Area =
            Area(id, tenantId, code, name, parentId)

        private fun validateCode(code: String): String {
            val normalized = code.trim().uppercase()
            if (normalized.length !in 2..40) throw ValidationException("Kode area harus 2-40 karakter")
            return normalized
        }

        private fun validateName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.length !in 2..120) throw ValidationException("Nama area harus 2-120 karakter")
            return trimmed
        }
    }
}
