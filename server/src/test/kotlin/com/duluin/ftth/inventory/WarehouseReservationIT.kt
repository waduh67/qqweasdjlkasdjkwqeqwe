package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReservationIT : WarehouseReceiptHttpFixture() {
    @Test fun `partial demand is durable and does not create physical movement`() {
        val token = tenant()
        val fixture = fixture(token).also { it.setup() }
        val actor = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        val document = UUID.randomUUID()
        val plan = UUID.randomUUID()
        fixture.transaction {
            receipt(StockQuantity.metres("60"))
            sql("UPDATE inventory_location SET area_id='${area(token)}',revision=revision+1 WHERE tenant_id='$tenant'")
            sql("INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch) SELECT gen_random_uuid(),tenant_id,'$actor',id,'$actor',0 FROM inventory_location WHERE tenant_id='$tenant'")
            sql("INSERT INTO work_order(id,tenant_id,code,type,title,status,created_by,area_id) VALUES ('$workOrder','$tenant','RESERVATION','PREVENTIVE','Reservation','DRAFT','$actor','${area(token)}')")
            sql("INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id) VALUES ('$plan','$tenant','$workOrder',1,0,'MATERIAL_REQUIRED','$actor')")
            sql("INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit,continuous_cut) VALUES (gen_random_uuid(),'$tenant','$plan',1,'$sku',100000,'MM',false)")
            sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=now(),revision=1 WHERE id='$plan'")
            sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,work_order_revision,plan_revision,submitted_at,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','DEMAND','$actor','$workOrder',0,1,now(),0,0)")
            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,quantity_base,base_unit,tracking,continuous_cut) VALUES (gen_random_uuid(),'$tenant','$document',1,0,'$sku',100000,'MM','LOT',false)")
            sql("UPDATE inventory_document SET state='SUBMITTED',revision=1 WHERE id='$document'")
        }
        val legs = fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        val body = """{"expectedRevision":1,"workOrderRevision":0,"planRevision":1}"""
        val path = "/api/v1/warehouse/material-requests/$document/reserve"
        val response = request("POST", path, token, body, "partial")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val result = mapper.readTree(response.contentAsString)
        assertThat(result.path("state").asString()).isEqualTo("PART_RESERVED")
        assertThat(result.path("lines")[0].path("reservedUnpickedBase").asString()).isEqualTo("60000")
        assertThat(result.path("lines")[0].path("backorderBase").asString()).isEqualTo("40000")
        assertThat(request("POST", path, token, body, "partial").contentAsString).isEqualTo(response.contentAsString)
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }).isEqualTo(legs)
        assertThat(fixture.transaction { scalar("SELECT count(*) FROM inventory_reservation WHERE state='OPEN'") }).isEqualTo("1")
    }
}
