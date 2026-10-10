package com.duluin.ftth

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.security.SessionIdentity
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.DeliveryAuthorityApi
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.iam.application.port.outbound.RoleRepository
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.iam.application.port.outbound.UserRepository
import com.duluin.ftth.iam.application.service.AdminProvisioner
import com.duluin.ftth.iam.application.service.AuthViewAssembler
import com.duluin.ftth.iam.application.bootstrap.TenantDefaultRoleBackfillRunner
import com.duluin.ftth.iam.application.bootstrap.TenantOwnerBackfillRunner
import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import com.duluin.ftth.iam.domain.model.Role
import com.duluin.ftth.iam.domain.model.User
import com.duluin.ftth.iam.domain.model.vo.Email
import com.duluin.ftth.inventory.InventoryWarehouseScopeApi
import com.duluin.ftth.inventory.application.service.WarehouseTenantDefaults
import com.duluin.ftth.inventory.application.service.WarehouseTenantDefaultsRunner
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantStatus
import org.springframework.boot.DefaultApplicationArguments
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class TenantDefaultsIT {
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var provisioner: AdminProvisioner
    @Autowired private lateinit var defaults: WarehouseTenantDefaults
    @Autowired private lateinit var roles: RoleRepository
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var owners: TenantOwnerStore
    @Autowired private lateinit var assembler: AuthViewAssembler
    @Autowired private lateinit var authority: DeliveryAuthorityApi
    @Autowired private lateinit var scopes: InventoryWarehouseScopeApi
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var tenants: TenantApi
    @Autowired private lateinit var roleBackfill: TenantDefaultRoleBackfillRunner
    @Autowired private lateinit var ownerBackfill: TenantOwnerBackfillRunner
    @Autowired private lateinit var warehouseBackfill: WarehouseTenantDefaultsRunner
    @Autowired private lateinit var entityManager: jakarta.persistence.EntityManager

    private fun tenant(): Pair<UUID, String> {
        val slug = "defaults-${UUID.randomUUID().toString().take(8)}"
        val email = "owner@$slug.test"
        return onboarding.onboard(OnboardTenantCommand(slug, "Defaults", email, "Owner", "secret12345")).tenant.id to email
    }

    private fun <T : Any> within(tenant: UUID, action: () -> T): T = TenantContext.runAs(tenant) {
        requireNotNull(TransactionTemplate(transactions).execute { action() })
    }

    @Test
    fun `tenant baru memiliki empat role owner eksplisit dan gudang tanpa stok`() {
        val (tenant, email) = tenant()
        within(tenant) {
            assertThat(roles.findAll().map { it.defaultKey }).containsExactlyInAnyOrder("ADMIN", "MANAGER", "TECHNICIAN_NE", "TECHNICIAN_FO")
            val owner = requireNotNull(users.findByEmail(Email.of(email)))
            assertThat(owners.findUserId()).isEqualTo(owner.id)
            assertThat(owner.platformAdmin).isFalse()
            assertThat(owner.roleIds).containsExactly(requireNotNull(roles.findByDefaultKey("ADMIN")).id)
            assertThat(jdbc.queryForList("SELECT name FROM inventory_location WHERE tenant_id=? AND kind='WAREHOUSE'", String::class.java, tenant))
                .containsExactly("Gudang Utama")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_serialized_asset WHERE tenant_id=?", Long::class.java, tenant)).isZero()
            assertThat(users.search(null, com.duluin.ftth.common.domain.PageRequest(0, 100)).content).hasSize(1)
            assertThat(jdbc.queryForObject("SELECT workflow_mode FROM inventory_tenant_cutover WHERE tenant_id=?", String::class.java, tenant)).isEqualTo("REFERENCE")
            assertThat(jdbc.queryForObject("SELECT epoch FROM inventory_tenant_cutover WHERE tenant_id=?", Long::class.java, tenant)).isEqualTo(1L)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reference_bootstrap WHERE tenant_id=?", Long::class.java, tenant)).isEqualTo(1L)
        }
    }

    @Test
    fun `owner tanpa role dan dengan area terbatas tetap akses tenant penuh tanpa izin platform`() {
        val (tenant, email) = tenant()
        within(tenant) {
            val owner = requireNotNull(users.findByEmail(Email.of(email)))
            owner.assignRoles(emptySet())
            val area = UUID.randomUUID()
            jdbc.update("INSERT INTO area(id,tenant_id,code,name) VALUES (?,?,?,?)", area, tenant, "OWNER-AREA", "Area Owner")
            owner.assignAreas(setOf(area))
            users.save(owner)
            val expected = PermissionCatalog.tenantAssignable().map { it.code.value }
            assertThat(assembler.permissionCodesFor(owner)).containsExactlyInAnyOrderElementsOf(expected)
            assertThat(assembler.toAuthUserView(owner, expected.toSet()).areaIds).isEmpty()
            val current = authority.lockActor(SessionIdentity(tenant, owner.id, null))
            assertThat(current.permissions).containsExactlyInAnyOrderElementsOf(expected)
            assertThat(current.platformAdmin).isFalse()
            assertThat(current.areaScope).isEqualTo(AuthorityScope.Unrestricted)
            assertThat(scopes.currentUnderFence(current.fence)).isEqualTo(AuthorityScope.Unrestricted)
        }
    }

    @Test
    fun `provisioning berulang mempertahankan nama izin role akun owner dan gudang`() {
        val (tenant, email) = tenant()
        val before = within(tenant) {
            val admin = requireNotNull(roles.findByDefaultKey("ADMIN"))
            admin.rename("Operasional Custom")
            admin.replacePermissions(emptySet())
            roles.save(admin)
            jdbc.update("UPDATE inventory_location SET name='Gudang Jakarta',revision=revision+1 WHERE tenant_id=? AND kind='WAREHOUSE'", tenant)
            requireNotNull(owners.findUserId())
        }
        within(tenant) {
            assertThat(provisioner.provisionTenantAdmin(tenant, email, "Changed", "different123")).isFalse()
            defaults.ensureWarehouse()
            val admin = requireNotNull(roles.findByDefaultKey("ADMIN"))
            assertThat(admin.name).isEqualTo("Operasional Custom")
            assertThat(admin.permissionIds).isEmpty()
            assertThat(roles.findAll()).hasSize(4)
            assertThat(owners.findUserId()).isEqualTo(before)
            assertThat(users.findById(before)?.name).isEqualTo("Owner")
            assertThat(jdbc.queryForList("SELECT name FROM inventory_location WHERE tenant_id=? AND kind='WAREHOUSE'", String::class.java, tenant))
                .containsExactly("Gudang Jakarta")
        }
    }

    @Test
    fun `role custom bernama sama tidak diambil alih oleh backfill`() {
        val (tenant, _) = tenant()
        within(tenant) {
            val old = requireNotNull(roles.findByDefaultKey("MANAGER"))
            roles.deleteById(old.id)
            entityManager.flush()
            val custom = roles.save(Role.create(tenant, "Manager", permissionIds = emptySet()))
            provisioner.ensureOperationalRoles(tenant)
            assertThat(roles.findById(custom.id)?.defaultKey).isNull()
            assertThat(roles.findById(custom.id)?.permissionIds).isEmpty()
            assertThat(roles.findByDefaultKey("MANAGER")?.name).isEqualTo("Manager (Bawaan 1)")
        }
    }

    @Test
    fun `backfill B2B menambah izin sekali dan mempertahankan kustomisasi setelah upgrade`() {
        val (tenant, _) = tenant()
        within(tenant) {
            jdbc.update("DELETE FROM iam_default_role_feature WHERE tenant_id=?", tenant)
            listOf("ADMIN", "TECHNICIAN_NE").forEach { key ->
                val role = requireNotNull(roles.findByDefaultKey(key))
                role.replacePermissions(emptySet())
                roles.save(role)
            }
            provisioner.ensureOperationalRoles(tenant)
            assertThat(roles.findByDefaultKey("ADMIN")?.permissionIds).hasSize(2)
            assertThat(roles.findByDefaultKey("TECHNICIAN_NE")?.permissionIds).hasSize(2)
            listOf("ADMIN", "TECHNICIAN_NE").forEach { key ->
                val role = requireNotNull(roles.findByDefaultKey(key))
                role.replacePermissions(emptySet())
                roles.save(role)
            }
            provisioner.ensureOperationalRoles(tenant)
            assertThat(roles.findByDefaultKey("ADMIN")?.permissionIds).isEmpty()
            assertThat(roles.findByDefaultKey("TECHNICIAN_NE")?.permissionIds).isEmpty()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_default_role_feature WHERE tenant_id=?", Long::class.java,tenant)).isEqualTo(2)
        }
    }

    @Test
    fun `identitas owner tenant lain tidak terbaca`() {
        val (first, _) = tenant()
        val (second, _) = tenant()
        val firstOwner = within(first) { requireNotNull(owners.findUserId()) }
        within(second) {
            assertThat(owners.findUserId()).isNotEqualTo(firstOwner)
            assertThat(jdbc.queryForList("SELECT user_id FROM iam_tenant_owner WHERE tenant_id=?", UUID::class.java, first)).isEmpty()
            assertThat(users.findById(firstOwner)).isNull()
        }
    }

    @Test
    fun `owner historis hanya diikat dengan admin paling awal yang tunggal`() {
        val (tenant, email) = tenant()
        within(tenant) {
            val owner = requireNotNull(users.findByEmail(Email.of(email)))
            val legacy = provisioner.ensureTenantAdminRole(tenant)
            owner.assignRoles(setOf(legacy))
            users.save(owner)
            users.save(User.create(tenant, Email.of("later-$email"), "Later Admin", "unused", roleIds = setOf(legacy)))
            jdbc.update("DELETE FROM iam_tenant_owner WHERE tenant_id=?", tenant)
            provisioner.backfillOwner()
            assertThat(owners.findUserId()).isEqualTo(owner.id)
            jdbc.update("DELETE FROM iam_tenant_owner WHERE tenant_id=?", tenant)
            jdbc.update("UPDATE app_user SET created_at=? WHERE tenant_id=?", java.sql.Timestamp.from(owner.createdAt), tenant)
            provisioner.backfillOwner()
            assertThat(owners.findUserId()).isNull()
        }
    }

    @Test
    fun `backfill juga melengkapi tenant suspended tanpa mengaktifkannya`() {
        val tenant = tenants.ensureTenant("suspended-${UUID.randomUUID().toString().take(8)}", "Suspended").id
        tenants.suspend(tenant)
        val args = DefaultApplicationArguments()
        roleBackfill.run(args)
        ownerBackfill.run(args)
        warehouseBackfill.run(args)
        assertThat(tenants.requireById(tenant).status).isEqualTo(TenantStatus.SUSPENDED)
        within(tenant) {
            assertThat(roles.findByDefaultKey("MANAGER")).isNotNull()
            assertThat(jdbc.queryForList("SELECT name FROM inventory_location WHERE tenant_id=? AND kind='WAREHOUSE'", String::class.java, tenant))
                .containsExactly("Gudang Utama")
        }
    }
}
