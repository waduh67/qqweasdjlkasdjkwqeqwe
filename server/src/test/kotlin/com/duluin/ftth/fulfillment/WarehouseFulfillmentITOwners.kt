package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.http.HttpMethod
import java.util.UUID

class WarehouseFulfillmentITOwners : WarehouseFulfillmentFixture() {
    @Test fun `linked order delivery succeeds after interruption without an HTTP principal`() {
        val stock = setupReceipt()
        val customer = created(stock.token, "/api/customers", """{"code":"ORDER-CUSTOMER","name":"Order customer","address":"Field","location":{"longitude":106.9,"latitude":-6.2}}""")
        var order = mapper.readTree(request("POST", "/api/orders", stock.token, """{"customerId":"$customer",
            "lines":[{"catalogItemId":"${stock.cable}","description":"Inspection","quantity":1}],
            "serviceAddress":{"address":"Field","city":"City","postalCode":"10000"},
            "operation":{"namespace":"test.order","key":"create","payloadHash":"create"}}""").contentAsString)
        val orderId = order.path("id").asString()
        for (action in listOf("SUBMIT", "ACCEPT", "SCHEDULE", "START_FULFILLING")) {
            val response = request("POST", "/api/orders/$orderId/$action", stock.token, """{"expectedRevision":${order.path("revision").asLong()},
                "appointment":{"startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z"},
                "operation":{"namespace":"test.order","key":"$action","payloadHash":"$action"}}""")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            order = mapper.readTree(response.contentAsString)
        }
        val job = prepared(stock.token, """{"type":"PREVENTIVE","title":"Linked order","customerId":"$customer","orderId":"$orderId","areaId":"${area(stock.token)}"}""")
        completeJob(job.first, job.second)
        FulfillmentSqlProbe(context, FulfillmentSqlPhase.COMPLETED_EFFECT) { error("Interrupted before order effect") }.use {
            assertThat(request("POST", "/api/work-orders/${job.first}/approve", stock.token, "{}").status).isEqualTo(200)
        }
        val frozen = fixture(stock.token).transaction { scalar("SELECT request_payload FROM fulfillment_approval_snapshot").decodeFulfillmentRequest() }

        val retry = TenantContext.runAs(frozen.tenantId) { context.getBean(FulfillmentCoordinator::class.java).process(frozen) }

        assertThat(retry.state).withFailMessage(retry.toString()).isEqualTo(FulfillmentState.APPLIED)
        assertThat(mapper.readTree(request("GET", "/api/orders/$orderId", stock.token).contentAsString).path("status").asString()).isEqualTo("FULFILLED")
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM order_operation WHERE namespace='workorder.fulfillment.approve'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
        }
    }

    @Test fun `explicit BNG link activates once without serial deployment or a second transaction effect`() {
        val stock = setupReceipt()
        val plan = created(stock.token, "/api/catalog/plans", """{"name":"Linked service","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""")
        val customerResponse = request("POST", "/api/customers", stock.token,
            """{"code":"SERVICE-CUSTOMER","name":"Service customer","address":"Field","planId":"$plan","location":{"longitude":106.9,"latitude":-6.2}}""")
        assertThat(customerResponse.status).withFailMessage(customerResponse.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(customerResponse.contentAsString)
        val subscription = customer.path("subscription").path("id").asString()
        val access = created(stock.token, "/api/bng/access", """{"subscriptionId":"$subscription","planId":"$plan","nasId":null}""")
        val job = prepared(stock.token, """{"type":"PSB","title":"Explicit service action","customerId":"${customer.path("id").asString()}",
            "subscriptionId":"$subscription","areaId":"${area(stock.token)}"}""")
        completeService(job.first, job.second)

        val approved = request("POST", "/api/work-orders/${job.first}/approve", stock.token, "{}")

        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        fixture(stock.token).transaction { assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("APPLIED") }
        assertThat(mapper.readTree(request("GET", "/api/bng/access/$access", stock.token).contentAsString).path("status").asString()).isEqualTo("ACTIVE")
        assertThat(request("POST", "/api/work-orders/${job.first}/approve", stock.token, "{}").status).isEqualTo(200)
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE effect_type='PROVISIONING' AND status='COMPLETED'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM onu")).isEqualTo("0")
        }
    }

    private fun created(token: String, path: String, body: String): String {
        val response = request("POST", path, token, body)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }

    private fun prepared(token: String, body: String): Pair<String, String> {
        val receiver = technician(token)
        val workOrder = created(token, "/api/work-orders", body)
        assign(token, workOrder, receiver.second)
        putPlan(token, workOrder, plan(token, workOrder, "[]", mode="NONE", reason="Service state only"))
        action(token, workOrder, "submit-request", command(token, workOrder, 1))
        val revision = summary(receiver.first, workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = """{"expectedRevision":0,"planRevision":1,"workOrderRevision":$revision,"materialMode":"NONE","reason":"Service state only","evidenceReference":"service-check","lines":[]}"""
        assertThat(request("POST", "/api/work-orders/$workOrder/materials/report-use", receiver.first, input).status).isEqualTo(200)
        return workOrder to receiver.first
    }

    private fun completeService(workOrder: String, token: String) {
        assertThat(request("POST", "/api/work-orders/$workOrder/start", token).status).isEqualTo(200)
        val png = byteArrayOf(-119,80,78,71,13,10,26,10,1,2,3,4,5)
        val artifacts = listOf("FAT","ODP","DROPCORE","ONT","ONU","OPTICAL_BEFORE","OPTICAL_AFTER","TECHNICIAN_SIGNATURE","LOCATION").map { kind ->
            val response = mvc.perform(multipart("/api/work-orders/$workOrder/evidence")
                .file(MockMultipartFile("file","$kind.png",MediaType.IMAGE_PNG_VALUE,png)).param("kind",kind)
                .header("Authorization","Bearer $token")).andReturn().response
            assertThat(response.status).isEqualTo(201)
            mapOf("kind" to kind,"revisionId" to mapper.readTree(response.contentAsString).path("revisionId").asString())
        }
        val signature = mvc.perform(multipart(HttpMethod.PUT,"/api/work-orders/$workOrder/signature")
            .file(MockMultipartFile("file","ack.png",MediaType.IMAGE_PNG_VALUE,png)).param("signerName","Customer")
            .header("Authorization","Bearer $token")).andReturn().response
        assertThat(signature.status).isEqualTo(200)
        val proof = mapper.readTree(request("GET","/api/work-orders/$workOrder/proof-of-work",token).contentAsString)
        val all = artifacts + mapOf("kind" to "CUSTOMER_ACKNOWLEDGEMENT","revisionId" to mapper.readTree(signature.contentAsString).path("revisionId").asString())
        val complete = request("POST","/api/work-orders/$workOrder/complete",token,mapper.writeValueAsString(mapOf("proofRevision" to proof.path("revision").asString(),"artifacts" to all)))
        assertThat(complete.status).withFailMessage(complete.contentAsString).isEqualTo(200)
    }
}
