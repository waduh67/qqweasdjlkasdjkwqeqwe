package com.duluin.ftth.inventory

import com.duluin.ftth.FtthApplication
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Compiled only with the pinned pre-expiry application, never the current test classpath. */
object WarehouseDraftUpgradeSeed {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4 && System.getenv("WAREHOUSE_QA") == "true")
        val output = Path.of(args[0])
        val url = args[1]
        require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:25432/warehouse_fixture_[a-f0-9]{32}")))
        require(args[2] in setOf("178.9", "178.10"))
        SpringApplicationBuilder(FtthApplication::class.java).profiles("test").run(
            "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=$url", "--spring.flyway.url=$url",
            "--spring.flyway.schemas=public", "--spring.flyway.default-schema=public", "--spring.flyway.target=${args[2]}",
            "--ftth.bootstrap.seed-demo-tenant=false",
        ).use { context ->
            context.getBean(javax.sql.DataSource::class.java).connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_user,rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user").use { rows ->
                        check(rows.next() && rows.getString(1) == "warehouse_app" && !rows.getBoolean(2) && !rows.getBoolean(3))
                    }
                }
            }
            val client = SeedClient(context)
            val seed = when (args[3]) { "transfer" -> client.transferSeed(); "count" -> client.countSeed(); else -> error("Unknown seed family") }
            Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(output, jacksonObjectMapper().writeValueAsString(seed))
        }
    }

    private class SeedClient(application: ConfigurableApplicationContext) : WarehouseTransferFixture() {
        init {
            context = application
            mvc = MockMvcBuilders.webAppContextSetup(application as WebApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity()).build()
            ReflectionTestUtils.setField(this, "onboarding", application.getBean(OnboardTenantUseCase::class.java))
        }
        fun transferSeed(): Map<String, Any> {
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
            return mapOf("stock" to stock, "original" to original.contentAsString, "id" to id,
                "shippedStock" to shippedStock, "shippedId" to shippedId, "shipped" to shipped,
                "before" to before, "postedBefore" to postedBefore)
        }
        fun countSeed(): Map<String, Any> {
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
            return mapOf("stock" to stock, "body" to body, "balance" to balance, "draftId" to draftId,
                "oldDraft" to oldDraft.contentAsString, "countingId" to countingId, "oldStart" to oldStart.contentAsString,
                "postedId" to postedId, "oldPosted" to oldPosted.contentAsString, "before" to before)
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
