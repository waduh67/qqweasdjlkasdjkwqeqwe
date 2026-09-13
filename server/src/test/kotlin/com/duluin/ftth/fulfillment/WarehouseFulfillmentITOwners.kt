package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.http.HttpMethod
import java.util.UUID

class WarehouseFulfillmentITOwners : WarehouseFulfillmentFixture() {
    @ParameterizedTest @ValueSource(strings=["PSB","DISMANTLE"])
    fun `NAS linked service records the real queued BNG handoff`(type: String) {
        val (stock,job) = serviceCase(type,true)
        completeService(job.first,job.second)

        assertThat(request("POST","/api/work-orders/${job.first}/approve",stock.token,"{}").status).isEqualTo(200)

        fixture(stock.token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("APPLIED")
            assertThat(scalar("SELECT cardinality(action_ids) FROM bng_fulfillment_receipt").toInt()).isGreaterThan(0)
            assertThat(scalar("SELECT status FROM subscriber_access")).isEqualTo(if(type=="PSB") "ACTIVE" else "TERMINATED")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
        }
    }

    @Test fun `completed BNG receipt cannot be redirected to a different handoff action`() {
        val (stock,job) = serviceCase("PSB",true)
        completeService(job.first,job.second)
        assertThat(request("POST","/api/work-orders/${job.first}/approve",stock.token,"{}").status).isEqualTo(200)

        org.assertj.core.api.Assertions.assertThatThrownBy { fixture(stock.token).transaction {
            sql("UPDATE bng_action SET action='DISCONNECT' WHERE id IN (SELECT unnest(action_ids) FROM bng_fulfillment_receipt)")
        } }.isInstanceOf(RuntimeException::class.java)
    }

