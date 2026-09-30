package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID
import java.util.stream.Stream

class CustomerDeploymentStrictInputIT : CustomerDeploymentFixture() {
    companion object {
        @JvmStatic fun invalidBodies(): Stream<Arguments> = buildList {
            val invalid = listOf("tenantId", "actorId", "custodianId", "sourceId", "receiptId", "movementId", "approver",
                "DUPLICATE", "FRACTIONAL", "STRING", "NULL", "TRAILING", "MALFORMED", "NESTED")
            for (route in listOf("INSTALL", "ONU", "AUTHORIZE")) for (shape in invalid) add(Arguments.of(route, shape, false))
            for (route in listOf("INSTALL", "ONU")) for (shape in invalid.take(8)) add(Arguments.of(route, shape, true))
        }.stream()
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    fun `strict write boundaries reject invalid shapes before writes or replay disclosure`(route: String, shape: String, replay: Boolean) {
        val install = installation()
        if (replay) assertThat(consume(install).status).isEqualTo(201)
        val installRequest = InstallCustomerAssetRequest(install.authorization, 0, null)
        val base = when (route) {
            "INSTALL" -> mapper.writeValueAsString(installRequest)
            "ONU" -> mapper.writeValueAsString(mapOf("deployment" to installRequest))
            "AUTHORIZE" -> mapper.writeValueAsString(mapOf("expectedRevision" to install.receipt.input.workOrderRevision,
                "assetId" to install.receipt.input.lines.single().stockIdentityId, "issueLineId" to install.receipt.input.lines.single().issueLineId, "purpose" to "INSTALL"))
            else -> error("Unknown route")
        }
        val body = when (shape) {
            "DUPLICATE" -> when (route) {
                "ONU" -> base.dropLast(1) + ",\"deployment\":${mapper.writeValueAsString(installRequest)}}"
                "AUTHORIZE" -> base.dropLast(1) + ",\"assetId\":\"${install.receipt.input.lines.single().stockIdentityId}\"}"
                else -> base.dropLast(1) + ",\"authorizationId\":\"${install.authorization}\"}"
            }
            "FRACTIONAL" -> base.replace(Regex("\"expectedRevision\":[0-9]+"), "\"expectedRevision\":0.5")
            "STRING" -> base.replace(Regex("\"expectedRevision\":[0-9]+"), "\"expectedRevision\":\"0\"")
            "NULL" -> base.replace(Regex("\"expectedRevision\":[0-9]+"), "\"expectedRevision\":null")
            "TRAILING" -> "$base {}"
            "MALFORMED" -> "{"
            "NESTED" -> if (route == "AUTHORIZE") base.dropLast(1) + ",\"authority\":{\"actorId\":\"${UUID.randomUUID()}\"}}"
                else base.replace("\"topology\":null", "\"topology\":{\"odpId\":\"${UUID.randomUUID()}\",\"portNumber\":1,\"installRxPowerDbm\":null,\"actorId\":\"${UUID.randomUUID()}\"}")
            else -> base.dropLast(1) + ",\"$shape\":\"${UUID.randomUUID()}\"}"
        }
        val path = when (route) {
            "INSTALL" -> "/api/customers/${install.customer}/assets/install"
            "ONU" -> "/api/customers/${install.customer}/onus"
            "AUTHORIZE" -> "/api/work-orders/${install.receipt.workOrder}/assets/authorize"
            else -> error("Unknown route")
        }

        val response = request("POST", path, install.receipt.receiver.first, body, if (route == "AUTHORIZE") "authorize" else "consume")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(400)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("MALFORMED_REQUEST")
        if (!replay) assertUninstalled(install) else fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='${install.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM customer_asset_installation WHERE operation_id='${install.operation}'")).isEqualTo("1")
        }
    }
}
