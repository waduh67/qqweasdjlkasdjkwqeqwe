package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MaterialHandoverWorkbenchIT : MaterialLifecycleFixture() {
    @Test fun `named dispatcher sources and sender grant complete the three party handover without hidden receipt identities`() {
        val case = residualCase("7500")
        val receipt = case.usage.receipt
        val admin = receipt.stock.token
        val receiver = technician(admin)
        assign(admin, receipt.workOrder, receiver.second)
        val target = create("locations", admin,
            """{"code":"NEW_TECH","name":"New technician custody","kind":"TECHNICIAN","custodianId":"${receiver.second}"}""").path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/$target", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val root = "/api/work-orders/${receipt.workOrder}/materials/handover-workbench"
        val sources = request("GET", "$root/sources?size=1", admin)
        assertThat(sources.status).withFailMessage(sources.contentAsString).isEqualTo(200)
        val source = mapper.readTree(sources.contentAsString).path("items").single()
        assertThat(source.path("sender").path("id").asString()).isEqualTo(receipt.receiver.second)
        assertThat(source.path("sender").path("name").asString()).isNotBlank()
        assertThat(source.path("source").path("id").asString()).isEqualTo(case.input.stockIdentityId.toString())
        assertThat(source.path("source").path("quantityBase").asString()).isEqualTo("17500")
        assertThat(source.path("source").path("sourceUsageId").asString()).isEqualTo(case.input.usageId.toString())
        val targets = request("GET", "$root/targets", admin)
        assertThat(targets.status).withFailMessage(targets.contentAsString).isEqualTo(200)
        val selected = mapper.readTree(targets.contentAsString).path("items").single()
        assertThat(selected.path("id").asString()).isEqualTo(target)
        assertThat(selected.path("receiver").path("id").asString()).isEqualTo(receiver.second)
        assertThat(request("GET", "$root/sources", receipt.receiver.first).status).isEqualTo(403)
        assertThat(request("GET", "$root/targets", receiver.first).status).isEqualTo(403)
        val revision = summary(admin, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = case.input.copy(workOrderRevision = revision, targetLocationId = UUID.fromString(target))
        val originalAccounting = usageAccounting(case.usage)
        val authorized = request("POST", "/api/work-orders/${receipt.workOrder}/materials/handover/authorize", admin, mapper.writeValueAsString(input), "authorized-transfer")
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val grantId = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()
        val pending = request("GET", "$root/pending", receipt.receiver.first)
        assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(200)
        val grant = mapper.readTree(pending.contentAsString).path("items").single()
        assertThat(grant.path("id").asString()).isEqualTo(grantId)
        assertThat(grant.path("currentQuantityBase").asString()).isEqualTo("17500")
        assertThat(grant.path("request").path("quantityBase").asString()).isEqualTo("7500")
        assertThat(grant.path("receiver").path("name").asString()).isNotBlank()
        assertThat(grant.path("dispatcher").path("name").asString()).isNotBlank()
        assertThat(grant.path("location").path("name").asString()).isEqualTo("New technician custody")
        assertThat(pending.contentAsString + sources.contentAsString).doesNotContain("payloadHash", "operationKey", "sessionId", "cost", "email")
        assertThat(request("GET", "$root/pending/$grantId", receipt.receiver.first).status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", "$root/pending", receiver.first).contentAsString).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "$root/pending/$grantId", receiver.first).status).isEqualTo(404)
        assertThat(usageAccounting(case.usage)).isEqualTo(originalAccounting)
        val dispatched = request("POST", "/api/work-orders/${receipt.workOrder}/materials/handover", receipt.receiver.first,
            mapper.writeValueAsString(input.copy(authorizationId = UUID.fromString(grantId))), "sender-transfer")
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        assertThat(request("GET", "$root/pending/$grantId", receipt.receiver.first).status).isEqualTo(404)
        val own = request("GET", "/api/v1/warehouse/my-materials/${receipt.workOrder}/residuals", receiver.first)
        assertThat(own.status).withFailMessage(own.contentAsString).isEqualTo(200)
        val awaiting = mapper.readTree(own.contentAsString).path("items").single()
        val ack = request("POST", "/api/work-orders/${receipt.workOrder}/materials/residuals/acknowledge", receiver.first,
            mapper.writeValueAsString(mapOf("documentId" to awaiting.path("id").asString(), "expectedRevision" to awaiting.path("revision").asLong(), "evidenceReference" to "New technician signed")), "receiver-transfer")
        assertThat(ack.status).withFailMessage(ack.contentAsString).isEqualTo(200)
        fixture(admin).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED' AND custody_owner_id='${receiver.second}'")).isEqualTo("7500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='ISSUED' AND custody_owner_id='${receipt.receiver.second}'")).isEqualTo("10000")
        }
    }

    @Test fun `dispatcher source and target scope filter before pagination and reject selectors for another actor`() {
        val case = residualCase()
        val receipt = case.usage.receipt
        val admin = receipt.stock.token
        val receiver = technician(admin)
        assign(admin, receipt.workOrder, receiver.second)
        val target = create("locations", admin,
            """{"code":"SCOPED_RECEIVER","name":"Scoped receiver custody","kind":"TECHNICIAN","custodianId":"${receiver.second}"}""").path("id").asString()
        val revision = summary(admin, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = case.input.copy(workOrderRevision = revision, targetLocationId = UUID.fromString(target))
        val dispatcher = user(admin, setOf("workorder.order.assign", "inventory.issue.manage"))
        val profile = mapper.readTree(request("GET", "/api/me", dispatcher.first).contentAsString)
        assertThat(request("PUT", "/api/users/${dispatcher.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to profile.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val root = "/api/work-orders/${receipt.workOrder}/materials/handover-workbench"
        for (resource in listOf("sources", "targets")) {
            val empty = request("GET", "$root/$resource?size=1", dispatcher.first)
            assertThat(empty.status).withFailMessage(empty.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(empty.contentAsString).path("totalElements").asLong()).isZero()
        }
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${dispatcher.second}/${receipt.field}", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", "$root/sources", dispatcher.first).contentAsString).path("totalElements").asLong()).isEqualTo(1)
        assertThat(mapper.readTree(request("GET", "$root/sources?size=1&page=1", dispatcher.first).contentAsString).path("items").isEmpty).isTrue()
        val authorize = "/api/work-orders/${receipt.workOrder}/materials/handover/authorize"
        assertThat(request("POST", authorize, dispatcher.first, mapper.writeValueAsString(input), "scoped-handover").status).isEqualTo(404)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${dispatcher.second}/$target", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", "$root/targets", dispatcher.first).contentAsString).path("totalElements").asLong()).isEqualTo(1)
        val granted = request("POST", authorize, dispatcher.first, mapper.writeValueAsString(input), "scoped-handover")
        assertThat(granted.status).withFailMessage(granted.contentAsString).isEqualTo(200)
        assertThat(request("POST", authorize, dispatcher.first, mapper.writeValueAsString(input), "scoped-handover").contentAsString).isEqualTo(granted.contentAsString)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${dispatcher.second}/${receipt.field}", admin,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(mapper.readTree(request("GET", "$root/sources", dispatcher.first).contentAsString).path("totalElements").asLong()).isZero()
        assertThat(request("POST", authorize, dispatcher.first, mapper.writeValueAsString(input), "scoped-handover").status).isEqualTo(404)
        for (suffix in listOf("?page=0&page=1", "?size=101", "?actorId=${receipt.receiver.second}"))
            assertThat(request("GET", "$root/sources$suffix", dispatcher.first).status).isEqualTo(400)
        assertThat(request("GET", "$root/sources", tenant()).status).isEqualTo(404)
    }
}
