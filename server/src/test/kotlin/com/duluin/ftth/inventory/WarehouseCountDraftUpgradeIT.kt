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

class WarehouseCountDraftUpgradeIT {
    @Test fun `upgrade preserves existing draft counting and posted evidence and only the never started draft can change`() {
        WarehouseSchemaDatabase("178.10").use { database ->
            postingContext(database, "178.10").use { context -> UpgradeClient(context).verify(database) }
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
            val token = stock.setup.token
            val root = "/api/v1/warehouse/counts"
            val balance = fixture(token).transaction {
                scalar("SELECT id FROM inventory_balance_projection WHERE stock_identity_id='${stock.identity}' AND location_id='${stock.setup.bin}'")
            }
            val body = """{"locationId":"${stock.setup.bin}","partialLocation":true,"reason":"Before upgrade",
                "entries":[{"balanceId":"$balance","counterId":"${stock.receiver}"}]}"""
            val oldDraft = request("POST", root, token, body, "old-draft")
            assertThat(oldDraft.status).withFailMessage(oldDraft.contentAsString).isEqualTo(201)
            val draftId = mapper.readTree(oldDraft.contentAsString).path("id").asString()
            val countingId = create("counts", token, body).path("id").asString()
            val oldStart = request("POST", "$root/$countingId/start", token, """{"expectedRevision":0}""", "old-start")
            assertThat(oldStart.status).withFailMessage(oldStart.contentAsString).isEqualTo(200)
            val postedId = create("counts", token, body).path("id").asString()
            assertThat(request("POST", "$root/$postedId/start", token, """{"expectedRevision":0}""").status).isEqualTo(200)
            assertThat(request("POST", "$root/$postedId/observe", token,
                """{"expectedRevision":1,"balanceId":"$balance","quantityBase":"100000","reason":"Physical measurement","documentReference":"OLD-COUNT"}""").status).isEqualTo(200)
            val oldPosted = request("POST", "$root/$postedId/submit", token, """{"expectedRevision":2}""", "old-submit")
            assertThat(oldPosted.status).withFailMessage(oldPosted.contentAsString).isEqualTo(200)
            val before = facts(token)
            assertThat(database.migrate().migrations.map { it.version }).contains("178.11")
            assertThat(facts(token)).isEqualTo(before)
            assertThat(request("POST", root, token, body, "old-draft").contentAsString).isEqualTo(oldDraft.contentAsString)
            assertThat(request("POST", "$root/$countingId/start", token, """{"expectedRevision":0}""", "old-start").contentAsString)
                .isEqualTo(oldStart.contentAsString)
            assertThat(request("POST", "$root/$postedId/submit", token, """{"expectedRevision":2}""", "old-submit").contentAsString)
                .isEqualTo(oldPosted.contentAsString)
            val changed = request("PUT", "$root/$draftId", token,
                """{"expectedRevision":0,"draft":${body.replace("Before upgrade", "After upgrade")}}""")
            assertThat(changed.status).withFailMessage(changed.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(changed.contentAsString).path("revision").asLong()).isEqualTo(1)
            for ((id, revision) in listOf(countingId to 1, postedId to 5)) {
                assertThat(request("PUT", "$root/$id", token, """{"expectedRevision":$revision,"draft":$body}""").status).isEqualTo(409)
            }
            assertThat(request("POST", "$root/$draftId/start", token, """{"expectedRevision":1}""").status).isEqualTo(200)
            fixture(token).transaction {
                assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE id='$balance'")).isEqualTo("100000")
                assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id IN ('$draftId','$countingId','$postedId')")).isEqualTo("0")
            }
            assertThat(database.migrate().migrationsExecuted).isZero()
        }

        private fun facts(token: String) = fixture(token).transaction {
            scalar("""SELECT jsonb_build_array(
                (SELECT jsonb_agg(to_jsonb(document) ORDER BY document.id) FROM inventory_document document WHERE kind='COUNT'),
                (SELECT jsonb_agg(to_jsonb(scope) ORDER BY scope.id) FROM inventory_count_scope scope),
                (SELECT jsonb_agg(to_jsonb(line) ORDER BY line.id) FROM inventory_document_line line JOIN inventory_count_scope scope ON scope.id=line.document_id),
                (SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.id) FROM inventory_count_entry entry),
                (SELECT jsonb_agg(to_jsonb(round) ORDER BY round.document_id,round.document_revision) FROM inventory_count_round round),
                (SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.id) FROM inventory_cycle_count fact),
                (SELECT jsonb_agg(to_jsonb(result) ORDER BY result.id) FROM inventory_count_result result),
                (SELECT jsonb_agg(to_jsonb(operation) ORDER BY operation.id) FROM inventory_operation operation),
                (SELECT jsonb_agg(to_jsonb(identity) ORDER BY identity.id) FROM inventory_command_identity identity),
                (SELECT jsonb_agg(to_jsonb(movement) ORDER BY movement.id) FROM inventory_movement movement))::text""")
        }
    }
}
