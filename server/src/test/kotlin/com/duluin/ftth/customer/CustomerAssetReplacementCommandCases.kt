package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class CustomerAssetReplacementCommandCases : CustomerAssetReplacementIntegrityCases() {
    @Test
    fun `removed recovery scope prevents private swap replay with the original JWT`() {
        val swap = swappedCase()
        val receipt = swap.case.replacement
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${receipt.transit}", receipt.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)

        val replay = request("POST", "/api/customers/${swap.case.old.installation.customer}/assets/replace", receipt.receiver.first, swap.request, "swap")

        assertThat(replay.status).isEqualTo(404)
        assertThat(mapper.readTree(replay.contentAsString).path("code").asString()).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `unused competing authorization cannot prevent physical recovery or be consumed afterward`() {
        val case = replacementCase()
        val first = authorizeReplacement(case)
        val second = authorizeReplacement(case, key = "second-authorization")
        assertThat(first.status).isEqualTo(200)
        assertThat(second.status).isEqualTo(200)
        val firstId = mapper.readTree(first.contentAsString).path("authorizationId").asString()
        val secondId = mapper.readTree(second.contentAsString).path("authorizationId").asString()
        val body = """{"authorizationId":"$firstId","expectedRevision":0,"expectedAssignmentRevision":1,"expectedTitleRevision":0,
            "evidenceId":"${case.evidence}","topology":null}"""

        val swapped = request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", case.replacement.receiver.first, body, "first-swap")

        assertThat(swapped.status).withFailMessage(swapped.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", case.replacement.receiver.first,
            body.replace(firstId, secondId), "second-swap").status).isEqualTo(409)
    }

    @Test
    fun `sold old asset retains CUSTOMER title while its loan replacement starts ISP owned`() {
        val case = replacementCase(oldOwnership = "SALE")
        val authorized = authorizeReplacement(case)
        assertThat(authorized.status).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()

        val swapped = request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", case.replacement.receiver.first,
            """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,"expectedTitleRevision":1,
                "evidenceId":"${case.evidence}","topology":null}""", "sold-swap")

        assertThat(swapped.status).withFailMessage(swapped.contentAsString).isEqualTo(201)
        fixture(case.replacement.stock.token).transaction {
            assertThat(scalar("SELECT status||'|'||legal_owner FROM inventory_serialized_asset WHERE id='${case.old.installation.receipt.input.lines.single().stockIdentityId}'"))
                .isEqualTo("QUARANTINE|CUSTOMER")
            assertThat(scalar("SELECT ownership_mode||'|'||legal_owner FROM inventory_asset_assignment WHERE asset_id='${case.replacement.input.lines.single().stockIdentityId}'"))
                .isEqualTo("LOAN|ISP")
            assertThat(scalar("SELECT recovery_state||'|'||recovery_required FROM inventory_asset_recovery_state WHERE assignment_id='${case.old.installation.operation}'"))
                .isEqualTo("IN_RECOVERY|false")
        }
    }

    @Test
    fun `authorized topology relocation returns exact replay without inventory delta`() {
        val case = replacementCase()
        val odp = topology(case.old)
        val path = "/api/customers/${case.old.installation.customer}/assets/${case.old.installation.operation}/relocate"
        val revision = summary(case.replacement.stock.token, case.replacement.workOrder).path("revisions").path("workOrderRevision").asLong()
        val body = """{"workOrderId":"${case.replacement.workOrder}","expectedWorkOrderRevision":$revision,"expectedRevision":0,
            "topology":{"odpId":"$odp","portNumber":1,"installRxPowerDbm":null}}"""
        val before = physicalFingerprint(case.old)

        val moved = request("POST", path, case.replacement.receiver.first, body, "relocate")

        assertThat(moved.status).withFailMessage(moved.contentAsString).isEqualTo(200)
        assertThat(physicalFingerprint(case.old)).isEqualTo(before)
        val replay = request("POST", path, case.replacement.receiver.first, body, "relocate")
        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(moved.contentAsString)
        assertThat(request("POST", path, case.replacement.receiver.first, body, "new-relocate").status).isEqualTo(409)
        assertThat(physicalFingerprint(case.old)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tenantId", "actorId", "customerId", "legalOwner", "oldAssetId", "newAssetId", "removedAt"])
    fun `swap rejects authority fields before disclosing a stored outcome`(field: String) {
        val swap = swappedCase()
        val body = swap.request.dropLast(1) + ",\"$field\":\"forbidden\"}"
        val before = physicalFingerprint(swap.case.old)

        val denied = request("POST", "/api/customers/${swap.case.old.installation.customer}/assets/replace", swap.case.replacement.receiver.first, body, "swap")

        assertThat(denied.status).isEqualTo(400)
        assertThat(physicalFingerprint(swap.case.old)).isEqualTo(before)
    }

    @Test
    fun `competing swap and dismantle record one physical removal`() {
        val case = replacementCase()
        val receipt = case.replacement
        val authorized = authorizeReplacement(case)
        assertThat(authorized.status).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()
        val dismantle = workOrder(receipt.stock.token, "DISMANTLE", case.old.installation.customer.toString())
        assign(receipt.stock.token, dismantle, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, dismantle)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val swap = pool.submit<Int> {
                ready.countDown(); check(start.await(15, TimeUnit.SECONDS))
                request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", receipt.receiver.first,
                    """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,"expectedTitleRevision":0,
                        "evidenceId":"${case.evidence}","topology":null}""", "race-swap").status
            }
            val remove = pool.submit<Int> {
                ready.countDown(); check(start.await(15, TimeUnit.SECONDS))
                request("POST", "/api/customers/${case.old.installation.customer}/assets/remove", receipt.receiver.first,
                    """{"assignmentId":"${case.old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,
                        "workOrderId":"$dismantle","evidenceId":"$evidence"}""", "race-remove").status
            }
            check(ready.await(15, TimeUnit.SECONDS))

            start.countDown()
            val results = listOf(swap.get(45, TimeUnit.SECONDS), remove.get(45, TimeUnit.SECONDS))

            assertThat(results.count { it in 200..201 }).isEqualTo(1)
            assertThat(results.count { it == 409 }).isEqualTo(1)
            fixture(receipt.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_asset_removal WHERE assignment_id='${case.old.installation.operation}'")).isEqualTo("1")
                assertThat(scalar("SELECT status FROM inventory_serialized_asset WHERE id='${case.old.installation.receipt.input.lines.single().stockIdentityId}'")).isEqualTo("QUARANTINE")
            }
        } finally { pool.shutdownNow() }
    }
}
