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
            val seed = WarehouseDraftUpgradeProcess.seed(database, "transfer", "178.9")
            assertThat(database.migrate().migrations.map { it.version }).contains("178.10", "178.12")
            postingContext(database).use { context -> UpgradeClient(context).verify(database, seed) }
        }
    }

    private class UpgradeClient(application: ConfigurableApplicationContext) : WarehouseTransferFixture() {
        init {
            context = application
            mvc = MockMvcBuilders.webAppContextSetup(application as WebApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity()).build()
            ReflectionTestUtils.setField(this, "onboarding", application.getBean(OnboardTenantUseCase::class.java))
        }

        fun verify(database: WarehouseSchemaDatabase, seed: tools.jackson.databind.JsonNode) {
            val stock = mapper.treeToValue(seed.path("stock"), TransferStock::class.java)
            val admin = stock.setup.token
            val original = seed.path("original").asString()
            val id = seed.path("id").asString()
            val shippedStock = mapper.treeToValue(seed.path("shippedStock"), TransferStock::class.java)
            val shippedId = seed.path("shippedId").asString()
            val shipped = seed.path("shipped")
            val before = seed.path("before").asString()
            val postedBefore = seed.path("postedBefore").asString()
            assertThat(durableFacts(admin, id)).isEqualTo(before)
            assertThat(durableFacts(shippedStock.setup.token, shippedId)).isEqualTo(postedBefore)
            assertThat(request("POST", "/api/v1/warehouse/transfers", admin, transferBody(stock), "pre-upgrade").contentAsString)
                .isEqualTo(original)
            assertThat(transferAction(shippedStock, shippedId, "dispatch", """{"expectedRevision":0}""", "pre-upgrade-dispatch"))
                .isEqualTo(shipped)
            fixture(admin).transaction {
                assertThat(scalar("SELECT provenance FROM inventory_document_draft_activity WHERE document_id='$id'")).isEqualTo("LEGACY_BASELINE")
                assertThat(scalar("SELECT (deadline>clock_timestamp())::text FROM inventory_document_draft_activity WHERE document_id='$id'")).isEqualTo("true")
            }
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
