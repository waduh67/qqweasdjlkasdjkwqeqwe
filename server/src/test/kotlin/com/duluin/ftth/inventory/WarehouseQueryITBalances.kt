package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseQueryITBalances : WarehouseReceiptHttpFixture() {
    @Test fun `durable cuts consumption return reservation pick and title buckets conserve quantities`() {
        val token = tenant()
        val fixture = fixture(token).also { it.setup() }
        val userId = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        fixture.transaction {
            val transitId = UUID.randomUUID()
            sql("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$transitId','$tenant','TRANSIT','TRANSIT')")
            sql("UPDATE inventory_location SET area_id='${area(token)}',revision=revision+1 WHERE tenant_id='$tenant'")
            sql("INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch) SELECT gen_random_uuid(),tenant_id,'$userId',id,'$userId',0 FROM inventory_location WHERE tenant_id='$tenant'")
            val reel = receipt(StockQuantity.metres("1000"))
            receipt(StockQuantity.each("3000000000"), bulk = true)
            val serials = (1..10).map { receipt(StockQuantity.each("1")) }
            val issued = reel.copy(stockIdentityId=UUID.randomUUID(),locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            val remainder = reel.copy(stockIdentityId=UUID.randomUUID())
            post(move(reel,issued,StockQuantity.metres("100"),splits=listOf(PostingSplit(reel.stockIdentityId,0,listOf(
                SegmentChild(issued.stockIdentityId,StockQuantity.metres("100"),SegmentKind.CUT),
                SegmentChild(remainder.stockIdentityId,StockQuantity.metres("900"),SegmentKind.REMNANT)))),extra=listOf(remainder to StockQuantity.metres("900"))))
            val used = issued.copy(stockIdentityId=UUID.randomUUID(),locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER)
            val remnant = issued.copy(stockIdentityId=UUID.randomUUID())
            post(move(issued,used,StockQuantity.metres("82.5"),MovementKind.CONSUME,listOf(PostingSplit(issued.stockIdentityId,0,listOf(
                SegmentChild(used.stockIdentityId,StockQuantity.metres("82.5"),SegmentKind.CUT),
                SegmentChild(remnant.stockIdentityId,StockQuantity.metres("17.5"),SegmentKind.REMNANT)))),listOf(remnant to StockQuantity.metres("17.5")),listOf(
                PostingMaterialFact(UUID.randomUUID(),used.stockIdentityId,customer,workOrder,"Cable",StockQuantity.metres("82.5"),1,true,false))))
            val inspection = remnant.copy(locationId=warehouse,custodianId=warehouse,custodianKind=OwnerKind.WAREHOUSE,condition=WarehouseCondition.QUARANTINE)
            post(move(remnant,inspection,StockQuantity.metres("17.5"),MovementKind.RETURN))
            post(move(inspection,inspection.copy(condition=WarehouseCondition.SERVICEABLE),StockQuantity.metres("17.5")))
            val reserve = reservation(remainder,StockQuantity.metres("300"))
            post(reserve)
            post(reserve.copy(expectedRevision=1,operation=operation("PICK"),reservations=reserve.reservations.map {
                it.copy(expectedRevision=0,unpicked=StockQuantity.metres("200"),picked=StockQuantity.metres("100")) }))
            val onu = serials.first().copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(serials.first(),onu,StockQuantity.each("1")))
            post(move(onu,onu.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER),StockQuantity.each("1"),MovementKind.CONSUME,facts=listOf(
                PostingMaterialFact(UUID.randomUUID(),onu.stockIdentityId,customer,workOrder,"ONU",StockQuantity.each("1"),2,true,false))))
            post(move(serials[1],serials[1].copy(legalOwner=AssetLegalOwner.CUSTOMER),StockQuantity.each("1")))
            val transit = move(serials[2],serials[2].copy(locationId=transitId,custodianId=transitId,custodianKind=OwnerKind.TRANSIT),StockQuantity.each("1"))
            post(transit.copy(legs=transit.legs.map { if(it.direction==LegDirection.IN) it.copy(status=InventoryStatus.IN_TRANSIT) else it }))
            post(move(serials[3],serials[3].copy(condition=WarehouseCondition.QUARANTINE),StockQuantity.each("1")))
            post(move(serials[4],serials[4].copy(legalOwner=AssetLegalOwner.UNKNOWN),StockQuantity.each("1")))
        }
        val before = fixture.transaction { counts() }
        val stock = request("GET", "/api/v1/warehouse/stock", token)
        assertThat(stock.status).withFailMessage(stock.contentAsString).isEqualTo(200)
        val items = mapper.readTree(stock.contentAsString).path("items")
        val cable = items.single { it.path("skuId").asString()==fixture.sku.toString() }
        assertThat(cable.path("physical").path("quantityBase").asString()).isEqualTo("1000000")
        assertThat(cable.path("available").path("quantityBase").asString()).isEqualTo("617500")
        assertThat(cable.path("reservedUnpicked").path("quantityBase").asString()).isEqualTo("200000")
        assertThat(cable.path("reservedPicked").path("quantityBase").asString()).isEqualTo("100000")
        val devices = items.single { it.path("skuId").asString()==fixture.serialSku.toString() }
        assertThat(devices.path("physical").path("quantityBase").asString()).isEqualTo("9")
        assertThat(devices.path("available").path("quantityBase").asString()).isEqualTo("5")
        val unknown = mapper.readTree(request("GET", "/api/v1/warehouse/stock/unknown", token).contentAsString)
        assertThat(unknown.path("items")[0].path("available").asBoolean()).isFalse()
        assertThat(unknown.path("totalElements").asInt()).isEqualTo(1)
        val lot = fixture.transaction { scalar("SELECT id FROM inventory_lot WHERE sku_id='$sku'") }
        val tree = mapper.readTree(request("GET", "/api/v1/warehouse/lots/$lot/segments", token).contentAsString)
        assertThat(tree.path("totalElements").asInt()).isEqualTo(5)
        assertThat(tree.path("items").all { it.path("conserved").asBoolean() }).isTrue()
        val history = request("GET", "/api/v1/warehouse/lots/$lot/history?size=100", token)
        assertThat(history.status).withFailMessage(history.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(history.contentAsString).path("items").count { it.path("kind").asString()=="RESERVATION" }).isEqualTo(2)
        assertThat(request("GET", "/api/inventory/stock", token).contentAsString).doesNotContain("3000000000",fixture.bulkSku.toString(),fixture.sku.toString())
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
    }
}
