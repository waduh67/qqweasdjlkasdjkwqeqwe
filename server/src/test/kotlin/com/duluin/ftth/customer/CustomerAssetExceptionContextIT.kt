package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CustomerAssetExceptionContextIT : CustomerAssetOwnershipFixture() {
    private val proposalPermissions = setOf("customer.onu.view", "inventory.approval.request", "inventory.approval.view",
        "inventory.custody.manage", "workorder.evidence.view")

    private fun office(case: OwnershipCase, permissions: Set<String> = proposalPermissions, locationAccess: Boolean = true): String {
        val admin = case.installation.receipt.stock.token
        val actor = user(admin, permissions)
        val principal = mapper.readTree(request("GET", "/api/users/${actor.second}", admin).contentAsString)
        val access = request("PUT", "/api/users/${actor.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin)))))
        assertThat(access.status).withFailMessage(access.contentAsString).isEqualTo(200)
        if (locationAccess) {
            val source = fixture(admin).transaction { scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='${case.installation.receipt.input.lines.single().stockIdentityId}'") }
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${actor.second}/$source", admin,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        return actor.first
    }

    private fun path(case: OwnershipCase) = "/api/customers/${case.installation.customer}/assets/${case.installation.operation}/exceptions/context"

    @ParameterizedTest
    @ValueSource(strings = ["LOAN", "SALE"])
    fun `office requester obtains exact named context and creates a proposal without technician assignment or title effects`(mode: String) {
        val case = ownershipCase(mode)
        assertThat(accept(case).status).isEqualTo(200)
        val actor = office(case)
        val before = title(case)
        val response = request("GET", path(case), actor)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val context = mapper.readTree(response.contentAsString)
        val ownership = context.path("ownership")
        assertThat(ownership.path("assignmentId").asString()).isEqualTo(case.installation.operation.toString())
        assertThat(ownership.path("assetId").asString()).isEqualTo(case.installation.receipt.input.lines.single().stockIdentityId.toString())
        assertThat(ownership.path("assignmentRevision").asLong()).isEqualTo(1)
        assertThat(ownership.path("titleRevision").asLong()).isEqualTo(if (mode == "SALE") 1 else 0)
        assertThat(context.path("customerLabel").asString()).isNotBlank()
        assertThat(context.path("workOrder").path("code").asString()).isNotBlank()
        assertThat(context.path("workOrder").path("id").asString()).isEqualTo(case.installation.receipt.workOrder.toString())
        assertThat(context.path("workOrder").path("signature").path("id").asString()).isEqualTo(case.signature.toString())
        assertThat(context.path("canRequestTitleCorrection").asBoolean()).isTrue()
        assertThat(context.path("canRequestLoss").asBoolean()).isEqualTo(mode == "LOAN")
        assertThat(response.contentAsString).doesNotContain("storage_key", "storageKey", "digest", "reference")
        val report = request("GET", "/api/customers/${case.installation.customer}/assets/ownership", actor)
        assertThat(report.status).withFailMessage(report.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(report.contentAsString).single().path("legalOwner").asString()).isEqualTo(if (mode == "SALE") "CUSTOMER" else "ISP")
        val body = mapper.writeValueAsString(mapOf("assignmentId" to case.installation.operation,
            "sourceHandoverId" to ownership.path("handoverId").asString(), "expectedAssignmentRevision" to ownership.path("assignmentRevision").asLong(),
            "expectedTitleRevision" to ownership.path("titleRevision").asLong(), "targetOwner" to if (mode == "SALE") "ISP" else "CUSTOMER",
            "reason" to "Office request using current signed context", "evidenceId" to context.path("workOrder").path("signature").path("id").asString()))
        val created = request("POST", "/api/v1/warehouse/asset-title-corrections", actor, body, "office-title")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/v1/warehouse/asset-title-corrections", actor, body, "office-title").contentAsString).isEqualTo(created.contentAsString)
        assertThat(title(case)).isEqualTo(before)
        assertThat(request("GET", "/api/customers/${case.installation.customer}/assets/workbench/jobs/${case.installation.receipt.workOrder}", actor).status)
            .isIn(403, 404)
    }

    @Test
    fun `context requires actual evidence read permission and current warehouse scope`() {
        val case = ownershipCase()
        assertThat(accept(case).status).isEqualTo(200)
        val noEvidence = office(case, proposalPermissions - "workorder.evidence.view")
        assertThat(request("GET", path(case), noEvidence).status).isEqualTo(404)
        val noProposal = office(case, proposalPermissions - "inventory.approval.request")
        assertThat(request("GET", path(case), noProposal).status).isEqualTo(403)
        val noLocation = office(case, locationAccess = false)
        assertThat(request("GET", path(case), noLocation).status).isIn(403, 404)
        val viewer = office(case, setOf("customer.onu.view", "workorder.evidence.view"))
        assertThat(request("GET", path(case), viewer).status).isEqualTo(403)
        assertThat(title(case)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `selected context rejects another customer foreign tenant and unaccepted installation`() {
        val case = ownershipCase()
        val admin = case.installation.receipt.stock.token
        val actor = office(case)
        assertThat(request("GET", path(case), actor).status).isEqualTo(409)
        assertThat(accept(case).status).isEqualTo(200)
        assertThat(request("GET", path(case).replace(case.installation.customer.toString(), UUID.randomUUID().toString()), actor).status).isEqualTo(404)
        assertThat(request("GET", path(case).replace(case.installation.operation.toString(), UUID.randomUUID().toString()), actor).status).isEqualTo(404)
        val foreign = tenant()
        assertThat(request("GET", path(case), foreign).status).isEqualTo(404)
        val inaccessible = request("POST", "/api/areas", admin, """{"code":"OTHER","name":"Other area"}""")
        assertThat(inaccessible.status).isEqualTo(201)
        val other = mapper.readTree(inaccessible.contentAsString).path("id").asString()
        fixture(admin).transaction { sql("UPDATE customer SET area_id='$other' WHERE id='${case.installation.customer}'") }
        assertThat(request("GET", path(case), actor).status).isEqualTo(404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["CONTEXT", "OWNERSHIP"])
    fun `customer area moving while a read waits on its late customer lock cannot disclose context`(kind: String) {
        val case = ownershipCase()
        assertThat(accept(case).status).isEqualTo(200)
        val admin = case.installation.receipt.stock.token
        val actor = office(case)
        val second = request("POST", "/api/areas", admin, """{"code":"OTHER","name":"Other area"}""")
        assertThat(second.status).isEqualTo(201)
        val other = mapper.readTree(second.contentAsString).path("id").asString()
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var read: java.util.concurrent.Future<org.springframework.mock.web.MockHttpServletResponse>
            fixture(admin).transaction {
                sql("SELECT id FROM customer WHERE id='${case.installation.customer}' FOR UPDATE")
                val holder = scalar("SELECT pg_backend_pid()").toInt()
                val endpoint = if (kind == "CONTEXT") path(case) else "/api/customers/${case.installation.customer}/assets/ownership"
                read = executor.submit<org.springframework.mock.web.MockHttpServletResponse> { request("GET", endpoint, actor) }
                jdbc { awaitCustomerWait(it, holder) }
                sql("UPDATE customer SET area_id='$other' WHERE id='${case.installation.customer}'")
            }
            val response = read.get(40, TimeUnit.SECONDS)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(404)
            assertThat(mapper.readTree(response.contentAsString).has("ownership")).isFalse()
        }
    }

    private fun awaitCustomerWait(connection: Connection, holder: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        connection.prepareStatement("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))").use { query ->
            query.setInt(1, holder)
            while (System.nanoTime() < deadline) {
                if (query.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }) return
                Thread.sleep(10)
            }
        }
        error("Context did not reach the customer row lock")
    }

    @ParameterizedTest
    @ValueSource(strings = ["TITLE_FRESH", "TITLE_REPLAY", "LOSS_FRESH", "LOSS_REPLAY"])
    fun `customer area change rejects captured proposal POST and historical replay after the customer lock wait`(scenario: String) {
        val case = ownershipCase()
        assertThat(accept(case).status).isEqualTo(200)
        val admin = case.installation.receipt.stock.token
        val actor = office(case)
        val read = request("GET", path(case), actor)
        assertThat(read.status).withFailMessage(read.contentAsString).isEqualTo(200)
        val context = mapper.readTree(read.contentAsString)
        val source = context.path("ownership")
        val loss = scenario.startsWith("LOSS")
        val input = mutableMapOf<String, Any>("assignmentId" to case.installation.operation,
            "sourceHandoverId" to source.path("handoverId").asString(), "expectedTitleRevision" to source.path("titleRevision").asLong(),
            "reason" to "Captured proposal with current customer context", "evidenceId" to case.signature)
        if (loss) {
            val sink = create("locations", admin, """{"code":"LOSS","name":"Lost devices","kind":"LOST"}""").path("id").asString()
            val actorId = mapper.readTree(request("GET", "/api/me", actor).contentAsString).path("id").asString()
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/$actorId/$sink", admin,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
            input["expectedRevision"] = source.path("assignmentRevision").asLong()
            input["expectedWorkOrderRevision"] = context.path("workOrder").path("revision").asLong()
            input["destinationLocationId"] = sink
        } else {
            input["expectedAssignmentRevision"] = source.path("assignmentRevision").asLong()
            input["targetOwner"] = "CUSTOMER"
        }
        val endpoint = "/api/v1/warehouse/${if (loss) "asset-losses" else "asset-title-corrections"}"
        val body = mapper.writeValueAsString(input)
        val replay = scenario.endsWith("REPLAY")
        if (replay) {
            val created = request("POST", endpoint, actor, body, "captured-proposal")
            assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
            assertThat(request("POST", endpoint, actor, body, "captured-proposal").contentAsString).isEqualTo(created.contentAsString)
        }
        val second = request("POST", "/api/areas", admin, """{"code":"OTHER","name":"Other area"}""")
        assertThat(second.status).isEqualTo(201)
        val other = mapper.readTree(second.contentAsString).path("id").asString()
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var submitted: java.util.concurrent.Future<org.springframework.mock.web.MockHttpServletResponse>
            fixture(admin).transaction {
                sql("SELECT id FROM customer WHERE id='${case.installation.customer}' FOR UPDATE")
                val holder = scalar("SELECT pg_backend_pid()").toInt()
                submitted = executor.submit<org.springframework.mock.web.MockHttpServletResponse> { request("POST", endpoint, actor, body, "captured-proposal") }
                jdbc { awaitCustomerWait(it, holder) }
                sql("UPDATE customer SET area_id='$other' WHERE id='${case.installation.customer}'")
            }
            val rejected = submitted.get(40, TimeUnit.SECONDS)
            assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(404)
        }
        fixture(admin).transaction {
            val table = if (loss) "inventory_asset_loss_request" else "inventory_asset_title_request"
            assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo(if (replay) "1" else "0")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='LOSS'")).isEqualTo("0")
        }
        assertThat(title(case)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|1|1")
    }
}
