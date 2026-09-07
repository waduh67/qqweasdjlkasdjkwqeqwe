package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseDocumentJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseSkuJpaEntity
import com.duluin.ftth.inventory.application.service.CutoverTransitionUnavailable
import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource
import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.tenancy.TenantCreatedEvent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(WarehouseSchemaITPolicy.ProbeConfiguration::class)
class WarehouseSchemaITPolicy {
    @TestConfiguration
    class ProbeConfiguration {
        @Bean fun creationProbe(jdbc: TenantTransactionJdbc) = WarehouseTenantCreationProbe(jdbc)
    }
    companion object {
        private val database by lazy { WarehouseSchemaDatabase() }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }

    @Autowired private lateinit var policy: InventoryTenantPolicyService
    @Autowired private lateinit var manager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tenants: com.duluin.ftth.tenancy.TenantApi
    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var probe: WarehouseTenantCreationProbe

    @Test
    fun `real tenant creation commits warehouse policy and authorization epoch`() {
        val created = tenants.ensureTenant("creation-${UUID.randomUUID()}", "Created tenant")
        transaction(created.id) {
            assertThat(policy.read().state).isEqualTo(WarehouseCutoverState.ENFORCED)
            assertThat(entityManager.createNativeQuery("SELECT count(*) FROM iam_authorization_epoch WHERE tenant_id=:tenant", Long::class.java)
                .setParameter("tenant",created.id).singleResult).isEqualTo(1L)
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["NONEMPTY","AFTER_INIT"])
    fun `listener failure rolls back tenant policy and epoch together`(mode: String) {
        val slug="rollback-${UUID.randomUUID()}"
        probe.mode=mode
        assertThatThrownBy { tenants.ensureTenant(slug,"Rollback") }.isInstanceOf(Exception::class.java)
        assertThat(tenants.findBySlug(slug)).isNull()
        val failedTenant=requireNotNull(probe.tenantId)
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute("SET app.tenant_id='$failedTenant'")
            for (table in listOf("inventory_tenant_cutover","iam_authorization_epoch","inventory_document")) {
                statement.executeQuery("SELECT count(*) FROM $table WHERE tenant_id='$failedTenant'").use {
                    it.next(); assertThat(it.getInt(1)).describedAs(table).isZero()
                }
            }
            statement.execute("RESET app.tenant_id")
        } }
    }

    @Test
    fun `duplicate creation delivery preserves one policy and existing authorization epoch`() {
        val created=tenants.ensureTenant("duplicate-${UUID.randomUUID()}","Duplicate")
        assertThat(tenants.ensureTenant(created.slug,"Ignored").id).isEqualTo(created.id)
        transaction(created.id) {
            entityManager.createNativeQuery("UPDATE iam_authorization_epoch SET epoch=1,revision=1 WHERE tenant_id=:tenant").setParameter("tenant",created.id).executeUpdate()
            events.publishEvent(TenantCreatedEvent(created.id))
            events.publishEvent(TenantCreatedEvent(created.id))
            assertThat(policy.read().epoch).isZero()
            assertThat(entityManager.createNativeQuery("SELECT epoch FROM iam_authorization_epoch WHERE tenant_id=:tenant",Long::class.java)
                .setParameter("tenant",created.id).singleResult).isEqualTo(1L)
        }
    }

    @Test
    fun `existing tenant creation lookup cannot silently enforce missing or legacy policy`() {
        val existing=tenant(true)
        assertThat(tenants.ensureTenant("policy-$existing","Existing").id).isEqualTo(existing)
        assertThrows<WarehouseContractException> { transaction(existing) { policy.read() } }
        assertThatThrownBy { transaction(existing) { events.publishEvent(TenantCreatedEvent(existing)) } }.hasStackTraceContaining("validated cutover")
        transaction(existing) {
            policy.initializeExistingTenant()
            events.publishEvent(TenantCreatedEvent(existing))
            assertThat(policy.read().state).isEqualTo(WarehouseCutoverState.LEGACY)
        }
    }

    @Test
    fun `creation restores original Hibernate tenant and requires a transaction`() {
        val original=tenant(true)
        transaction(original) {
            policy.initializeExistingTenant()
            val before=entityManager.createNativeQuery("SELECT current_setting('app.tenant_id')",String::class.java).singleResult
            tenants.ensureTenant("scoped-${UUID.randomUUID()}","Scoped")
            assertThat(entityManager.createNativeQuery("SELECT current_setting('app.tenant_id')",String::class.java).singleResult).isEqualTo(before)
            assertThat(policy.read().state).isEqualTo(WarehouseCutoverState.LEGACY)
        }
        assertThatThrownBy { events.publishEvent(TenantCreatedEvent(original)) }
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException::class.java)
    }

    private fun tenant(old: Boolean = false): UUID {
        val tenant = UUID.randomUUID()
        dataSource.connection.use { connection -> connection.createStatement().use {
            it.execute("INSERT INTO tenant(id,slug,name,created_at) VALUES ('$tenant','policy-$tenant','Policy',${if (old) "'2000-01-01'::timestamptz" else "clock_timestamp()"})")
        } }
        return tenant
    }

    private fun <T> transaction(tenant: UUID, action: () -> T): T? = TenantContext.runAs(tenant) {
        TransactionTemplate(manager).execute { action() }
    }

    @Test
    fun `absence never implies enforcement and owner initialization distinguishes existing tenants`() {
        val existing = tenant(true)
        val missing = assertThrows<WarehouseContractException> { transaction(existing) { policy.read() } }
        assertThat(missing.error.code).isEqualTo(WarehouseErrorCode.CUTOVER_REQUIRED)
        assertThatThrownBy { transaction(existing) { policy.initializeNewEmptyTenant() } }.hasStackTraceContaining("validated cutover")
        assertThat(transaction(existing) { policy.initializeExistingTenant().state }).isEqualTo(WarehouseCutoverState.LEGACY)
        val ordinary = assertThrows<WarehouseContractException> { transaction(existing) { policy.lockForCommand(0, WarehouseOperationClass.ORDINARY_STOCK) } }
        assertThat(ordinary.error.code).isEqualTo(WarehouseErrorCode.CUTOVER_REQUIRED)
    }

    @Test
    fun `new empty tenant has persisted enforcement and expired fence cannot escape transaction`() {
        val newTenant = tenant()
        assertThat(transaction(newTenant) { policy.initializeNewEmptyTenant().state }).isEqualTo(WarehouseCutoverState.ENFORCED)
        val fence = requireNotNull(transaction(newTenant) {
            policy.lockForCommand(0, WarehouseOperationClass.ORDINARY_STOCK).also { it.assertHeld() }
        })
        assertThatThrownBy { fence.assertHeld() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(transaction(newTenant) { policy.read().state }).isEqualTo(WarehouseCutoverState.ENFORCED)
        assertThatThrownBy { transaction(newTenant) { policy.lockForCommand(0, WarehouseOperationClass.LEGACY_EFFECT) } }
            .isInstanceOf(WarehouseContractException::class.java)
    }

    @Test
    fun `validation snapshots persist and finalization stays typed closed`() {
        val existing = tenant(true)
        transaction(existing) { policy.initializeExistingTenant() }
        val validating = requireNotNull(transaction(existing) { policy.beginValidation(0) })
        assertThat(validating.state).isEqualTo(WarehouseCutoverState.VALIDATING)
        assertThat(validating.epoch).isEqualTo(1)
        assertThat(validating.migrationBatchId).isNotNull()
        assertThat(validating.snapshotWatermark).isNotBlank()
        val stale = assertThrows<WarehouseContractException> { transaction(existing) { policy.lockForCommand(0, WarehouseOperationClass.CONTROL_PLANE) } }
        assertThat(stale.error.code).isEqualTo(WarehouseErrorCode.STALE_CUTOVER)
        assertThat(transaction(existing) { policy.finalizeValidation(1) }).isEqualTo(CutoverTransitionUnavailable.INDEPENDENT_APPROVAL_NOT_INSTALLED)
        for (operation in listOf(WarehouseOperationClass.MIGRATION_BASELINE, WarehouseOperationClass.PROVENANCE_RESOLUTION)) {
            val failure = assertThrows<WarehouseContractException> { transaction(existing) { policy.lockForCommand(1, operation) } }
            assertThat(failure.error.code).isEqualTo(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        }
        assertThat(transaction(existing) { policy.read() }).isEqualTo(validating)
    }

    @Test
    fun `C10 allowlist is explicit for every state and operation class`() {
        for (state in WarehouseCutoverState.entries) {
            for (operation in WarehouseOperationClass.entries) {
                val expected = operation in when (state) {
                    WarehouseCutoverState.LEGACY -> setOf(WarehouseOperationClass.CONTROL_PLANE, WarehouseOperationClass.MIGRATION_REPORT)
                    WarehouseCutoverState.VALIDATING -> setOf(WarehouseOperationClass.CONTROL_PLANE, WarehouseOperationClass.MIGRATION_REPORT,
                        WarehouseOperationClass.PROVENANCE_RESOLUTION, WarehouseOperationClass.MIGRATION_APPROVAL,
                        WarehouseOperationClass.MIGRATION_BASELINE, WarehouseOperationClass.CUTOVER_FINALIZATION)
                    WarehouseCutoverState.ENFORCED -> setOf(WarehouseOperationClass.CONTROL_PLANE, WarehouseOperationClass.MIGRATION_REPORT,
                        WarehouseOperationClass.ORDINARY_STOCK, WarehouseOperationClass.ASSET_ASSIGNMENT)
                }
                assertThat(policy.allows(state, operation)).describedAs("%s/%s", state, operation).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `JPA master and draft document round trip preserve revisions and nullable labels`() {
        val tenant = tenant()
        val sku = UUID.randomUUID()
        val document = UUID.randomUUID()
        transaction(tenant) {
            entityManager.persist(WarehouseSkuJpaEntity(sku, "sku", "Cable", WarehouseTracking.LOT, WarehouseBaseUnit.MM))
            entityManager.persist(WarehouseDocumentJpaEntity(document, "draft", WarehouseDocumentKind.DEMAND, UUID.randomUUID(), 0, 0))
        }
        transaction(tenant) {
            val saved = entityManager.find(WarehouseSkuJpaEntity::class.java, sku)
            assertThat(saved.baseUnit).isEqualTo(WarehouseBaseUnit.MM)
            assertThat(saved.allowedOwnershipModes).containsExactly("LOAN", "SALE")
            saved.name = "Updated"
        }
        transaction(tenant) {
            assertThat(entityManager.find(WarehouseSkuJpaEntity::class.java, sku).revision).isEqualTo(1)
            val draft = entityManager.find(WarehouseDocumentJpaEntity::class.java, document)
            assertThat(draft.customerId).isNull()
            assertThat(draft.workOrderCodeSnapshot).isNull()
            assertThat(draft.state).isEqualTo("DRAFT")
        }
    }
}
