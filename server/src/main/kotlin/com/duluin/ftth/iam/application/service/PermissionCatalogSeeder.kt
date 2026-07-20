package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import com.duluin.ftth.iam.domain.model.Permission
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Menyinkronkan tabel `permission` dengan [PermissionCatalog] di kode: tambah izin
 * baru, perbarui deskripsi, dan nonaktifkan izin yang sudah dihapus dari katalog.
 * Idempotent — aman dijalankan tiap startup.
 */
@Service
class PermissionCatalogSeeder(
    private val permissionRepository: PermissionRepository,
) {
    @Transactional
    fun sync() {
        val existing = permissionRepository.findAll().associateBy { it.code.value }

        PermissionCatalog.ALL.forEach { def ->
            val current = existing[def.code.value]
            when {
                current == null ->
                    permissionRepository.save(Permission.create(def.code, def.description, def.platformOnly))

                current.description != def.description || !current.active -> {
                    current.syncWith(def.description)
                    permissionRepository.save(current)
                }
            }
        }

        existing.values
            .filter { it.active && it.code.value !in PermissionCatalog.codes }
            .forEach {
                it.deactivate()
                permissionRepository.save(it)
            }
    }
}
