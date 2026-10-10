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
    private val authority: com.duluin.ftth.iam.CurrentAuthorityApi,
    private val owners: com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore,
    private val features: com.duluin.ftth.iam.adapter.outbound.persistence.DefaultRoleFeatureStore,
) {
    fun provisionTenantAdmin(tenantId: UUID, email: String, name: String, password: String): Boolean {
        val roleId = ensureOperationalRoles(tenantId)
        val created = ensureAdminUser(tenantId, email, name, password, platformAdmin = false, roleIds = setOf(roleId))
        if (created && owners.bindIfMissing(requireNotNull(userRepository.findByEmail(Email.of(email))).id)) {
            authority.lockForChange().incrementEpoch()
        }
        return created
    }

    /**
     * Role historis tetap dapat ditemukan tanpa menulis ulang izin tenant.
     */
    fun ensureTenantAdminRole(tenantId: UUID): UUID =
        ensureRole(tenantId, "TENANT_OWNER_LEGACY", TENANT_ADMIN_ROLE_NAME, "Akses penuh dalam tenant", tenantPermissionIds())

    fun ensureOperationalRoles(tenantId: UUID): UUID {
        val admin = ensureRole(tenantId, "ADMIN", "Admin", "Kelola gudang, work order dan akun teknisi", permissionIdsForCodes(ADMIN_PERMISSION_CODES))
        ensureRole(tenantId, "MANAGER", "Manager", "Persetujuan pengajuan material", permissionIdsForCodes(MANAGER_PERMISSION_CODES))
        val ne = ensureRole(tenantId, "TECHNICIAN_NE", "Teknisi NE", "Teknisi Network Equipment", permissionIdsForCodes(TECHNICIAN_PERMISSION_CODES + B2B_NE_PERMISSION_CODES))
        ensureRole(tenantId, "TECHNICIAN_FO", "Teknisi FO", "Teknisi Fiber Optic", permissionIdsForCodes(TECHNICIAN_PERMISSION_CODES))
        ensureFeaturePermissions(admin, B2B_ADMIN_PERMISSION_CODES)
        ensureFeaturePermissions(ne, B2B_NE_PERMISSION_CODES)
        return admin
    }

    private fun ensureFeaturePermissions(roleId: UUID, codes: Set<String>) {
        val fence = authority.lockForChange()
        if (!features.claim(roleId, "B2B_V1")) return
        val role = requireNotNull(roleRepository.findById(roleId))
        val updated = role.permissionIds + permissionIdsForCodes(codes)
        if (updated != role.permissionIds) {
            role.replacePermissions(updated)
            roleRepository.save(role)
            fence.incrementEpoch()
        }
    }

    fun backfillOwner() {
        val fence = authority.lockForChange()
        if (owners.backfillInitialAdmin()) fence.incrementEpoch()
    }

    /** Role "Super Admin" (semua izin termasuk platform) + platform admin. */
    fun provisionPlatformAdmin(tenantId: UUID, email: String, name: String, password: String): Boolean {
        val roleId = ensureRole(tenantId, "PLATFORM_ADMIN", "Super Admin", "Akses penuh platform", allPermissionIds())
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
            "TECHNICIAN_LEGACY",
            TECHNICIAN_ROLE_NAME,
            "Teknisi lapangan: kerjakan work order yang ditugaskan",
            permissionIdsForCodes(TECHNICIAN_PERMISSION_CODES),
        )

    private fun ensureRole(tenantId: UUID, key: String, name: String, description: String, permissionIds: Set<UUID>): UUID {
        val fence = authority.lockForChange()
        roleRepository.findByDefaultKey(key)?.let { return it.id }
        var availableName = name
        var suffix = 1
        while (roleRepository.existsByName(availableName)) availableName = "$name (Bawaan ${suffix++})"
        fence.incrementEpoch()
        return roleRepository.save(Role.create(tenantId, availableName, description, systemRole = true,
            permissionIds = permissionIds, defaultKey = key)).id
    }

    private fun ensureAdminUser(
        tenantId: UUID,
        email: String,
        name: String,
        password: String,
        platformAdmin: Boolean,
        roleIds: Set<UUID>,
    ): Boolean {
        val fence = authority.lockForChange()
        val parsed = Email.of(email)
        if (userRepository.existsByEmail(parsed)) return false
        fence.incrementEpoch()
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
            "workorder.order.field",
            "customer.customer.view",
            "customer.subscription.view",
            // Teknisi mengetik setelan TR-069 ke ONT di rumah pelanggan; tanpa ini ia
            // harus menanyakan URL CWMP & interval inform lewat chat tiap pemasangan.
            // Hanya info server (nilai env global), bukan daftar perangkat tenant.
            "cpe.acs.view",
            "warehouse.material.own",
            "warehouse.request.own",
            "warehouse.return.own",
        )

        val B2B_ADMIN_PERMISSION_CODES = setOf("b2b.client.view", "b2b.client.manage")
        val B2B_NE_PERMISSION_CODES = setOf("b2b.visit.view", "b2b.visit.report")

        val ADMIN_PERMISSION_CODES = B2B_ADMIN_PERMISSION_CODES + setOf(
            "iam.user.view", "iam.user.create", "iam.user.update", "iam.user.assign",
            "customer.customer.view", "customer.subscription.view",
            "workorder.order.view", "workorder.dashboard.view", "workorder.order.create",
            "workorder.order.update", "workorder.order.assign", "workorder.evidence.view",
            "warehouse.catalog.view", "warehouse.catalog.manage", "warehouse.stock.view",
            "warehouse.stock.manage", "warehouse.request.view", "warehouse.request.review",
            "warehouse.request.receive", "warehouse.request.handover", "warehouse.return.manage",
            "warehouse.count.manage", "warehouse.technician.manage",
        )

        val MANAGER_PERMISSION_CODES = setOf(
            "warehouse.catalog.view", "warehouse.stock.view", "warehouse.request.view",
            "warehouse.request.approve", "workorder.order.view", "workorder.dashboard.view",
        )
    }
}
