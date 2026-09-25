package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

class WarehouseTransferDraftUpgradeIT {
    @Test fun `upgrade retains old draft and dispatched commands and permits a revision only for the old draft`() {
        WarehouseSchemaDatabase("178.9").use { database ->
            postingContext(database, "178.9").use { context ->
                UpgradeClient(context).verify(database)
            }
        }
    }

    private class UpgradeClient(application: ConfigurableApplicationContext) : WarehouseTransferFixture() {
        init {
            context = application
            mvc = MockMvcBuilders.webAppContextSetup(application as WebApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity()).build()
            ReflectionTestUtils.setField(this, "onboarding", application.getBean(OnboardTenantUseCase::class.java))
        }

        fun verify(database: WarehouseSchemaDatabase) {
            val stock = transferStock()
            val admin = stock.setup.token
            val original = request("POST", "/api/v1/warehouse/transfers", admin, transferBody(stock), "pre-upgrade")
            assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
            val id = mapper.readTree(original.contentAsString).path("id").asString()
            val shippedStock = transferStock()
            val shippedId = transfer(shippedStock).path("id").asString()
            val shipped = transferAction(shippedStock, shippedId, "dispatch", """{"expectedRevision":0}""", "pre-upgrade-dispatch")
            val before = durableFacts(admin, id)
            val postedBefore = durableFacts(shippedStock.setup.token, shippedId)
            assertThat(database.migrate().migrations.map { it.version }).contains("178.10")
            assertThat(durableFacts(admin, id)).isEqualTo(before)
            assertThat(durableFacts(shippedStock.setup.token, shippedId)).isEqualTo(postedBefore)
            assertThat(request("POST", "/api/v1/warehouse/transfers", admin, transferBody(stock), "pre-upgrade").contentAsString)
                .isEqualTo(original.contentAsString)
            assertThat(transferAction(shippedStock, shippedId, "dispatch", """{"expectedRevision":0}""", "pre-upgrade-dispatch"))
                .isEqualTo(shipped)
            val updated = request("PUT", "/api/v1/warehouse/transfers/$id", admin,
                """{"expectedRevision":0,"draft":${transferBody(stock).replace("100000", "60000")}}""")
            assertThat(updated.status).withFailMessage(updated.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(updated.contentAsString).path("revision").asLong()).isEqualTo(1)
            balances(stock, "100000", "0", "0")
            transferAction(stock, id, "dispatch", """{"expectedRevision":1}""")
            balances(stock, "40000", "60000", "0")
            assertThat(request("PUT", "/api/v1/warehouse/transfers/$shippedId", shippedStock.setup.token,
                """{"expectedRevision":1,"draft":${transferBody(shippedStock)}}""").status).isEqualTo(409)
            assertThat(durableFacts(shippedStock.setup.token, shippedId)).isEqualTo(postedBefore)
            assertThat(database.migrate().migrationsExecuted).isZero()
        }

        private fun durableFacts(token: String, id: String) = fixture(token).transaction {
            scalar("""SELECT jsonb_build_array(
                (SELECT to_jsonb(document) FROM inventory_document document WHERE id='$id'),
                (SELECT jsonb_agg(to_jsonb(line) ORDER BY line.id) FROM inventory_document_line line WHERE document_id='$id'),
                (SELECT jsonb_agg(to_jsonb(operation) ORDER BY operation.document_revision) FROM inventory_operation operation WHERE document_id='$id'),
                (SELECT jsonb_agg(to_jsonb(identity) ORDER BY identity.id) FROM inventory_command_identity identity
                    JOIN inventory_operation operation ON operation.tenant_id=identity.tenant_id AND operation.id=identity.id WHERE operation.document_id='$id'),
                (SELECT jsonb_agg(to_jsonb(movement) ORDER BY movement.id) FROM inventory_movement movement WHERE document_id='$id'))::text""")
        }
    }
}
