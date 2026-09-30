package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehousePostingITAdversarial {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }

    @ParameterizedTest @ValueSource(strings=["UNBALANCED","MIXED_UNIT","OVERFLOW","ZERO","SERIAL_TWO","STALE","FAKE_SINK","PARTIAL_PIECE","BULK_MM_PARTIAL"])
    fun `malformed quantities and references leave no writes`(mode: String) {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(if(mode=="SERIAL_TWO") StockQuantity.each("1") else StockQuantity.metres("100"),bulk=mode=="BULK_MM_PARTIAL") }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction {
            val quantity=if(mode=="SERIAL_TWO") StockQuantity.each("1") else StockQuantity.metres("100")
            val command=move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),quantity)
            val invalid=when(mode) {
                "UNBALANCED" -> command.copy(legs=command.legs.map { if(it.direction==LegDirection.IN) it.copy(quantity=StockQuantity.metres("99")) else it })
                "MIXED_UNIT" -> command.copy(legs=command.legs.map { if(it.direction==LegDirection.IN) it.copy(quantity=StockQuantity.each("100000")) else it })
                "OVERFLOW" -> command.copy(legs=command.legs.flatMap { listOf(it.copy(quantity=StockQuantity.of(Long.MAX_VALUE,StockUnit.MM)),it) })
                "ZERO" -> command.copy(legs=command.legs.map { it.copy(quantity=StockQuantity.metres("0")) })
                "SERIAL_TWO" -> command.copy(legs=command.legs.map { it.copy(quantity=StockQuantity.each("2")) })
                "STALE" -> command.copy(expectedRevision=9)
                "FAKE_SINK" -> command.copy(legs=command.legs.map { if(it.direction==LegDirection.IN) it.copy(dimension=it.dimension.copy(locationId=consumed)) else it })
                else -> command.copy(legs=command.legs.map { it.copy(quantity=StockQuantity.metres("50")) })
            }
            post(invalid)
        } }.isInstanceOf(Exception::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `duplicate posting and changed key never apply business action twice`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        val command=fixture.transaction { move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")) }
        fixture.transaction { post(command) }
        val before=fixture.transaction { counts() }
        listOf(command,command.copy(operation=command.operation.copy(id=UUID.randomUUID(),key="another"))).forEach { duplicate ->
            assertThatThrownBy { fixture.transaction { post(duplicate) } }.isInstanceOf(WarehouseContractException::class.java)
        }
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(total(technician,StockUnit.EA)).isEqualTo(1) }
    }

    @Test fun `outside transaction expired fence and changed tenant are rejected`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        val command=fixture.transaction { move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")) }
        val fence=fixture.transaction { context.getBean(InventoryTenantPolicyService::class.java).lockForCommand(0,WarehouseOperationClass.ORDINARY_STOCK) }
        assertThatThrownBy { context.getBean(WarehousePosting::class.java).post(command,fence) }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { fixture.transaction { context.getBean(WarehousePosting::class.java).post(command,fence) } }.hasMessageContaining("fence")
        assertThatThrownBy { fixture.transaction {
            val current=context.getBean(InventoryTenantPolicyService::class.java).lockForCommand(0,WarehouseOperationClass.ORDINARY_STOCK)
            TenantContext.runAs(UUID.randomUUID()) { context.getBean(WarehousePosting::class.java).post(command,current) }
        } }.isInstanceOf(Exception::class.java)
        fixture.transaction { assertThat(total(warehouse,StockUnit.EA)).isEqualTo(1) }
    }

    @Test fun `second physical debit fails and history is immutable`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        fixture.transaction { post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1"))) }
        assertThatThrownBy { fixture.transaction { post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1"))) } }
            .isInstanceOf(WarehouseContractException::class.java)
        listOf("inventory_movement","inventory_movement_leg","inventory_outbox","inventory_operation").forEach { table ->
            assertThatThrownBy { fixture.transaction { sql("DELETE FROM $table") } }.isInstanceOf(Exception::class.java)
        }
        fixture.transaction { assertThat(total(technician,StockUnit.EA)).isEqualTo(1) }
    }

    @Test fun `terminal and legacy staged identities cannot be posted`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.metres("100")) }
        val staged=UUID.randomUUID()
        database.ownerFixture { connection -> connection.createStatement().use { statement ->
            statement.execute("SET app.tenant_id='${fixture.tenant}'")
            statement.execute("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base,warehouse_admission) VALUES ('$staged','${fixture.tenant}','${piece.skuId}','${piece.lotId}','REEL','MM',100000,'LEGACY_UNRESOLVED')")
        } }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction {
            val from=piece.copy(stockIdentityId=staged)
            post(move(from,from.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.metres("100")))
        } }.isInstanceOf(WarehouseContractException::class.java)
        fixture.transaction {
            assertThat(counts()).isEqualTo(before)
            sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE stock_identity_id='${piece.stockIdentityId}'")
            sql("UPDATE inventory_segment SET state='RETIRED',revision=revision+1 WHERE id='${piece.stockIdentityId}'")
        }
        assertThatThrownBy { fixture.transaction { post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.metres("100"))) } }
            .isInstanceOf(WarehouseContractException::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `tenant GUC mismatch cannot reuse a valid context fence`() {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { receipt(StockQuantity.each("1")) }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction {
            val command=move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1"))
            val fence=context.getBean(InventoryTenantPolicyService::class.java).lockForCommand(0,WarehouseOperationClass.ORDINARY_STOCK)
            sql("SET LOCAL app.tenant_id=''")
            context.getBean(WarehousePosting::class.java).post(command,fence)
        } }.isInstanceOf(Exception::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }
}
