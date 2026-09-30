package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehousePostingFixture
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID

abstract class BngHandoffFixture : WarehouseFulfillmentFixture() {
    data class HandoffCase(val token: String, val workOrder: String, val approvalId: UUID, val xid: String)

    protected fun completedHandoff(): HandoffCase {
        val (token,workOrder)=preparedHandoff()
        assertThat(request("POST","/api/work-orders/$workOrder/approve",token,"{}").status).isEqualTo(200)
        val receipt=fixture(token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("APPLIED")
            assertThat(scalar("SELECT cardinality(action_ids) FROM bng_fulfillment_receipt")).isEqualTo("2")
            UUID.fromString(scalar("SELECT id FROM bng_fulfillment_receipt")) to scalar("SELECT created_xid::text FROM bng_fulfillment_receipt")
        }
        assertThat(request("POST","/api/work-orders/$workOrder/approve",token,"{}").status).isEqualTo(200)
        return HandoffCase(token,workOrder,receipt.first,receipt.second)
    }

    protected fun preparedHandoff(): Pair<String,String> {
        val stock = setupReceipt()
        fun create(path: String,body: String): tools.jackson.databind.JsonNode {
            val response=request("POST",path,stock.token,body)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            return mapper.readTree(response.contentAsString)
        }
        val planId=create("/api/catalog/plans","""{"name":"Handoff plan","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""").path("id").asString()
        val customer=create("/api/customers","""{"code":"HANDOFF","name":"Handoff customer","address":"Field","planId":"$planId","location":{"longitude":106.9,"latitude":-6.2}}""")
        val subscription=customer.path("subscription").path("id").asString()
        val nas=create("/api/bng/nas","""{"name":"Handoff NAS","vendor":"MIKROTIK"}""").path("id").asString()
        create("/api/bng/access","""{"subscriptionId":"$subscription","planId":"$planId","nasId":"$nas"}""")
        val receiver=technician(stock.token)
        val workOrder=create("/api/work-orders","""{"type":"PSB","title":"Handoff binding","customerId":"${customer.path("id").asString()}","subscriptionId":"$subscription","areaId":"${area(stock.token)}"}""").path("id").asString()
        assign(stock.token,workOrder,receiver.second)
        putPlan(stock.token,workOrder,plan(stock.token,workOrder,"[]",mode="NONE",reason="Service verification"))
        action(stock.token,workOrder,"submit-request",command(stock.token,workOrder,1))
        val revision=summary(receiver.first,workOrder).path("revisions").path("workOrderRevision").asLong()
        assertThat(request("POST","/api/work-orders/$workOrder/materials/report-use",receiver.first,
            """{"expectedRevision":0,"planRevision":1,"workOrderRevision":$revision,"materialMode":"NONE","reason":"Service verification","evidenceReference":"service-check","lines":[]}""").status).isEqualTo(200)
        completeService(workOrder,receiver.first)
        return stock.token to workOrder
    }

    protected fun unbound(case: HandoffCase): UUID {
        val id=UUID.randomUUID()
        fixture(case.token).transaction {
            sql("""INSERT INTO bng_action SELECT (jsonb_populate_record(NULL::bng_action,to_jsonb(original)||
                jsonb_build_object('id','$id','fulfillment_approval_id',null,'fulfillment_xid',null,'external_id',null))).*
                FROM bng_action original WHERE fulfillment_approval_id='${case.approvalId}' AND action='PROVISION' LIMIT 1""")
            assertThat(scalar("SELECT fulfillment_approval_id IS NULL AND fulfillment_xid IS NULL FROM bng_action WHERE id='$id'")).isEqualTo("t")
        }
        return id
    }

    internal fun WarehousePostingFixture.attach(case: HandoffCase, id: UUID) =
        sql("UPDATE bng_action SET fulfillment_approval_id='${case.approvalId}',fulfillment_xid='${case.xid}'::xid8 WHERE id='$id'")

    protected fun graph(case: HandoffCase): String = fixture(case.token).transaction {
        scalar("""SELECT jsonb_build_array(receipt.action_ids,receipt.action_bindings,
            (SELECT array_agg(id ORDER BY id) FROM bng_action WHERE fulfillment_approval_id=receipt.id AND fulfillment_xid=receipt.created_xid),
            (SELECT state FROM fulfillment_checkpoint WHERE work_order_id='${case.workOrder}'))::text
            FROM bng_fulfillment_receipt receipt WHERE id='${case.approvalId}'""")
    }

    private fun completeService(workOrder: String,token: String) {
        assertThat(request("POST","/api/work-orders/$workOrder/start",token).status).isEqualTo(200)
        val png=byteArrayOf(-119,80,78,71,13,10,26,10,1,2,3,4,5)
        val artifacts=listOf("FAT","ODP","DROPCORE","ONT","ONU","OPTICAL_BEFORE","OPTICAL_AFTER","TECHNICIAN_SIGNATURE","LOCATION").map { kind ->
            val response=mvc.perform(multipart("/api/work-orders/$workOrder/evidence").file(MockMultipartFile("file","$kind.png",MediaType.IMAGE_PNG_VALUE,png))
                .param("kind",kind).header("Authorization","Bearer $token")).andReturn().response
            assertThat(response.status).isEqualTo(201)
            mapOf("kind" to kind,"revisionId" to mapper.readTree(response.contentAsString).path("revisionId").asString())
        }
        val signature=mvc.perform(multipart(HttpMethod.PUT,"/api/work-orders/$workOrder/signature")
            .file(MockMultipartFile("file","ack.png",MediaType.IMAGE_PNG_VALUE,png)).param("signerName","Customer")
            .header("Authorization","Bearer $token")).andReturn().response
        assertThat(signature.status).isEqualTo(200)
        val proof=mapper.readTree(request("GET","/api/work-orders/$workOrder/proof-of-work",token).contentAsString)
        val all=artifacts+mapOf("kind" to "CUSTOMER_ACKNOWLEDGEMENT","revisionId" to mapper.readTree(signature.contentAsString).path("revisionId").asString())
        val response=request("POST","/api/work-orders/$workOrder/complete",token,mapper.writeValueAsString(mapOf("proofRevision" to proof.path("revision").asString(),"artifacts" to all)))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
}
