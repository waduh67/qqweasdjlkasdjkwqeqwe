package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CustomerWarehouseProvenanceIT : CustomerDeploymentIntegrityFixture() {
    @Test
    fun `legacy ONU remains readable without creating physical authority`() {
        val stock = setupReceipt()
        val customer = customer(stock.token)
        val serial = "LEGACY-${UUID.randomUUID()}"
        val onu = LegacyOnuTestFixture.stage(customer, serial)

        val response = request("GET", "/api/customers/$customer/onus", stock.token)

        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsString).single().path("id").asString()).isEqualTo(onu)
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='$customer'")).isEqualTo("0")
        }
    }

    @Test
    fun `direct raw serial registration rejects missing deployment authorization without writes`() {
        val stock = setupReceipt()
        val customer = customer(stock.token)

        val response = request("POST", "/api/customers/$customer/onus", stock.token,
            """{"serialNumber":"UNISSUED-DEVICE","model":"ONU"}""")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(response.contentAsString).contains("USE_WORKORDER_ASSET_WORKFLOW")
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM onu WHERE customer_id='$customer'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='$customer'")).isEqualTo("0")
        }
    }

    @Test
    fun `acknowledged issued asset installs through direct customer consume route`() {
        val case = receiptCase(serial = true, installation = true)
        received(case)
        val customer = fixture(case.stock.token).transaction {
            scalar("SELECT customer_id FROM work_order WHERE id='${case.workOrder}'")
        }
        val selected = case.input.lines.single()
        val revision = summary(case.stock.token, case.workOrder).path("revisions").path("workOrderRevision").asLong()
        val authorization = request("POST", "/api/work-orders/${case.workOrder}/assets/authorize", case.receiver.first,
            """{"expectedRevision":$revision,"assetId":"${selected.stockIdentityId}","issueLineId":"${selected.issueLineId}","purpose":"INSTALL"}""",
            "installation-authorize")
        assertThat(authorization.status).withFailMessage(authorization.contentAsString).isEqualTo(200)
        val authorizationId = mapper.readTree(authorization.contentAsString).path("authorizationId").asString()

        val response = request("POST", "/api/customers/$customer/assets/install", case.receiver.first,
            """{"authorizationId":"$authorizationId","expectedRevision":0,"topology":null}""", "installation-consume")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization WHERE id='$authorizationId' AND consumed")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${selected.stockIdentityId}' AND customer_id='$customer'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='${selected.stockIdentityId}' AND customer_id='$customer'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${selected.stockIdentityId}' AND status='CUSTOMER_INSTALLED' AND custody_owner_id='$customer' AND quantity_base=1")).isEqualTo("1")
        }
        assertThat(summary(case.stock.token, case.workOrder).path("revisions").path("useRevision").asLong()).isEqualTo(1)
    }

    private fun customer(token: String): String = UUID.randomUUID().also { id ->
        fixture(token).transaction {
            sql("INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$id','$tenant','$id','Installation','Test')")
        }
    }.toString()

    @ParameterizedTest
    @ValueSource(strings = ["REVOKED", "REASSIGNED", "CANCELLED", "REVISION", "CUSTOMER"])
    fun `original JWT cannot consume after authority or work order changes`(change: String) {
        val install = installation()
        val case = install.receipt
        when (change) {
            "REVOKED" -> assertThat(request("PUT", "/api/users/${case.receiver.second}/access", case.stock.token,
                """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
            "REASSIGNED" -> assign(case.stock.token, case.workOrder, technician(case.stock.token).second)
            "CANCELLED" -> assertThat(request("POST", "/api/work-orders/${case.workOrder}/cancel", case.stock.token,
                """{"reason":"Cancelled installation"}""").status).isEqualTo(200)
            "REVISION" -> assertThat(request("POST", "/api/work-orders/${case.workOrder}/start", case.receiver.first).status).isEqualTo(200)
            "CUSTOMER" -> fixture(case.stock.token).transaction {
                sql("UPDATE work_order SET customer_id=NULL WHERE id='${case.workOrder}'")
            }
            else -> error("Unknown change")
        }

        val response = consume(install)

        assertThat(response.status).withFailMessage(response.contentAsString).isIn(403, 409)
        assertUninstalled(install)
    }

    @Test
    fun `same operation replay returns original customer episode without posting again`() {
        val install = installation()
        val first = consume(install)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(201)

        val replay = consume(install)

        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(201)
        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='${install.operation}'")).isEqualTo("1")
        }
    }

    @Test
    fun `generic router orchestration commits assignment without an ONU`() {
        val install = installation(generic = true)

        val episode = authenticated(install) {
            context.getBean(com.duluin.ftth.fulfillment.CustomerAssetWorkflowService::class.java).install(install.customer,
                InstallCustomerAssetRequest(install.authorization, 0, null), com.duluin.ftth.inventory.WarehouseMutationMetadata("generic"))
        }

        assertThat(episode.onuId).isNull()
        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${episode.assignmentId}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE assignment_id='${episode.assignmentId}'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM customer_asset_installation WHERE id='${episode.episodeId}'")).isEqualTo("1")
        }
    }

    @Test
    fun `unknown discovery authorization cannot create customer equipment`() {
        val install = installation()

        org.junit.jupiter.api.assertThrows<com.duluin.ftth.inventory.WarehouseContractException> {
            authenticated(install) {
                context.getBean(CustomerAssetApi::class.java).installDiscoveredAsset(install.customer,
                    InstallCustomerAssetRequest(UUID.randomUUID(), 0, null), com.duluin.ftth.inventory.WarehouseMutationMetadata("discovery"))
            }
        }

        assertUninstalled(install)
    }

    @Test
    fun `direct ONU endpoint derives serial from the consumed physical asset`() {
        val install = installation()

        val response = request("POST", "/api/customers/${install.customer}/onus", install.receipt.receiver.first,
            mapper.writeValueAsString(mapOf("serialNumber" to "CLIENT-NOT-AUTHORITY", "deployment" to
                InstallCustomerAssetRequest(install.authorization, 0, null))), "direct-onu")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        assertThat(mapper.readTree(response.contentAsString).path("serialNumber").asString())
            .isEqualTo(install.receipt.input.lines.single().serial)
    }

    @Test
    fun `public provision caller consumes the same authorization gate`() {
        val install = installation()

        val onu = authenticated(install) {
            context.getBean(CustomerApi::class.java).provisionOnu(ProvisionOnuCommand("NOT-AUTHORITY", null,
                install.customer, null, null, null, AuthorizedOnuInstallation(InstallCustomerAssetRequest(install.authorization, 0, null), "public-provision")))
        }

        assertThat(onu.serialNumber).isEqualTo(install.receipt.input.lines.single().serial)
        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization WHERE id='${install.authorization}' AND consumed")).isEqualTo("1")
        }
    }

    @Test
    fun `foreign customer cannot spend a valid authorization`() {
        val install = installation()
        val other = customer(install.receipt.stock.token)

        val response = request("POST", "/api/customers/$other/assets/install", install.receipt.receiver.first,
            mapper.writeValueAsString(InstallCustomerAssetRequest(install.authorization, 0, null)), "foreign")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertUninstalled(install)
    }
}
