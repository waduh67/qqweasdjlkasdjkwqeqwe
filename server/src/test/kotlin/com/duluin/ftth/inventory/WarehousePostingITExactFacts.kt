package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.MaterialConsumptionService
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehousePostingITExactFacts : WarehousePostingRegressionSupport() {
    @ParameterizedTest @ValueSource(strings=["2147483647","3000000000","9223372036854775807"])
    fun `AV5-05 exact EA fact is independent of legacy Int projection`(base: String) {
        val fixture=fixture()
        val quantity=StockQuantity.each(base)
        fixture.transaction {
            val piece=receipt(quantity,true)
            val issued=piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(piece,issued,quantity))
            val target=issued.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER)
            post(move(issued,target,quantity,MovementKind.CONSUME,facts=listOf(fact(issued,quantity).copy(fulfillmentTargetId=UUID.randomUUID()))))
        }
        fixture.transaction {
            val legacy=if(quantity.quantityBase<=Int.MAX_VALUE) base else "NULL"
            assertThat(scalar("SELECT quantity_base||':'||base_unit||':'||coalesce(quantity::text,'NULL') FROM inventory_customer_material_fact")).isEqualTo("$base:EA:$legacy")
            assertThat(scalar("SELECT quantity_base||':'||base_unit||':'||coalesce(quantity::text,'NULL') FROM inventory_fulfillment_effect")).isEqualTo("$base:EA:$legacy")
            assertThat(total(consumed,StockUnit.EA)).isEqualTo(quantity.quantityBase)
            assertThat(total(technician,StockUnit.EA)).isZero()
            assertThat(context.getBean(MaterialConsumptionService::class.java).forCustomer(tenant,customer)).hasSize(if(legacy=="NULL") 0 else 1)
        }
    }
}
