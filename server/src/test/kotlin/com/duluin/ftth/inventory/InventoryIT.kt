package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
import com.duluin.ftth.inventory.application.port.outbound.InventoryItemRepository
import com.duluin.ftth.inventory.application.service.CycleCountCommand
import com.duluin.ftth.inventory.application.service.CreateInventoryApproval
import com.duluin.ftth.inventory.application.service.InventoryApprovalService
import com.duluin.ftth.inventory.application.service.InventoryMovementLedgerService
import com.duluin.ftth.inventory.application.service.InventoryReconciliationService
import com.duluin.ftth.inventory.application.service.MaterialConsumptionService
import com.duluin.ftth.inventory.domain.model.*
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Uji integrasi PERTAMA modul inventory.
 *
 * Sampai P0, seluruh state gudang hidup di memori proses sehingga tidak satu pun jaminan
 * basis data — Row-Level Security, UNIQUE idempotency, CHECK saldo — pernah benar-benar
 * dieksekusi. Tes ini memastikan modulnya kini berdiri di atas Postgres: data bertahan
 * lintas transaksi DAN tidak bocor ke tenant lain.
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryIT {

    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var items: InventoryItemRepository
    @Autowired private lateinit var ledger: InventoryMovementLedgerService
    @Autowired private lateinit var materials: MaterialConsumptionService
    @Autowired private lateinit var reconciliation: InventoryReconciliationService
    @Autowired private lateinit var approvals: InventoryApprovalService

    // Dependensi opsional modul lain WAJIB benar-benar ter-inject; lihat tes di bawah.
    @Autowired private lateinit var movementApi: InventoryMovementApi
    @Autowired private lateinit var materialApi: MaterialConsumptionApi
    @Autowired private lateinit var approvalApi: InventoryApprovalApi

    @PersistenceContext private lateinit var em: EntityManager

    private fun unique() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T =
        TenantContext.runAs(tenantId) { TransactionTemplate(txManager).execute { block() }!! }

    private fun countVisible(table: String, tenantId: UUID, column: String, value: UUID): Long = asTenant(tenantId) {
        (
            em.createNativeQuery("SELECT count(*) FROM $table WHERE $column = :value")
                .setParameter("value", value)
                .singleResult as Number
            ).toLong()
    }

    @Test
    fun `inventory permissions are registered`() {
        assertThat(PermissionCatalog.codes).contains(
            "inventory.restock.request",
            "inventory.restock.receive",
            "inventory.movement.view",
            "inventory.movement.issue",
            "inventory.movement.return",
            "inventory.movement.adjust",
            "inventory.count.perform",
            "inventory.count.approve",
        )
    }

    /**
     * `Subscriber360Service` menerima [MaterialConsumptionApi] sebagai parameter opsional.
     * Kalau bean-nya tidak terdaftar, ia ter-inject `null` dan panel material pelanggan
     * kosong TANPA error apa pun — kegagalan diam yang hanya ketahuan dari keluhan pengguna.
     */
    @Test
    fun `cross module inventory APIs have runtime implementations`() {
        assertThat(movementApi).isNotNull()
        assertThat(materialApi).isNotNull()
        assertThat(approvalApi).isNotNull()
    }

    @Test
    fun `warehouse writes survive a new transaction and stay invisible to other tenants`() {
        val tenantA = tenantApi.ensureTenant("inventory-a-${unique()}", "Inventory A").id
        val tenantB = tenantApi.ensureTenant("inventory-b-${unique()}", "Inventory B").id

        val actor = UUID.randomUUID()
        val technician = UUID.randomUUID()
        val location = UUID.randomUUID()
        val customer = UUID.randomUUID()
        val workOrder = UUID.randomUUID()
        val sku = UUID.randomUUID()

        // --- Transaksi 1: seluruh tulisan gudang ---------------------------------------
        val written = asTenant(tenantA) {
            val item = items.save(
                InventoryItem.create(tenantA, "ONT-${unique()}", "ONT Dual Band", InventoryItemCategory.ONT, InventoryUnit.PCS, serialized = false),
            )

            ledger.apply(
                MovementCommand(
                    tenantA, actor, "it.receive", "receive-${unique()}", "receive-hash", "restock awal",
                    MovementKind.RECEIVE,
                    listOf(MovementLeg(LegDirection.IN, item.id, sku, location, 5, false, actor, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE)),
                ),
            )
            ledger.apply(
                MovementCommand(
                    tenantA, actor, "it.issue", "issue-${unique()}", "issue-hash", "keluar ke teknisi",
                    MovementKind.ISSUE,
                    listOf(
                        MovementLeg(LegDirection.OUT, item.id, sku, location, 2, false, actor, OwnerKind.WAREHOUSE, InventoryStatus.AVAILABLE),
                        MovementLeg(LegDirection.IN, item.id, sku, location, 2, false, technician, OwnerKind.TECHNICIAN, InventoryStatus.ISSUED),
                    ),
                ),
            )

            val consumeKey = "consume-${unique()}"
            materials.consume(
                MaterialConsumptionCommand(
                    tenantA, workOrder, customer, technician, consumeKey, "consume-hash", sku, item.id,
                    location, 1, false, "pasang di rumah pelanggan", "ONT",
                ),
            )

            val count = reconciliation.createCount(
                CycleCountCommand(
                    tenantA, tenantA, location, item.id, sku, 4, actor, "opname rutin",
                    "evidence://opname-${unique()}", "count-${unique()}", "count-hash",
                ),
            )

            val approval = approvals.request(
                CreateInventoryApproval(
                    tenantA, InventoryApprovalType.RESTOCK, 500, actor, technician,
                    InventoryApprovalPolicy(1, listOf(ApprovalTier(1, 0, setOf(UUID.randomUUID())))),
                    "policy-hash", "approval-${unique()}", "approval-hash",
                ),
            )

            Written(item.id, count.countId, approval.approvalId)
        }

        // --- Transaksi 2: dibaca ULANG di transaksi baru --------------------------------
        // Kalau service-nya masih in-memory, semua assertion di bawah ini lolos juga di
        // transaksi yang sama; yang membuktikan persistensi justru pembacaan ulang ini.
        val balances = asTenant(tenantA) { ledger.balances(tenantA) }
        assertThat(balances.filter { it.itemId == written.itemId })
            .extracting<Pair<InventoryStatus, Int>> { it.status to it.quantity }
            .containsExactlyInAnyOrder(
                InventoryStatus.AVAILABLE to 3,
                InventoryStatus.ISSUED to 1,
            )
        assertThat(asTenant(tenantA) { ledger.movements(tenantA) }).hasSize(3)
        assertThat(asTenant(tenantA) { items.findById(written.itemId) }).isNotNull()
        assertThat(asTenant(tenantA) { reconciliation.get(written.countId) }).isNotNull()
        // Kebijakan approval disimpan sebagai jsonb; pembacaan ulang membuktikan snapshot-nya
        // memang bulat-balik, bukan sekadar tersimpan sebagai teks yang tak bisa diurai lagi.
        assertThat(asTenant(tenantA) { approvals.get(written.approvalId) }?.policy?.tiers).hasSize(1)

        // --- RLS: tenant B tidak boleh melihat sebaris pun ------------------------------
        val movementIds = asTenant(tenantA) { ledger.movements(tenantA).map { it.movementId } }
        val isolatedTables = listOf(
            "inventory_item" to written.itemId,
            "inventory_movement" to movementIds.first(),
            "inventory_cycle_count" to written.countId,
            "inventory_approval" to written.approvalId,
        )
        isolatedTables.forEach { (table, id) ->
            assertThat(countVisible(table, tenantA, "id", id)).describedAs("$table untuk pemiliknya").isEqualTo(1)
            assertThat(countVisible(table, tenantB, "id", id)).describedAs("$table untuk tenant lain").isZero()
        }

        val byMovement = listOf("inventory_movement_leg")
        byMovement.forEach { table ->
            assertThat(countVisible(table, tenantA, "movement_id", movementIds.first())).isGreaterThan(0)
            assertThat(countVisible(table, tenantB, "movement_id", movementIds.first())).isZero()
        }

        assertThat(countVisible("inventory_customer_material_fact", tenantA, "customer_id", customer)).isEqualTo(1)
        assertThat(countVisible("inventory_customer_material_fact", tenantB, "customer_id", customer)).isZero()
        assertThat(countVisible("inventory_balance_projection", tenantA, "item_id", written.itemId)).isGreaterThan(0)
        assertThat(countVisible("inventory_balance_projection", tenantB, "item_id", written.itemId)).isZero()

        // Lapisan aplikasi harus sepakat dengan RLS, bukan hanya SQL mentahnya.
        assertThat(asTenant(tenantB) { ledger.movements(tenantB) }).isEmpty()
        assertThat(asTenant(tenantB) { materials.forCustomer(tenantB, customer) }).isEmpty()
        assertThat(asTenant(tenantB) { items.findAll(tenantB) }).isEmpty()
        assertThat(asTenant(tenantB) { reconciliation.open(tenantB) }).isEmpty()
    }

    private data class Written(val itemId: UUID, val countId: UUID, val approvalId: UUID)
}
