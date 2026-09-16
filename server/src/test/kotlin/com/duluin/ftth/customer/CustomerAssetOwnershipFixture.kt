package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.Base64
import java.util.UUID

abstract class CustomerAssetOwnershipFixture : CustomerDeploymentFixture() {
    protected data class OwnershipCase(val installation: Installation, val signature: UUID)

    protected fun ownershipCase(mode: String? = null, serials: List<String> = listOf("RECEIVE-1", "RECEIVE-2")): OwnershipCase {
        val receipt = receiptCase(serial = true, installation = true, serials = serials)
        received(receipt)
        val start = request("POST", "/api/work-orders/${receipt.workOrder}/start", receipt.receiver.first)
        assertThat(start.status).withFailMessage(start.contentAsString).isEqualTo(200)
        val customer = fixture(receipt.stock.token).transaction {
            UUID.fromString(scalar("SELECT customer_id FROM work_order WHERE id='${receipt.workOrder}'"))
        }
        val selected = receipt.input.lines.single()
        val revision = summary(receipt.stock.token, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val intent = mode?.let { ",\"ownershipMode\":\"$it\"" }.orEmpty()
        val authorization = request("POST", "/api/work-orders/${receipt.workOrder}/assets/authorize", receipt.receiver.first,
            """{"expectedRevision":$revision,"assetId":"${selected.stockIdentityId}","issueLineId":"${selected.issueLineId}","purpose":"INSTALL"$intent}""", "ownership-authorize")
        assertThat(authorization.status).withFailMessage(authorization.contentAsString).isEqualTo(200)
        val body = mapper.readTree(authorization.contentAsString)
        val installation = Installation(receipt, customer, UUID.fromString(body.path("authorizationId").asString()),
            UUID.fromString(body.path("operationId").asString()))
        val installed = consume(installation)
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=")
        val signature = mvc.perform(multipart(HttpMethod.PUT, "/api/work-orders/${receipt.workOrder}/signature")
            .file(MockMultipartFile("file", "acceptance.png", "image/png", png))
            .param("signerName", "Installation")
            .header("Authorization", "Bearer ${receipt.receiver.first}")).andReturn().response
        assertThat(signature.status).withFailMessage(signature.contentAsString).isEqualTo(200)
        return OwnershipCase(installation, UUID.fromString(mapper.readTree(signature.contentAsString).path("revisionId").asString()))
    }

    protected fun accept(case: OwnershipCase, key: String = "accept-title") = request("POST",
        "/api/customers/${case.installation.customer}/assets/handover", case.installation.receipt.receiver.first,
        """{"assignmentId":"${case.installation.operation}","expectedRevision":0,"expectedTitleRevision":0,"evidenceId":"${case.signature}"}""", key)

    protected fun title(case: OwnershipCase): String = fixture(case.installation.receipt.stock.token).transaction {
        scalar("""SELECT concat_ws('|',assignment.ownership_mode,assignment.legal_owner,asset.legal_owner,
            asset.status,assignment.revision,(SELECT count(*) FROM inventory_asset_handover WHERE assignment_id=assignment.id))
            FROM inventory_asset_assignment assignment JOIN inventory_serialized_asset asset ON asset.id=assignment.asset_id
            WHERE assignment.id='${case.installation.operation}'""")
    }
}
