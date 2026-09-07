package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.DurableInventoryFulfillmentService
import com.duluin.ftth.inventory.application.service.MaterialConsumptionService
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehousePostingITLegacy {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }

    @ParameterizedTest @ValueSource(strings=["DURABLE","MATERIAL","RETURN","MOVEMENT"])
    fun `legacy entry points use paired durable posting and facts`(mode: String) {
        val fixture=WarehousePostingFixture(context).also { it.setup() }
        val piece=fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) }
        val key=UUID.randomUUID().toString()
        fixture.transaction {
            val command=InventoryFulfillmentCommand(tenant,piece.stockIdentityId,piece.stockIdentityId,piece.skuId,piece.locationId,customer,workOrder,1,true,mode!="RETURN",actor,
                "legacy.fulfillment",key,"a".repeat(64),"Legacy fulfillment","ONU")
            when(mode) {
                "DURABLE","RETURN" -> context.getBean(DurableInventoryFulfillmentService::class.java).apply(command,mode=="RETURN")
                "MATERIAL" -> context.getBean(MaterialConsumptionService::class.java).consume(MaterialConsumptionCommand(tenant,workOrder,customer,actor,key,"a".repeat(64),piece.skuId,piece.stockIdentityId,piece.locationId,1,true,"Use","ONU"))
                else -> InventoryMovementApiAdapter(context.getBean(DurableInventoryFulfillmentService::class.java)).consume(
                    MovementCommand(tenant,actor,"legacy.fulfillment",key,"a".repeat(64),"Use",MovementKind.CONSUME,listOf(MovementLeg(LegDirection.OUT,piece.stockIdentityId,piece.skuId,piece.locationId,1,true,actor,OwnerKind.TECHNICIAN,InventoryStatus.ISSUED))))
            }
            assertThat(total(technician,StockUnit.EA)).isZero()
            assertThat(total(if(mode=="RETURN") warehouse else consumed,StockUnit.EA)).isEqualTo(1)
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_fulfillment_effect")).isEqualTo("1")
            assertThat(scalar("SELECT sum(CASE direction WHEN 'IN' THEN quantity_base ELSE -quantity_base END) FROM inventory_movement_leg")).isEqualTo("0")
            assertThat(scalar("SELECT authority_epoch FROM inventory_operation WHERE authority_epoch=7")).isEqualTo("7")
            assertThat(context.getBean(MaterialConsumptionService::class.java).forCustomer(tenant,customer)).hasSize(1)
        }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction {
            context.getBean(DurableInventoryFulfillmentService::class.java).apply(InventoryFulfillmentCommand(tenant,piece.stockIdentityId,piece.stockIdentityId,piece.skuId,piece.locationId,customer,workOrder,1,true,true,actor,"legacy.fulfillment",key,"a".repeat(64),"Again","ONU"),false)
        } }.isInstanceOf(WarehouseContractException::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }
}
