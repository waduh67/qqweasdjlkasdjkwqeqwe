package com.karuhun.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class WarehouseClientOperationsIT : WarehouseMasterHttpFixture() {
    private data class Setup(val owner: String,val warehouse: String,val supplier: String,val sku: String)
    private fun ok(method: String,path: String,s: Setup,body: Any? = null,status: Int=200,key: String=UUID.randomUUID().toString()): JsonNode {
        val result=request(method,"/api/v2/warehouse$path",s.owner,body?.let(mapper::writeValueAsString),key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(status)
        return mapper.readTree(result.contentAsString)
    }
    private fun setup(serial: Boolean=false): Setup {
        val owner=tenant()
        val warehouse=create("locations",owner,"""{"code":"WH","name":"Gudang","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val supplier=create("suppliers",owner,"""{"code":"VENDOR","name":"Vendor"}""").path("id").asString()
        val sku=create("skus",owner,"""{"code":"ITEM","name":"Barang","tracking":"${if(serial) "SERIAL" else "BULK"}","baseUnit":"EA"}""").path("id").asString()
        val s=Setup(owner,warehouse,supplier,sku)
        ok("POST","/workflow/drain",s,mapOf("expectedEpoch" to 0))
        val review=ok("GET","/workflow/review",s)
        ok("POST","/workflow/activate",s,mapOf("expectedEpoch" to 1,"reviewHash" to review.path("reviewHash").asString(),"reason" to "Client operations"))
        ok("PUT","/settings",s,mapOf("expectedRevision" to 0,"requireManagerApproval" to false,"overdueDays" to 7))
        return s
    }
    private fun purchase(s: Setup,quantity: String,requestId: String?=null,lineId: String?=null) = ok("POST","/purchases",s,mapOf("warehouseId" to s.warehouse,"supplierId" to s.supplier,
        "purchaseDate" to "2026-11-14","reference" to "PO-001","requestId" to requestId,"lines" to listOf(mapOf("skuId" to s.sku,"baseUnit" to "EA","quantityBase" to quantity,"requestLineId" to lineId))),201)
    private fun stock(s: Setup)=ok("GET","/stock/${s.sku}",s).path("positions")
    private fun receive(s: Setup,quantity: String,serial: String?=null)=ok("POST","/receipts",s,mapOf("warehouseId" to s.warehouse,"lines" to listOf(mapOf("skuId" to s.sku,"quantityBase" to quantity,
        "serials" to if(serial==null) emptyList<Any>() else listOf(mapOf("serial" to serial))))),201)
    private fun loan(s: Setup,quantity: String): JsonNode {
        val submitted=ok("POST","/loans",s,mapOf("warehouseId" to s.warehouse,"borrowerName" to "Client bebas","loanDate" to "2026-11-14","lines" to listOf(mapOf("skuId" to s.sku,"baseUnit" to "EA","quantityBase" to quantity))),201)
        return ok("POST","/loans/${submitted.path("id").asString()}/review",s,mapOf("expectedRevision" to 0,"lines" to listOf(mapOf("lineId" to submitted.path("lines")[0].path("id").asString(),"approvedBase" to quantity))))
    }
    private fun issue(s: Setup,row: JsonNode,identity: String)=ok("POST","/loans/${row.path("id").asString()}/issue",s,mapOf("expectedRevision" to row.path("revision").asLong(),"evidence" to "BA diserahkan",
        "lines" to listOf(mapOf("lineId" to row.path("lines")[0].path("id").asString(),"stocks" to listOf(mapOf("stockIdentityId" to identity,"quantityBase" to row.path("lines")[0].path("approvedBase").asString()))))))
    private fun returns(s: Setup,row: JsonNode,quantity: String,condition: String,serial: String?=null)=ok("POST","/loans/${row.path("id").asString()}/returns",s,mapOf("expectedRevision" to row.path("revision").asLong(),"evidence" to "Client mengembalikan",
        "lines" to listOf(mapOf("lineId" to row.path("lines")[0].path("id").asString(),"stockIdentityId" to row.path("lines")[0].path("stocks")[0].path("stockIdentityId").asString(),"quantityBase" to quantity,"condition" to condition,"observedSerial" to serial))))

    @Test fun purchaseReceiptReplayAndCancellation() {
        val s=setup();val row=purchase(s,"10");assertThat(stock(s).isEmpty).isTrue()
        val id=row.path("id").asString();val key=UUID.randomUUID().toString()
        val input=mapOf("expectedRevision" to 0,"lineId" to row.path("lines")[0].path("id").asString(),"quantityBase" to "4")
        val first=ok("POST","/purchases/$id/receipts",s,input,key=key)
        assertThat(first.path("state").asString()).isEqualTo("PARTIALLY_RECEIVED")
        assertThat(ok("POST","/purchases/$id/receipts",s,input,key=key)).isEqualTo(first)
        assertThat(stock(s).sumOf { it.path("quantityBase").asLong() }).isEqualTo(4)
        assertThat(ok("POST","/purchases/$id/cancel",s,mapOf("expectedRevision" to 1,"notes" to "Sisa tidak diperlukan")).path("state").asString()).isEqualTo("CANCELLED")
        assertThat(request("POST","/api/v2/warehouse/purchases/$id/receipts",s.owner,mapper.writeValueAsString(input+mapOf("expectedRevision" to 2))).status).isEqualTo(400)
    }
    @Test fun linkedOrderReservesProcurementAndPartialReceiptUpdatesRequest() {
        val s=setup();val submitted=ok("POST","/requests",s,mapOf("kind" to "PROCUREMENT","reason" to "Kebutuhan teknis","warehouseId" to s.warehouse,
            "lines" to listOf(mapOf("skuId" to s.sku,"baseUnit" to "EA","requestedBase" to "10"))),201)
        val id=submitted.path("id").asString();val line=submitted.path("lines")[0].path("id").asString()
        ok("POST","/requests/$id/review",s,mapOf("expectedRevision" to 0,"lines" to listOf(mapOf("lineId" to line,"approvedBase" to "10"))))
        val po=purchase(s,"8",id,line)
        assertThat(request("POST","/api/v2/warehouse/requests/$id/receipts",s.owner,mapper.writeValueAsString(mapOf("expectedRevision" to 1,"lineId" to line,"warehouseId" to s.warehouse,"quantityBase" to "3"))).status).isEqualTo(400)
        ok("POST","/purchases/${po.path("id").asString()}/receipts",s,mapOf("expectedRevision" to 0,"lineId" to po.path("lines")[0].path("id").asString(),"quantityBase" to "5"))
        assertThat(ok("GET","/requests/$id",s).path("request").path("lines")[0].path("receivedBase").asString()).isEqualTo("5")
    }
    @Test fun partialBulkReturnsSplitIdentitiesAndFollowVendorWarranty() {
        val s=setup();receive(s,"20");val approved=loan(s,"10")
        val active=issue(s,approved,stock(s).single().path("stockIdentityId").asString())
        val id=active.path("id").asString();val snapshot=ok("GET","/loans/$id/issue-snapshot",s)
        val partial=returns(s,active,"3","SERVICEABLE");val damaged=returns(s,partial,"2","DAMAGED")
        assertThat(damaged.path("state").asString()).isEqualTo("PARTIALLY_RETURNED")
        assertThat(ok("GET","/loans/$id/issue-snapshot",s)).isEqualTo(snapshot)
        assertThat(stock(s).filter { it.path("status").asString()=="AVAILABLE" }.sumOf { it.path("quantityBase").asLong() }).isEqualTo(13)
        val claim=ok("GET","/warranties",s).path("items").single();val wid=claim.path("id").asString()
        ok("POST","/warranties/$wid/dispatch",s,mapOf("expectedRevision" to 0,"supplierId" to s.supplier,"vendorReference" to "RMA-001","evidence" to "Dikirim"))
        ok("POST","/warranties/$wid/receive",s,mapOf("expectedRevision" to 1,"result" to "REPAIRED","evidence" to "Diperbaiki"))
        ok("POST","/warranties/$wid/inspect",s,mapOf("expectedRevision" to 2,"serviceable" to true,"evidence" to "Layak"))
        assertThat(stock(s).filter { it.path("status").asString()=="AVAILABLE" }.sumOf { it.path("quantityBase").asLong() }).isEqualTo(15)
        assertThat(returns(s,damaged,"5","SERVICEABLE").path("state").asString()).isEqualTo("RETURNED")
    }
    @Test fun replacementPreservesOriginalSerialAndRequiresReset() {
        val s=setup(true);receive(s,"1","OLD-001")
        val active=issue(s,loan(s,"1"),stock(s).single().path("stockIdentityId").asString());returns(s,active,"1","DAMAGED","OLD-001")
        val claim=ok("GET","/warranties",s).path("items").single();val id=claim.path("id").asString()
        ok("POST","/warranties/$id/dispatch",s,mapOf("expectedRevision" to 0,"supplierId" to s.supplier,"vendorReference" to "RMA-002","evidence" to "Dikirim"))
        ok("POST","/warranties/$id/receive",s,mapOf("expectedRevision" to 1,"result" to "REPLACED","replacementSerial" to "NEW-001","evidence" to "Pengganti"))
        val inspection=mapOf("expectedRevision" to 2,"serviceable" to true,"evidence" to "Diperiksa")
        assertThat(request("POST","/api/v2/warehouse/warranties/$id/inspect",s.owner,mapper.writeValueAsString(inspection)).status).isEqualTo(400)
        ok("POST","/warranties/$id/inspect",s,inspection+mapOf("resetConfirmed" to true))
        assertThat(stock(s).single { it.path("status").asString()=="AVAILABLE" }.path("serial").asString()).isEqualTo("NEW-001")
        assertThat(ok("GET","/loans/${active.path("id").asString()}/issue-snapshot",s).path("lines")[0].path("stocks")[0].path("serial").asString()).isEqualTo("OLD-001")
    }
    @Test fun managerCanRejectAndReplayWithoutLoanManagementPermission() {
        val s=setup()
        ok("PUT","/settings",s,mapOf("expectedRevision" to 1,"requireManagerApproval" to true,"overdueDays" to 7))
        val (manager,managerId)=user(s.owner,setOf("warehouse.loan.view","warehouse.request.approve"))
        val me=mapper.readTree(request("GET","/api/me",manager).contentAsString)
        assertThat(request("PUT","/api/users/$managerId/access",s.owner,mapper.writeValueAsString(mapOf(
            "roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(),"areaIds" to listOf(area(s.owner))))).status).isEqualTo(200)
        assertThat(request("PUT","/api/v1/warehouse/settings/scopes/$managerId/${s.warehouse}",s.owner,
            mapper.writeValueAsString(mapOf("expectedRevision" to 0,"active" to true))).status).isEqualTo(200)
        val reviewed=loan(s,"1");val id=reviewed.path("id").asString()
        assertThat(reviewed.path("state").asString()).isEqualTo("MANAGER_REVIEW")
        val decision=mapOf("expectedRevision" to 1,"approved" to false,"reason" to "Tidak diperlukan")
        val key=UUID.randomUUID().toString()
        val first=ok("POST","/loans/$id/decision",s.copy(owner=manager),decision,key=key)
        assertThat(first.path("state").asString()).isEqualTo("REJECTED")
        assertThat(ok("POST","/loans/$id/decision",s.copy(owner=manager),decision,key=key)).isEqualTo(first)
        assertThat(stock(s).isEmpty).isTrue()
    }
    @Test fun tenantScopeAndProjectionImmutability() {
        val s=setup();val po=purchase(s,"2");val id=po.path("id").asString();val foreign=setup()
        assertThat(request("GET","/api/v2/warehouse/purchases/$id",foreign.owner).status).isEqualTo(404)
        assertThatThrownBy { fixture(s.owner).transaction { jdbc { connection -> connection.createStatement().use {
            it.executeUpdate("UPDATE inventory_client_document SET revision=revision+1,snapshot=jsonb_set(snapshot,'{state}', '\"RECEIVED\"'::jsonb) WHERE id='$id'")
        } } } }.isInstanceOf(Exception::class.java)
        assertThat(ok("GET","/purchases/$id",s).path("document")).isEqualTo(po)
    }
}
