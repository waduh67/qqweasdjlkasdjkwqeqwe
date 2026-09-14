package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class CustomerDeploymentForwardFixIT : CustomerDeploymentGraphFixture() {
    @Test
    fun `T20-01 deployment rejects an additional verified customer material fact`() {
        val install = installation()
        assertThat(consume(install).status).isEqualTo(201)

        assertThrows<Exception> { fixture(install.receipt.stock.token).transaction { sql(extraFact(install)) } }

        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
        }
    }

    @Test
    fun `T20-02 posted deployment document requires its complete owner graph`() {
        val install = installation()
        assertThat(consume(install).status).isEqualTo(201)
        val orphan = UUID.randomUUID()

        assertThrows<Exception> { fixture(install.receipt.stock.token).transaction {
            clonedDocument(install, orphan).forEach(::sql)
            sql("UPDATE inventory_document SET state='POSTED',revision=1 WHERE id='$orphan'")
        } }

        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE id='$orphan'")).isEqualTo("0")
        }
    }

    @Test
    fun `T20-03 customer install rejects client authority and source fields before writes`() {
        val install = installation()
        val body = mapper.writeValueAsString(InstallCustomerAssetRequest(install.authorization, 0, null)).dropLast(1) +
            """, "tenantId":"${UUID.randomUUID()}","actorId":"${UUID.randomUUID()}","custodianId":"${UUID.randomUUID()}","sourceId":"${UUID.randomUUID()}","receiptId":"${UUID.randomUUID()}"}"""

        val response = request("POST", "/api/customers/${install.customer}/assets/install", install.receipt.receiver.first, body, "strict-input")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(400)
        assertUninstalled(install)
    }
}
