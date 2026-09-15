package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

abstract class CustomerAssetTitleCorrectionCases : CustomerAssetOwnershipIntegrityCases() {
    protected data class CorrectionCase(val ownership: OwnershipCase, val handover: String, val checker: Pair<String, String>, val mode: String)

    protected fun correctionCase(mode: String = "SALE"): CorrectionCase {
        val ownership = ownershipCase(mode)
        val accepted = accept(ownership)
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val handover = mapper.readTree(accepted.contentAsString).path("acceptedHandover").path("handoverId").asString()
        val admin = ownership.installation.receipt.stock.token
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val location = fixture(admin).transaction {
            scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='${ownership.installation.receipt.input.lines.single().stockIdentityId}'")
        }
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/$location", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["$location"],"rules":[{"operation":"TITLE_REACQUISITION","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        return CorrectionCase(ownership, handover, checker, mode)
    }

    protected fun correction(case: CorrectionCase, key: String = "correction") = request("POST",
        "/api/v1/warehouse/asset-title-corrections", case.ownership.installation.receipt.stock.token,
        """{"assignmentId":"${case.ownership.installation.operation}","sourceHandoverId":"${case.handover}","expectedAssignmentRevision":1,"expectedTitleRevision":${if (case.mode == "LOAN") 0 else 1},"targetOwner":"${if (case.mode == "LOAN") "CUSTOMER" else "ISP"}","reason":"Independent title correction","evidenceId":"${case.ownership.signature}"}""", key)

    protected fun ownershipReport(case: OwnershipCase) = request("GET",
        "/api/customers/${case.installation.customer}/assets/ownership", case.installation.receipt.stock.token)

    protected data class PendingCorrection(val case: CorrectionCase, val document: String, val approval: String)

    protected fun pendingCorrection(mode: String = "SALE"): PendingCorrection {
        return submitCorrection(correctionCase(mode))
    }

    protected fun submitCorrection(case: CorrectionCase): PendingCorrection {
        val requested = correction(case)
        assertThat(requested.status).withFailMessage(requested.contentAsString).isEqualTo(201)
        val document = mapper.readTree(requested.contentAsString).path("documentId").asString()
        val submitted = request("POST", "/api/v1/warehouse/approvals/request", case.ownership.installation.receipt.stock.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "approval-request")
        assertThat(submitted.status).withFailMessage(submitted.contentAsString).isEqualTo(201)
        return PendingCorrection(case, document, mapper.readTree(submitted.contentAsString).path("requestId").asString())
    }

    protected fun decideCorrection(pending: PendingCorrection, decision: String = "APPROVE", actor: String = pending.case.checker.first) =
        request("POST", "/api/v1/warehouse/approvals/decide", actor,
            """{"requestId":"${pending.approval}","expectedRevision":0,"decision":"$decision","reason":"Independent decision"}""", "title-decision")

    @Test
    fun `mutable SKU widening cannot authorize sale against a loan-only receipt`() {
        val receipt = receiptCase(serial = true, installation = true, loanOnly = true)
        received(receipt)
        assertThat(request("PUT", "/api/v1/warehouse/skus/${receipt.stock.onu}", receipt.stock.token,
            """{"expectedRevision":1,"code":"ONU","name":"ONU","category":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false,"allowedOwnershipModes":["LOAN","SALE"]}""").status).isEqualTo(200)
        val selected = receipt.input.lines.single()
        val revision = summary(receipt.stock.token, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()

        val authorization = request("POST", "/api/work-orders/${receipt.workOrder}/assets/authorize", receipt.receiver.first,
            """{"expectedRevision":$revision,"assetId":"${selected.stockIdentityId}","issueLineId":"${selected.issueLineId}","purpose":"INSTALL","ownershipMode":"SALE"}""", "frozen-mode")

        assertThat(authorization.status).withFailMessage(authorization.contentAsString).isEqualTo(409)
    }

    @Test
    fun `independent approval appends an installed owner transfer without changing accepted history`() {
        val case = correctionCase()
        val requested = correction(case)
        assertThat(requested.status).withFailMessage(requested.contentAsString).isEqualTo(201)
        val document = mapper.readTree(requested.contentAsString).path("documentId").asString()
        val submitted = request("POST", "/api/v1/warehouse/approvals/request", case.ownership.installation.receipt.stock.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "approval-request")
        assertThat(submitted.status).withFailMessage(submitted.contentAsString).isEqualTo(201)
        val approval = mapper.readTree(submitted.contentAsString).path("requestId").asString()
        assertThat(title(case.ownership)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")

        val decided = request("POST", "/api/v1/warehouse/approvals/decide", case.checker.first,
            """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}""", "approve-correction")

        assertThat(decided.status).withFailMessage(decided.contentAsString).isEqualTo(200)
        assertThat(title(case.ownership)).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|2|1")
        fixture(case.ownership.installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer WHERE assignment_id='${case.ownership.installation.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT snapshot->>'legal_owner' FROM inventory_asset_assignment_history WHERE assignment_id='${case.ownership.installation.operation}' AND revision=1")).isEqualTo("CUSTOMER")
        }
    }

    @Test
    fun `ceased loan reports recovery while retaining ISP title and installed custody`() {
        val case = ownershipCase()
        assertThat(accept(case).status).isEqualTo(200)
        assertThat(request("PUT", "/api/customers/${case.installation.customer}/status", case.installation.receipt.stock.token,
            """{"status":"TERMINATED"}""").status).isEqualTo(200)

        val report = ownershipReport(case)

        assertThat(report.status).withFailMessage(report.contentAsString).isEqualTo(200)
        val item = mapper.readTree(report.contentAsString).single()
        assertThat(item.path("legalOwner").asString()).isEqualTo("ISP")
        assertThat(item.path("recoveryRequired").asBoolean()).isTrue()
        assertThat(item.path("recoveryDue").asBoolean()).isTrue()
    }

    @Test
    fun `current sale ownership report reflects acceptance rather than the installation snapshot`() {
        val case = ownershipCase("SALE")
        assertThat(accept(case).status).isEqualTo(200)

        val report = ownershipReport(case)

        assertThat(report.status).withFailMessage(report.contentAsString).isEqualTo(200)
        val item = mapper.readTree(report.contentAsString).single()
        assertThat(item.path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(item.path("titleRevision").asLong()).isEqualTo(1)
        assertThat(item.path("recoveryRequired").asBoolean()).isFalse()
    }
}
