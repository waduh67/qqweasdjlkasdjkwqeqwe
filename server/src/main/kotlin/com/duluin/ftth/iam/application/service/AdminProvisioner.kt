package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.application.port.outbound.PasswordHasher
import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.domain.model.Role
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Menyediakan role admin bawaan + user admin awal. Berjalan DI DALAM tenant
 * context yang dipasang pemanggil (batas `@Transactional` di sini agar session
 * Hibernate terbuka dengan tenant yang benar). Semua operasi idempotent.
 */
@Service
@Transactional
class AdminProvisioner(
    private val roleRepository: RoleRepository,
    private val userRepository: UserRepository,
    private val permissionRepository: PermissionRepository,
    private val passwordHasher: PasswordHasher,
) {
    /** Role "Tenant Admin" (semua izin non-platform) + admin tenant. */
    fun provisionTenantAdmin(tenantId: UUID, email: String, name: String, password: String): Boolean {
        val roleId = ensureTenantAdminRole(tenantId)
        return ensureAdminUser(tenantId, email, name, password, platformAdmin = false, roleIds = setOf(roleId))
    }

    /**
     * Role bawaan "Tenant Admin": semua izin non-platform yang berlaku SAAT INI.
     * Dipisah dari [provisionTenantAdmin] agar bisa dipanggil sebagai backfill untuk
     * tenant lama — saat izin baru ditambahkan ke katalog, [ensureRole] menyetel ulang
     * set izin role ini (replacePermissions) sehingga menu baru ikut terbuka. Idempotent.
     */
    fun ensureTenantAdminRole(tenantId: UUID): UUID =
        ensureRole(tenantId, TENANT_ADMIN_ROLE_NAME, "Akses penuh dalam tenant", tenantPermissionIds())

    /** Role "Super Admin" (semua izin termasuk platform) + platform admin. */
    fun provisionPlatformAdmin(tenantId: UUID, email: String, name: String, password: String): Boolean {
        val roleId = ensureRole(tenantId, "Super Admin", "Akses penuh platform", allPermissionIds())
        return ensureAdminUser(tenantId, email, name, password, platformAdmin = true, roleIds = setOf(roleId))
    }

    /**
     * Role sistem "Teknisi": izin minimal untuk pengerjaan lapangan (papan tugas +
     * bukti WO yang ditugaskan ke diri sendiri, plus konteks pelanggan/langganan).
     * Dipakai aplikasi teknisi mobile. Idempotent; tenant admin bebas menyesuaikan
     * izinnya belakangan lewat role-builder.
     */
    fun ensureTechnicianRole(tenantId: UUID): UUID =
        ensureRole(
            tenantId,
            TECHNICIAN_ROLE_NAME,
            "Teknisi lapangan: kerjakan work order yang ditugaskan",
            permissionIdsForCodes(TECHNICIAN_PERMISSION_CODES),
        )

    private fun ensureRole(tenantId: UUID, name: String, description: String, permissionIds: Set<UUID>): UUID {
        val existing = roleRepository.findByName(name)
        return when {
            existing == null ->
                roleRepository.save(Role.create(tenantId, name, description, systemRole = true, permissionIds)).id

            existing.permissionIds != permissionIds -> {
                existing.replacePermissions(permissionIds)
                roleRepository.save(existing).id
            }

            else -> existing.id
        }
    }

    private fun ensureAdminUser(
        tenantId: UUID,
        email: String,
        name: String,
        password: String,
        platformAdmin: Boolean,
        roleIds: Set<UUID>,
    ): Boolean {
        val parsed = Email.of(email)
        if (userRepository.existsByEmail(parsed)) return false
        userRepository.save(
            User.create(
                tenantId = tenantId,
                email = parsed,
                name = name,
                passwordHash = passwordHasher.hash(password),
                platformAdmin = platformAdmin,
                roleIds = roleIds,
            ),
        )
        return true
    }

    private fun tenantPermissionIds(): Set<UUID> =
        permissionRepository.findAll().filterNot { it.platformOnly }.mapTo(HashSet()) { it.id }

    private fun allPermissionIds(): Set<UUID> =
        permissionRepository.findAll().mapTo(HashSet()) { it.id }

    private fun permissionIdsForCodes(codes: Set<String>): Set<UUID> =
        permissionRepository.findAll().filter { it.code.value in codes }.mapTo(HashSet()) { it.id }

    companion object {
        const val TENANT_ADMIN_ROLE_NAME = "Tenant Admin"
        const val TECHNICIAN_ROLE_NAME = "Teknisi"

        /** Izin minimal role Teknisi; kepemilikan WO ditegakkan terpisah di modul workorder. */
        val TECHNICIAN_PERMISSION_CODES = setOf(
            "workorder.order.view",
            "workorder.order.field",
            "workorder.evidence.view",
            "customer.customer.view",
            "customer.subscription.view",
            // Teknisi mengetik setelan TR-069 ke ONT di rumah pelanggan; tanpa ini ia
            // harus menanyakan URL CWMP & interval inform lewat chat tiap pemasangan.
            // Hanya info server (nilai env global), bukan daftar perangkat tenant.
            "cpe.acs.view",
        )
    }
}
