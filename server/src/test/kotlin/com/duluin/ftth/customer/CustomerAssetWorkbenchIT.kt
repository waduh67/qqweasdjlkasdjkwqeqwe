package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerAssetWorkbenchIT : CustomerAssetReplacementFixture() {
    private fun read(path: String, token: String): tools.jackson.databind.JsonNode {
        val response = request("GET", path, token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.contentAsString).doesNotContain("costTotal", "payloadHash", "storageKey", "sessionId")
        return mapper.readTree(response.contentAsString)
    }
    private fun customer(case: ReceiptCase) = fixture(case.stock.token).transaction { scalar("SELECT customer_id FROM work_order WHERE id='${case.workOrder}'") }

    @Test fun `only acknowledged own serial is eligible and real installation becomes named customer history`() {
        val case = receiptCase(serial = true, installation = true, extraPermissions = setOf("customer.onu.view"))
        val customer = customer(case)
        val root = "/api/customers/$customer/assets/workbench"
        val sourcePath = "$root/jobs/${case.workOrder}/sources"
        assertThat(read(root, case.receiver.first).path("unresolvedDevices").asLong()).isZero()
        assertThat(read(sourcePath, case.receiver.first).path("totalElements").asLong()).isZero()
        received(case)
        val before = accounting(case)
        val job = read("$root/jobs", case.receiver.first).path("items").single()
        val selected = read(sourcePath, case.receiver.first).path("items").single()
        val source = selected.path("source")
        assertThat(source.path("serial").asString()).isEqualTo(case.input.lines.single().serial)
        assertThat(source.path("issueLineId").asString()).isEqualTo(case.input.lines.single().issueLineId.toString())
        assertThat(selected.path("ownershipModes").asSequence().map { it.asString() }.toList()).containsExactly("LOAN", "SALE")
        assertThat(read("$sourcePath/${selected.path("id").asString()}", case.receiver.first)).isEqualTo(selected)
        assertThat(accounting(case)).isEqualTo(before)
        val authorize = request("POST", "/api/work-orders/${case.workOrder}/assets/authorize", case.receiver.first,
            """{"expectedRevision":${job.path("revision").asLong()},"assetId":${selected.path("id")},"issueLineId":${source.path("issueLineId")},"purpose":"INSTALL","ownershipMode":"LOAN"}""", "read-authorize")
        assertThat(authorize.status).withFailMessage(authorize.contentAsString).isEqualTo(200)
        val permit = mapper.readTree(authorize.contentAsString)
        val input = """{"authorizationId":${permit.path("authorizationId")},"expectedRevision":${permit.path("revision").asLong()},"topology":null}"""
        val installed = request("POST", "/api/customers/$customer/assets/install", case.receiver.first, input, "read-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/customers/$customer/assets/install", case.receiver.first, input, "read-install").contentAsString).isEqualTo(installed.contentAsString)
        assertThat(read(sourcePath, case.receiver.first).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "$sourcePath/${selected.path("id").asString()}", case.receiver.first).status).isEqualTo(404)
        val row = read("$root/history", case.receiver.first).path("items").single()
        val asset = row.path("asset")
        assertThat(asset.path("id").asString()).isEqualTo(permit.path("operationId").asString())
        assertThat(asset.path("serial").asString()).isEqualTo(source.path("serial").asString())
        assertThat(asset.path("origin").path("code").asString()).isNotBlank()
        assertThat(asset.path("legalOwner").asString()).isEqualTo("ISP")
        assertThat(asset.path("handoverState").asString()).isEqualTo("PENDING")
        assertThat(asset.path("revision").asLong()).isZero()
        assertThat(row.path("episode").path("episodeRevision").asLong()).isZero()
        assertThat(read("$root/history/${asset.path("id").asString()}", case.receiver.first)).isEqualTo(row)
    }

    @Test fun `customer sources apply current actor location and job fences before paging and forbid raw selectors`() {
        val install = installation(extraPermissions = setOf("customer.onu.view"))
        val case = install.receipt
        val root = "/api/customers/${install.customer}/assets/workbench"
        val sources = "$root/jobs/${case.workOrder}/sources"
        assertThat(read("$sources?size=1", case.receiver.first).path("totalElements").asLong()).isEqualTo(1)
        val other = technician(case.stock.token, setOf("customer.onu.view", "customer.onu.assign"))
        assertThat(read("$root/jobs", other.first).path("totalElements").asLong()).isZero()
        assertThat(request("GET", sources, other.first).status).isEqualTo(404)
        val foreign = installation(extraPermissions = setOf("customer.onu.view"))
        assertThat(request("GET", root, foreign.receipt.receiver.first).status).isEqualTo(404)
        assertThat(request("GET", "/api/customers/${UUID.randomUUID()}/assets/workbench", case.receiver.first).status).isEqualTo(404)
        assertThat(request("GET", "$sources/${UUID.randomUUID()}", case.receiver.first).status).isEqualTo(404)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receiver.second}/${case.field}", case.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(read("$sources?size=1", case.receiver.first).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "$sources/${case.input.lines.single().stockIdentityId}", case.receiver.first).status).isEqualTo(404)
        for (suffix in listOf("?actorId=${other.second}", "?serial=RECEIVE-1", "?page=0&page=1", "?size=101", "?page=-1"))
            assertThat(request("GET", "$sources$suffix", case.receiver.first).status).isEqualTo(400)
        for (resource in listOf(root, "$root/history", "$root/jobs", sources))
            assertThat(request("GET", resource, case.customerActor?.first ?: technician(case.stock.token).first).status).isEqualTo(403)
    }

    @Test fun `sale title and accepted handover show actual current revisions without pretending loan recovery`() {
        val case = ownershipCase("SALE")
        val token = case.installation.receipt.stock.token
        val root = "/api/customers/${case.installation.customer}/assets/workbench/history"
        val before = read(root, token).path("items").single().path("asset")
        assertThat(before.path("ownershipMode").asString()).isEqualTo("SALE")
        assertThat(before.path("legalOwner").asString()).isEqualTo("ISP")
        assertThat(accept(case).status).isEqualTo(200)
        val row = read(root, token).path("items").single().path("asset")
        assertThat(row.path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(row.path("titleRevision").asLong()).isEqualTo(1)
        assertThat(row.path("revision").asLong()).isEqualTo(1)
        assertThat(row.path("handoverState").asString()).isEqualTo("ACCEPTED")
        assertThat(row.path("recoveryRequired").asBoolean()).isFalse()
    }

    @Test fun `replacement history preserves old episode and names the new serial chain`() {
        val swap = swappedCase()
        val old = swap.case.old.installation
        val rows = read("/api/customers/${old.customer}/assets/workbench/history", old.receipt.stock.token).path("items").asSequence().toList()
        assertThat(rows).hasSize(2)
        val retired = rows.single { it.path("asset").path("id").asString() == old.operation.toString() }
        val replacement = rows.single { it.path("asset").path("id").asString() != old.operation.toString() }
        assertThat(retired.path("asset").path("endedAt").isNull).isFalse()
        assertThat(retired.path("asset").path("positionStatus").asString()).isEqualTo("RETIRED")
        assertThat(replacement.path("asset").path("previousAssignmentId").asString()).isEqualTo(old.operation.toString())
        assertThat(replacement.path("asset").path("serial").asString()).isEqualTo("REPLACEMENT-ONU")
        assertThat(retired.path("episode").path("onuId").asString()).isEqualTo(old.operation.toString())
    }
}
