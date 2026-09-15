package com.duluin.ftth.customer

import com.duluin.ftth.inventory.MaterialReceiptRequest
import com.duluin.ftth.inventory.MaterialReceiptSelection
import com.duluin.ftth.inventory.WarehouseBaseUnit
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.Base64
import java.util.UUID

abstract class CustomerAssetReplacementFixture : CustomerAssetOwnershipFixture() {
    protected data class ReplacementCase(val old: OwnershipCase, val replacement: ReceiptCase, val evidence: UUID)
    protected data class CompletedSwap(val case: ReplacementCase, val operation: UUID, val body: String, val request: String)

    protected fun swappedCase(): CompletedSwap {
        val case = replacementCase()
        val authorized = authorizeReplacement(case)
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()
        val body = """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,
            "expectedTitleRevision":0,"evidenceId":"${case.evidence}","topology":null}"""
        val swapped = request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", case.replacement.receiver.first, body, "swap")
        assertThat(swapped.status).withFailMessage(swapped.contentAsString).isEqualTo(201)
        return CompletedSwap(case, UUID.fromString(mapper.readTree(swapped.contentAsString).path("operationId").asString()), swapped.contentAsString, body)
    }

    protected fun replacementCase(acknowledged: Boolean = true, oldOwnership: String = "LOAN"): ReplacementCase {
        val old = ownershipCase(oldOwnership)
        assertThat(accept(old).status).isEqualTo(200)
        val original = old.installation.receipt
        val stock = original.stock
        val receiptId = draft(stock,
            """{"skuId":"${stock.onu}","quantityBase":"1","serials":[{"serial":"REPLACEMENT-ONU"}]}""").path("id").asString()
        transition(stock, receiptId, "receive", """{"expectedRevision":0}""")
        val received = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", stock.token).contentAsString).path("lines")[0]
        transition(stock, receiptId, "putaway", """{"expectedRevision":1,"destinationLocationId":"${stock.bin}","lines":[{
            "lineId":"${received.path("id").asString()}","stockIdentityId":"${received.path("pieces")[0].path("stockIdentityId").asString()}",
            "quantityBase":"1","baseUnit":"EA"}]}""")
        val order = workOrder(stock.token, "MIGRATION", old.installation.customer.toString())
        assign(stock.token, order, original.receiver.second)
        putPlan(stock.token, order, plan(stock.token, order, "[${line(stock.onu, "1", "EA")} ]"))
        action(stock.token, order, "submit-request", command(stock.token, order, 1))
        action(stock.token, order, "reserve", command(stock.token, order, 1))
        val setup = IssueSetup(stock, order, original.receiver.second)
        val picked = action(stock.token, order, "pick", pickBody(setup))
        val issue = action(stock.token, order, "dispatch", transitionBody(setup, picked))
        val issued = issue.path("lines")[0]
        val input = MaterialReceiptRequest(UUID.fromString(issue.path("issueId").asString()), issue.path("revision").asLong(),
            issue.path("workOrderRevision").asLong(), "replacement-delivery", listOf(MaterialReceiptSelection(
                UUID.fromString(issued.path("id").asString()), UUID.fromString(issued.path("dimension").path("stockIdentityId").asString()),
                WarehouseBaseUnit.EA, "1", serial = issued.path("serial").asString())))
        val replacement = ReceiptCase(stock, order, original.receiver, original.transit, original.field, input)
        if (acknowledged) {
            val acknowledgedReceipt = acknowledge(replacement, key = "replacement-acknowledgement")
            assertThat(acknowledgedReceipt.status).withFailMessage(acknowledgedReceipt.contentAsString).isEqualTo(200)
        }
        return ReplacementCase(old, replacement, removalEvidence(original.receiver.first, order))
    }

    protected fun removalEvidence(token: String, workOrder: String): UUID {
        val start = request("POST", "/api/work-orders/$workOrder/start", token)
        assertThat(start.status).withFailMessage(start.contentAsString).isEqualTo(200)
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=")
        val signed = mvc.perform(multipart(HttpMethod.PUT, "/api/work-orders/$workOrder/signature")
            .file(MockMultipartFile("file", "removal.png", "image/png", png))
            .param("signerName", "Physical removal witnessed")
            .header("Authorization", "Bearer $token")).andReturn().response
        assertThat(signed.status).withFailMessage(signed.contentAsString).isEqualTo(200)
        return UUID.fromString(mapper.readTree(signed.contentAsString).path("revisionId").asString())
    }

    protected fun topology(case: OwnershipCase): UUID {
        val odp = request("POST", "/api/odps", case.installation.receipt.stock.token,
            """{"code":"ODP-${UUID.randomUUID()}","name":"Replacement topology","location":{"longitude":106.995,"latitude":-6.245},"splitterRatio":"1:8","capacity":8}""")
        assertThat(odp.status).withFailMessage(odp.contentAsString).isEqualTo(201)
        return UUID.fromString(mapper.readTree(odp.contentAsString).path("id").asString())
    }

    protected fun authorizeReplacement(case: ReplacementCase, assignment: UUID = case.old.installation.operation, key: String = "replacement-authorize") = request("POST",
        "/api/work-orders/${case.replacement.workOrder}/assets/authorize", case.replacement.receiver.first,
        """{"expectedRevision":${summary(case.replacement.stock.token, case.replacement.workOrder).path("revisions").path("workOrderRevision").asLong()},
            "assetId":"${case.replacement.input.lines.single().stockIdentityId}","issueLineId":"${case.replacement.input.lines.single().issueLineId}",
            "purpose":"REPLACE","previousAssignmentId":"$assignment"}""", key)

    protected fun physicalFingerprint(case: OwnershipCase): String = fixture(case.installation.receipt.stock.token).transaction {
        scalar("""SELECT concat_ws('|',
            (SELECT count(*) FROM inventory_document),(SELECT count(*) FROM inventory_material_receipt),
            (SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg),
            (SELECT count(*) FROM inventory_asset_assignment),(SELECT count(*) FROM inventory_deployment_authorization),
            (SELECT count(*) FROM inventory_asset_assignment_history),
            (SELECT string_agg(concat_ws(':',id,status,legal_owner,revision),',' ORDER BY id) FROM inventory_serialized_asset))""")
    }
}