    @Test fun `WO customer A cannot approve the eligible order owned by customer B`() {
        val stock = setupReceipt()
        val customerA = created(stock.token, "/api/customers", """{"code":"CUSTOMER-A","name":"Customer A","address":"Field","location":{"longitude":106.9,"latitude":-6.2}}""")
        val customerB = created(stock.token, "/api/customers", """{"code":"CUSTOMER-B","name":"Customer B","address":"Field","location":{"longitude":106.9,"latitude":-6.2}}""")
        var order = mapper.readTree(request("POST", "/api/orders", stock.token, """{"customerId":"$customerB",
            "lines":[{"catalogItemId":"${stock.cable}","description":"Inspection","quantity":1}],
            "serviceAddress":{"address":"Field","city":"City","postalCode":"10000"},
            "operation":{"namespace":"test.order","key":"create","payloadHash":"create"}}""").contentAsString)
        val orderId = order.path("id").asString()
        for (transition in listOf("SUBMIT", "ACCEPT", "SCHEDULE", "START_FULFILLING")) {
            val response = request("POST", "/api/orders/$orderId/$transition", stock.token, """{"expectedRevision":${order.path("revision").asLong()},
                "appointment":{"startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z"},
                "operation":{"namespace":"test.order","key":"$transition","payloadHash":"$transition"}}""")
            assertThat(response.status).isEqualTo(200)
            order = mapper.readTree(response.contentAsString)
        }
        val job = prepared(stock.token, """{"type":"PREVENTIVE","title":"Wrong order owner","customerId":"$customerA","orderId":"$orderId","areaId":"${area(stock.token)}"}""")
        completeJob(job.first, job.second)

        val response = request("POST", "/api/work-orders/${job.first}/approve", stock.token, "{}")

        val orderState = mapper.readTree(request("GET", "/api/orders/$orderId", stock.token).contentAsString).path("status").asString()
        println("T17-AV-1 approval=${response.status} order=$orderState")
        assertThat(response.status).isEqualTo(409)
        assertThat(orderState).isEqualTo("FULFILLING")
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT approval_status FROM work_order WHERE id='${job.first}'")).isEqualTo("PENDING")
            for (table in listOf("fulfillment_approval_snapshot", "fulfillment_checkpoint", "inventory_material_settlement", "fulfillment_effect_progress"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM order_operation WHERE namespace='workorder.fulfillment.approve'")).isEqualTo("0")
        }
        val actor = UUID.fromString(mapper.readTree(request("GET","/api/me",stock.token).contentAsString).path("id").asString())
        val database = fixture(stock.token)
        org.assertj.core.api.Assertions.assertThatThrownBy { database.transaction {
            FirstFulfillmentForgery(database,UUID.fromString(job.first),actor).insert(missingVisit=false,handoff=true)
        } }.hasStackTraceContaining("FULFILLMENT_ORDER_BINDING")
    }

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
        val (stock,job,access) = serviceCase()
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

    @Test fun `checked out visit applies with one authoritative operation receipt and transition`() {
        val (stock,job) = serviceCase()
        checkedOutVisit(stock.token,job.first)
        completeService(job.first,job.second)

        val response = request("POST", "/api/work-orders/${job.first}/approve",stock.token,"{}")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("APPLIED")
            assertThat(scalar("SELECT state FROM fieldservice_visit")).isEqualTo("SUBMITTED")
            for (table in listOf("fieldservice_visit_operation","fieldservice_fulfillment_receipt","fieldservice_fulfillment_transition","customer_fulfillment_receipt","bng_fulfillment_receipt"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
        }
    }

    @Test fun `late visit receipt failure rolls back every service owner and settlement`() {
        val (stock,job) = serviceCase()
        checkedOutVisit(stock.token,job.first)
        completeService(job.first,job.second)

        FulfillmentSqlProbe(context,FulfillmentSqlPhase.VISIT_RECEIPT) { error("late visit failure") }.use {
            assertThat(request("POST", "/api/work-orders/${job.first}/approve",stock.token,"{}").status).isEqualTo(200)
        }

        fixture(stock.token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("REQUIRES_RECONCILIATION")
            assertThat(scalar("SELECT status FROM subscription")).isEqualTo("PENDING")
            assertThat(scalar("SELECT status FROM subscriber_access")).isEqualTo("PENDING")
            assertThat(scalar("SELECT state FROM fieldservice_visit")).isEqualTo("CHECKED_OUT")
            for (table in listOf("inventory_material_settlement","fulfillment_effect_progress","workorder_fulfillment_result","customer_fulfillment_receipt",
                "bng_fulfillment_receipt","fieldservice_fulfillment_receipt","customer_fulfillment_transition","bng_fulfillment_transition","fieldservice_fulfillment_transition"))
                assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
        }
    }

    private fun checkedOutVisit(token: String,workOrder: String) = fixture(token).transaction {
        sql("""INSERT INTO fieldservice_visit(id,tenant_id,order_id,work_order_id,technician_id,state,revision,assignment_active)
            SELECT '${UUID.randomUUID()}','$tenant','${UUID.randomUUID()}','$workOrder',technician_id,'CHECKED_OUT',3,true
            FROM work_order_assignee WHERE work_order_id='$workOrder'""")
    }

    private fun serviceCase(type: String = "PSB", withNas: Boolean = false): Triple<Setup,Pair<String,String>,String> {
        val stock = setupReceipt()
        val plan = created(stock.token, "/api/catalog/plans", """{"name":"Linked service","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""")
        val customerResponse = request("POST", "/api/customers", stock.token,
            """{"code":"SERVICE-CUSTOMER","name":"Service customer","address":"Field","planId":"$plan","location":{"longitude":106.9,"latitude":-6.2}}""")
        assertThat(customerResponse.status).withFailMessage(customerResponse.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(customerResponse.contentAsString)
        val subscription = customer.path("subscription").path("id").asString()
        val nas = if (withNas) created(stock.token,"/api/bng/nas","""{"name":"Receipt NAS","vendor":"MIKROTIK"}""") else null
        val access = created(stock.token, "/api/bng/access", """{"subscriptionId":"$subscription","planId":"$plan","nasId":${nas?.let { "\"$it\"" } ?: "null"}}""")
        if (type=="DISMANTLE") assertThat(request("POST","/api/customers/subscriptions/$subscription/activate",stock.token).status).isEqualTo(200)
        val job = prepared(stock.token, """{"type":"$type","title":"Explicit service action","customerId":"${customer.path("id").asString()}",
            "subscriptionId":"$subscription","areaId":"${area(stock.token)}"}""")
        return Triple(stock,job,access)
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
