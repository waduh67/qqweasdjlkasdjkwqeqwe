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

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseSchemaITPolicy {
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
