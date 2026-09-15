package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class CustomerDeploymentRootInputIT : CustomerDeploymentFixture() {
    companion object {
        @JvmStatic fun nullRoots(): Stream<Arguments> = buildList {
            for (route in listOf("INSTALL", "ONU", "AUTHORIZE")) for (replay in listOf(false, true)) add(Arguments.of(route, replay))
        }.stream()

        @JvmStatic fun otherRoots(): Stream<Arguments> = buildList {
            for (route in listOf("INSTALL", "ONU", "AUTHORIZE")) for (body in listOf("1", "\"text\"", "true", "[]"))
                for (replay in listOf(false, true)) add(Arguments.of(route, body, replay))
        }.stream()
    }

    @ParameterizedTest
    @MethodSource("nullRoots")
    fun `null root returns structured400 without writes or replay disclosure`(route: String, replay: Boolean) {
        rejectedRoot(route, "null", replay)
    }

    @ParameterizedTest
    @MethodSource("otherRoots")
    fun `non-object roots stay rejected before writes or replay disclosure`(route: String, body: String, replay: Boolean) {
        rejectedRoot(route, body, replay)
    }

    private fun rejectedRoot(route: String, body: String, replay: Boolean) {
        val install = installation()
        if (replay) assertThat(consume(install).status).isEqualTo(201)
        val installBody = mapper.writeValueAsString(InstallCustomerAssetRequest(install.authorization, 0, null))
        val validBody = when (route) {
            "INSTALL" -> installBody
            "ONU" -> """{"deployment":$installBody}"""
            "AUTHORIZE" -> mapper.writeValueAsString(mapOf("expectedRevision" to install.receipt.input.workOrderRevision,
                "assetId" to install.receipt.input.lines.single().stockIdentityId, "issueLineId" to install.receipt.input.lines.single().issueLineId,
                "purpose" to "INSTALL"))
            else -> error("Unknown route")
        }
        val path = when (route) {
            "INSTALL" -> "/api/customers/${install.customer}/assets/install"
            "ONU" -> "/api/customers/${install.customer}/onus"
            "AUTHORIZE" -> "/api/work-orders/${install.receipt.workOrder}/assets/authorize"
            else -> error("Unknown route")
        }
        val key = if (!replay) "root-input-fresh" else if (route == "AUTHORIZE") "authorize" else "consume"
        val original = if (replay) request("POST", path, install.receipt.receiver.first, validBody, key).also {
            assertThat(it.status).withFailMessage(it.contentAsString).isIn(200, 201)
        }.contentAsString else null
        val before = fingerprint(install)

        val response = request("POST", path, install.receipt.receiver.first, body, key)

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(400)
        val failure = mapper.readTree(response.contentAsString)
        assertThat(failure.path("code").asString()).isEqualTo("MALFORMED_REQUEST")
        assertThat(failure.has("episodeId") || failure.has("authorizationId") || failure.has("operationId")).isFalse()
        assertThat(fingerprint(install)).isEqualTo(before)
        if (replay) assertThat(request("POST", path, install.receipt.receiver.first, validBody, key).contentAsString).isEqualTo(original)
        else assertUninstalled(install)
    }

    private fun fingerprint(install: Installation): String = fixture(install.receipt.stock.token).transaction {
        scalar("""SELECT concat_ws('|',
            (SELECT count(*) FROM inventory_operation),(SELECT md5(string_agg(id::text||original_body,'|' ORDER BY id)) FROM inventory_operation),
            (SELECT count(*) FROM inventory_deployment_authorization),(SELECT count(*) FROM inventory_deployment_authorization WHERE consumed),
            (SELECT count(*) FROM inventory_deployment_result),(SELECT count(*) FROM inventory_asset_assignment),
            (SELECT count(*) FROM customer_asset_installation),(SELECT count(*) FROM onu),(SELECT count(*) FROM inventory_movement))""")
    }
}
