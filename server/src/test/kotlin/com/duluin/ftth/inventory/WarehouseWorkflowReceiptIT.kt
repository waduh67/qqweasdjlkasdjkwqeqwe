package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseWorkflowReceiptIT : WarehouseReceiptHttpFixture() {
    @Test fun `draining permits receipt completion and rejects a new receipt without stock changes`() {
        val setup = setupReceipt()
        val sku = request("PUT", "/api/v1/warehouse/skus/${setup.cable}", setup.token,
            """{"expectedRevision":0,"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM","inspectionRequired":false}""")
        assertThat(sku.status).isEqualTo(200)
        val lines = """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"DRAIN"}"""
        val id = draft(setup, lines).path("id").asString()
        fixture(setup.token).transaction { context.getBean(InventoryTenantPolicyService::class.java).beginDraining(0) }

        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val details = request("GET", "/api/v1/warehouse/receipts/$id", setup.token)
        assertThat(details.status).isEqualTo(200)
        val received = mapper.readTree(details.contentAsString)
        val line = received.path("lines")[0]
        val stock = line.path("pieces")[0].path("stockIdentityId").asString()
        val finished = transition(setup, id, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}",
            "lines":[{"lineId":"${line.path("id").asString()}","stockIdentityId":"$stock","quantityBase":"1000","baseUnit":"MM"}]}""")
        assertThat(finished.path("state").asString()).isEqualTo("PUTAWAY")
        val before = fixture(setup.token).transaction { counts() }
        val denied = request("POST", "/api/v1/warehouse/receipts", setup.token, draftBody(setup, lines))
        assertThat(denied.status).isEqualTo(409)
        assertThat(mapper.readTree(denied.contentAsString).path("code").asString()).isEqualTo("CUTOVER_REQUIRED")
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
        assertThat(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).status).isEqualTo(200)
    }
}
