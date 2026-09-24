package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MyMaterialsIT : MaterialLifecycleFixture() {
    private val root = "/api/v1/warehouse/my-materials"

    @Test fun `own pending issues expose actual revisions and receipt identities across partial acknowledgement`() {
        val case = receiptCase()
        val actor = case.receiver.first
        val path = "$root/${case.workOrder}"
        val before = accounting(case)
        val jobs = request("GET", "$root?size=1", actor)
        assertThat(jobs.status).withFailMessage(jobs.contentAsString).isEqualTo(200)
        assertThat(jobs.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(mapper.readTree(jobs.contentAsString).path("items").single().path("id").asString()).isEqualTo(case.workOrder)
        val pending = request("GET", "$path/issues", actor)
        assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(pending.contentAsString).path("items").single()
        assertThat(issue.path("revision").asLong()).isEqualTo(case.input.expectedRevision)
        assertThat(issue.path("workOrderRevision").asLong()).isEqualTo(case.input.workOrderRevision)
        assertThat(issue.path("receiver").path("id").asString()).isEqualTo(case.receiver.second)
        assertThat(issue.path("sender").path("name").asString()).isNotBlank()
        assertThat(issue.path("lines").single().path("stockIdentityId").asString()).isEqualTo(case.input.lines.single().stockIdentityId.toString())
        assertThat(issue.path("lines").single().path("remainingBase").asString()).isEqualTo("100000")
        assertThat(mapper.readTree(request("GET", "$path/custody", actor).contentAsString).path("items").isEmpty).isTrue()
        assertThat(accounting(case)).isEqualTo(before)
        received(case)
        val partial = mapper.readTree(request("GET", "$path/issues", actor).contentAsString).path("items").single()
        assertThat(partial.path("state").asString()).isEqualTo("PART_RECEIVED")
        assertThat(partial.path("lines").single().path("acceptedBase").asString()).isEqualTo("60000")
        assertThat(partial.path("lines").single().path("remainingBase").asString()).isEqualTo("40000")
        val finish = case.input.copy(expectedRevision = partial.path("revision").asLong(), lines = case.input.lines.map { it.copy(acceptedBase = "40000", missingBase = "0") })
        val accepted = acknowledge(case, finish, "finish-own-receipt")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val finalAccounting = accounting(case)
        assertThat(acknowledge(case, finish, "finish-own-receipt").contentAsString).isEqualTo(accepted.contentAsString)
        val finalIssue = mapper.readTree(request("GET", "$path/issues", actor).contentAsString).path("items").single()
        assertThat(finalIssue.path("lines").single().path("remainingBase").asString()).isEqualTo("0")
        val custody = request("GET", "$path/custody", actor)
        assertThat(mapper.readTree(custody.contentAsString).path("items").sumOf { it.path("quantityBase").asString().toLong() }).isEqualTo(100000)
        assertThat(custody.contentAsString + pending.contentAsString).doesNotContain("cost", "payloadHash", "email", "custodianId", "sourceRevision")
        assertThat(accounting(case)).isEqualTo(finalAccounting)
    }

    @Test fun `former assignee reads own remainder and returns it with current revision without new use authority`() {
        val case = residualCase()
        val receipt = case.usage.receipt
        val actor = receipt.receiver.first
        val path = "$root/${receipt.workOrder}"
        val context = request("GET", path, actor)
        assertThat(context.status).withFailMessage(context.contentAsString).isEqualTo(200)
        val current = mapper.readTree(context.contentAsString)
        assertThat(current.path("currentAssignee").asBoolean()).isFalse()
        assertThat(current.path("field").isNull).isTrue()
        assertThat(current.path("workOrderRevision").asLong()).isEqualTo(case.input.workOrderRevision)
        val own = request("GET", "$path/custody", actor)
        assertThat(own.status).withFailMessage(own.contentAsString).isEqualTo(200)
        val source = mapper.readTree(own.contentAsString).path("items").single()
        assertThat(source.path("quantityBase").asString()).isEqualTo("17500")
        assertThat(source.path("id").asString()).isEqualTo(case.input.stockIdentityId.toString())
        assertThat(source.path("sourceUsageId").asString()).isEqualTo(case.input.usageId.toString())
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/materials/report-use", actor,
            mapper.writeValueAsString(case.usage.input.copy(workOrderRevision = case.input.workOrderRevision)), "forbidden-new-use").status).isEqualTo(403)
        assertThat(mapper.readTree(request("GET", "$path/return-locations", actor).contentAsString).path("items").isEmpty).isTrue()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${case.input.targetLocationId}", receipt.stock.token,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val locations = request("GET", "$path/return-locations", actor)
        assertThat(locations.status).withFailMessage(locations.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(locations.contentAsString).path("items").single().path("name").asString()).isEqualTo("Return intake")
        val id = dispatchedResidual(case)
        val history = request("GET", "$path/residuals", actor)
        assertThat(history.status).withFailMessage(history.contentAsString).isEqualTo(200)
        val residual = mapper.readTree(history.contentAsString).path("items").single()
        assertThat(residual.path("id").asString()).isEqualTo(id)
        assertThat(residual.path("revision").asLong()).isEqualTo(1)
        assertThat(residual.path("sku").path("name").asString()).isEqualTo("Cable")
        assertThat(residual.path("sender").path("name").asString()).isNotBlank()
        assertThat(mapper.readTree(request("GET", "$path/custody", actor).contentAsString).path("items").isEmpty).isTrue()
        assertThat(acknowledgeResidual(case, id).status).isEqualTo(200)
        val after = mapper.readTree(request("GET", "$path/residuals", actor).contentAsString).path("items").single()
        assertThat(after.path("state").asString()).isEqualTo("RECEIVED_IN_INSPECTION")
        assertThat(after.path("revision").asLong()).isEqualTo(2)
    }

    @Test fun `own material filters reject other actors and tenants and apply current location revocation before paging`() {
        val case = receiptCase(serial = true)
        val actor = case.receiver.first
        val outsider = technician(case.stock.token)
        val path = "$root/${case.workOrder}"
        assertThat(mapper.readTree(request("GET", root, outsider.first).contentAsString).path("totalElements").asLong()).isZero()
        assertThat(mapper.readTree(request("GET", root, case.stock.token).contentAsString).path("totalElements").asLong()).isZero()
        for (suffix in listOf("", "/custody", "/issues", "/residuals", "/return-locations")) {
            assertThat(request("GET", path + suffix, outsider.first).status).isEqualTo(404)
            assertThat(request("GET", path + suffix, tenant()).status).isEqualTo(404)
        }
        received(case)
        val own = request("GET", "$path/custody", actor)
        assertThat(own.status).withFailMessage(own.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(own.contentAsString).path("items").single().path("serial").asString()).isEqualTo("RECEIVE-1")
        for (location in listOf(case.field, case.transit, case.stock.bin)) assertThat(request("PUT",
            "/api/v1/warehouse/settings/scopes/${case.receiver.second}/$location", case.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        for (resource in listOf(root, "$path/custody", "$path/issues", "$path/residuals")) {
            val empty = request("GET", "$resource?size=1", actor)
            assertThat(empty.status).withFailMessage(empty.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(empty.contentAsString).path("totalElements").asLong()).isZero()
        }
        for (suffix in listOf("?page=0&page=1", "?size=101", "?actorId=${case.receiver.second}", "?page=-1"))
            assertThat(request("GET", "$root$suffix", actor).status).isEqualTo(400)
    }
}
