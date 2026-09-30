package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaterialWorkbenchIT : MaterialUsageFixture() {
    @Test fun `field workbench follows real acknowledged cable into immutable use and current remainder`() {
        val case = usageCase()
        val root = "/api/work-orders/${case.receipt.workOrder}/materials/workbench"
        val actor = case.receipt.receiver.first
        val basis = request("GET", root, actor)
        assertThat(basis.status).withFailMessage(basis.contentAsString).isEqualTo(200)
        assertThat(basis.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(mapper.readTree(basis.contentAsString).path("useRevision").asLong()).isZero()
        assertThat(mapper.readTree(basis.contentAsString).path("plan").path("lines").single().path("sku").path("name").asString()).isEqualTo("Cable")
        val initial = request("GET", "$root/custody?size=1", actor)
        assertThat(initial.status).withFailMessage(initial.contentAsString).isEqualTo(200)
        val choice = mapper.readTree(initial.contentAsString).path("items").single()
        assertThat(choice.path("id").asString()).isEqualTo(case.input.lines.single().stockIdentityId.toString())
        assertThat(choice.path("receiptId").asString()).isEqualTo(case.input.lines.single().receiptId.toString())
        assertThat(choice.path("quantityBase").asString()).isEqualTo("100000")
        assertThat(choice.path("initialUseSource").asBoolean()).isTrue()
        val accountingBeforeReads = usageAccounting(case)
        assertThat(mapper.readTree(request("GET", "$root/usage", actor).contentAsString).path("totalElements").asLong()).isZero()
        assertThat(mapper.readTree(request("GET", "$root/custody?size=1&page=1", actor).contentAsString).path("items").size()).isZero()
        assertThat(usageAccounting(case)).isEqualTo(accountingBeforeReads)

        val original = mapper.readTree(used(case))
        val after = request("GET", "$root/custody", actor)
        assertThat(after.status).withFailMessage(after.contentAsString).isEqualTo(200)
        val remainder = mapper.readTree(after.contentAsString).path("items").single()
        assertThat(remainder.path("quantityBase").asString()).isEqualTo("17500")
        assertThat(remainder.path("id").asString()).isEqualTo(original.path("lines").single().path("remainder").path("stockIdentityId").asString())
        assertThat(remainder.path("sourceUsageId").asString()).isEqualTo(original.path("usageId").asString())
        assertThat(remainder.path("initialUseSource").asBoolean()).isFalse()
        val history = request("GET", "$root/usage", actor)
        assertThat(history.status).withFailMessage(history.contentAsString).isEqualTo(200)
        val recorded = mapper.readTree(history.contentAsString).path("items").single()
        assertThat(recorded.path("lines").single().path("quantityBase").asString()).isEqualTo("82500")
        assertThat(recorded.path("lines").single().path("sku").path("name").asString()).isEqualTo("Cable")
        assertThat(recorded.path("actor").path("name").asString()).isNotBlank()
        assertThat(history.contentAsString + after.contentAsString + basis.contentAsString).doesNotContain("cost", "payloadHash", "sourceRevision", "email", "custodianId")
        val current = mapper.readTree(request("GET", root, actor).contentAsString)
        assertThat(current.path("useRevision").asLong()).isEqualTo(1)
        assertThat(current.path("latestUsageId").asString()).isEqualTo(original.path("usageId").asString())
        assertThat(current.has("latestUsageLines")).isFalse()
    }

    @Test fun `workbench current actor and warehouse fences apply before pages and hide foreign custody`() {
        val case = usageCase()
        used(case)
        val root = "/api/work-orders/${case.receipt.workOrder}/materials/workbench"
        val admin = case.receipt.stock.token
        val outsider = technician(admin)
        assertThat(request("GET", "$root/custody", outsider.first).status).isEqualTo(403)
        assertThat(request("GET", "$root/usage", outsider.first).status).isEqualTo(403)
        val ownAdmin = request("GET", "$root/custody", admin)
        assertThat(ownAdmin.status).withFailMessage(ownAdmin.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(ownAdmin.contentAsString).path("totalElements").asLong()).isZero()
        val adminHistory = request("GET", "$root/usage", admin)
        assertThat(adminHistory.status).withFailMessage(adminHistory.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(adminHistory.contentAsString).path("totalElements").asLong()).isEqualTo(1)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receipt.receiver.second}/${case.receipt.field}", admin,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        for (resource in listOf("custody", "usage")) {
            val denied = request("GET", "$root/$resource?size=1", case.receipt.receiver.first)
            assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(denied.contentAsString).path("totalElements").asLong()).isZero()
        }
        for (suffix in listOf("?page=0&page=1", "?size=101", "?actorId=${case.receipt.receiver.second}", "?page=-1"))
            assertThat(request("GET", "$root/custody$suffix", case.receipt.receiver.first).status).isEqualTo(400)
        assertThat(request("GET", root, tenant()).status).isEqualTo(404)
    }
}
