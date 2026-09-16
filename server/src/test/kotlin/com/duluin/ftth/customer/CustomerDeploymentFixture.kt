package com.duluin.ftth.customer

import com.duluin.ftth.fulfillment.MaterialReceiptFixture
import org.assertj.core.api.Assertions.assertThat
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import java.util.UUID

abstract class CustomerDeploymentFixture : MaterialReceiptFixture() {
    protected data class Installation(val receipt: ReceiptCase, val customer: UUID, val authorization: UUID, val operation: UUID)

    protected fun installation(generic: Boolean = false, extraPermissions: Set<String> = emptySet()): Installation {
        val case = receiptCase(serial = true, installation = true, extraPermissions = extraPermissions)
        received(case)
        if (generic) {
            val response = request("PUT", "/api/v1/warehouse/skus/${case.stock.onu}", case.stock.token,
                """{"expectedRevision":1,"code":"ONU","name":"Router","category":"ROUTER","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false}""")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        }
        val customer = fixture(case.stock.token).transaction { UUID.fromString(scalar("SELECT customer_id FROM work_order WHERE id='${case.workOrder}'")) }
        val selected = case.input.lines.single()
        val revision = summary(case.stock.token, case.workOrder).path("revisions").path("workOrderRevision").asLong()
        val response = request("POST", "/api/work-orders/${case.workOrder}/assets/authorize", case.receiver.first,
            """{"expectedRevision":$revision,"assetId":"${selected.stockIdentityId}","issueLineId":"${selected.issueLineId}","purpose":"INSTALL"}""", "authorize")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val body = mapper.readTree(response.contentAsString)
        return Installation(case, customer, UUID.fromString(body.path("authorizationId").asString()), UUID.fromString(body.path("operationId").asString()))
    }

    protected fun consume(installation: Installation, key: String = "consume") = request("POST",
        "/api/customers/${installation.customer}/assets/install", installation.receipt.receiver.first,
        mapper.writeValueAsString(InstallCustomerAssetRequest(installation.authorization, 0, null)), key)

    protected fun assertUninstalled(installation: Installation) = fixture(installation.receipt.stock.token).transaction {
        val asset = installation.receipt.input.lines.single().stockIdentityId
        assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset'")).isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='$asset'")).isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization WHERE id='${installation.authorization}' AND consumed")).isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='${installation.operation}'")).isEqualTo("0")
        assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='$asset' AND status='ISSUED' AND custody_owner_kind='TECHNICIAN' AND quantity_base=1")).isEqualTo("1")
    }

    protected fun <T> authenticated(installation: Installation, action: () -> T): T {
        val stock = fixture(installation.receipt.stock.token)
        val previous = SecurityContextHolder.getContext()
        val security = SecurityContextHolder.createEmptyContext()
        security.authentication = JwtAuthenticationConverter().convert(context.getBean(JwtDecoder::class.java).decode(installation.receipt.receiver.first))
        SecurityContextHolder.setContext(security)
        return try { stock.transaction { action() } }
        finally { SecurityContextHolder.setContext(previous) }
    }
}
