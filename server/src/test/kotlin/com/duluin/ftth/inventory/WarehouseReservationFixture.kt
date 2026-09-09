package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode
import java.util.UUID

abstract class WarehouseReservationFixture : WarehouseReceiptHttpFixture() {
    internal data class Demand(val fixture: WarehousePostingFixture, val token: String, val document: UUID, val line: UUID, val workOrder: UUID)

    internal fun prepare(): Pair<String, WarehousePostingFixture> {
        val token = tenant()
        val fixture = fixture(token).also { it.setup() }
        val actor = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        fixture.transaction {
            sql("UPDATE inventory_location SET area_id='${area(token)}',revision=revision+1 WHERE tenant_id='$tenant'")
            sql("INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch) SELECT gen_random_uuid(),tenant_id,'$actor',id,'$actor',0 FROM inventory_location WHERE tenant_id='$tenant'")
        }
        return token to fixture
    }
    internal fun demand(token: String, fixture: WarehousePostingFixture, quantity: Long, continuous: Boolean = true, serial: Boolean = false,
        skuId: UUID? = null, lineCount: Int = 1): Demand {
        val actor = mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString()
        val workOrder = UUID.randomUUID()
        val document = UUID.randomUUID()
        val line = UUID.randomUUID()
        val plan = UUID.randomUUID()
        fixture.transaction {
            val selectedSku = skuId ?: if (serial) serialSku else sku
            val unit = if (serial) "EA" else "MM"
            val tracking = if (serial) "SERIAL" else "LOT"
            sql("INSERT INTO work_order(id,tenant_id,code,type,title,status,created_by,area_id) VALUES ('$workOrder','$tenant','${workOrder.toString().take(18)}','PREVENTIVE','Reservation','DRAFT','$actor','${area(token)}')")
            sql("INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id) VALUES ('$plan','$tenant','$workOrder',1,0,'MATERIAL_REQUIRED','$actor')")
            for (number in 1..lineCount) sql("INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit,continuous_cut) VALUES (gen_random_uuid(),'$tenant','$plan',$number,'$selectedSku',$quantity,'$unit',$continuous)")
            sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=now(),revision=1 WHERE id='$plan'")
            sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,work_order_revision,plan_revision,submitted_at,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','DEMAND','$actor','$workOrder',0,1,now(),0,0)")
            for (number in 1..lineCount) sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,quantity_base,base_unit,tracking,continuous_cut) VALUES ('${if (number == 1) line else UUID.randomUUID()}','$tenant','$document',$number,0,'$selectedSku',$quantity,'$unit','$tracking',$continuous)")
            sql("UPDATE inventory_document SET state='SUBMITTED',revision=1 WHERE id='$document'")
        }
        return Demand(fixture, token, document, line, workOrder)
    }
    internal fun reserve(demand: Demand, revision: Long = 1, extra: String = "", key: String = UUID.randomUUID().toString()) =
        call(demand, "reserve", """{"expectedRevision":$revision,"workOrderRevision":0,"planRevision":1$extra}""", key)

    internal fun call(demand: Demand, action: String, body: String, key: String = UUID.randomUUID().toString()): JsonNode {
        val result = request("POST", "/api/v1/warehouse/material-requests/${demand.document}/$action", demand.token, body, key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
    internal fun allocations(demand: Demand): JsonNode {
        val result = request("GET", "/api/v1/warehouse/material-requests/allocations/${demand.workOrder}", demand.token)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
    internal fun mutation(revision: Long, row: JsonNode, amount: String, extra: String = "") =
        """{"expectedRevision":$revision,"workOrderRevision":0,"planRevision":1,"reason":"Explicit warehouse action",
            "allocations":[{"reservationId":"${row.path("reservationId").asString()}","expectedRevision":${row.path("reservationRevision").asLong()},"quantityBase":"$amount"}]$extra}"""
    internal fun available(demand: Demand): String {
        val result = request("GET", "/api/v1/warehouse/stock?skuId=${demand.fixture.sku}", demand.token)
        assertThat(result.status).isEqualTo(200)
        return mapper.readTree(result.contentAsString).path("items")[0].path("available").path("quantityBase").asString()
    }
    internal fun receive(fixture: WarehousePostingFixture, quantity: String, serial: Boolean = false): PostingDimension = fixture.transaction {
        receipt(if (serial) StockQuantity.each(quantity) else StockQuantity.metres(quantity))
    }
    internal fun <T> authenticated(token: String, fixture: WarehousePostingFixture, action: () -> T): T {
        val security = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val previous = security.authentication
        security.authentication = com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter().convert(
            context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder::class.java).decode(token))
        try { return com.duluin.ftth.common.tenant.TenantContext.runAs(fixture.tenant, action) }
        finally { security.authentication = previous }
    }
}
