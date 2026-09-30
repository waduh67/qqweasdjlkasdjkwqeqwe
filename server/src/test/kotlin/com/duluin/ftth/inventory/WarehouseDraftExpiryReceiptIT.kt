package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseDraftExpiryService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDraftExpiryReceiptIT : WarehouseReceiptHttpFixture() {
    private val root = "/api/v1/warehouse/receipts"

    @Test fun `reads replay and caller supplied header time never renew the default clock`() {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        val body = body(setup)
        val saved = request("POST", root, setup.token, body, "created")
        assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(201)
        val id = mapper.readTree(saved.contentAsString).path("id").asString()
        val original = activity(database, id)
        repeat(2) {
            assertThat(request("GET", "$root/$id", setup.token).status).isEqualTo(200)
            assertThat(request("POST", root, setup.token, body, "created").contentAsString).isEqualTo(saved.contentAsString)
        }
        database.transaction {
            assertThat(scalar("SELECT ttl_seconds FROM inventory_draft_policy WHERE tenant_id='$tenant' AND version=1")).isEqualTo("604800")
            sql("UPDATE inventory_document SET updated_at='infinity',revision=revision+1 WHERE id='$id'")
            assertThat(scalar("SELECT count(*) FROM inventory_document_draft_activity WHERE document_id='$id'")).isEqualTo("1")
        }
        assertThat(activity(database, id)).isEqualTo(original)
    }

    @Test fun `accepted receipt save seals original numeric spellings and renews its pinned policy once`() {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        val body = body(setup)
        val created = request("POST", root, setup.token, body, "created")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val original = activity(database, id)
        policy(database, 31536000)
        val update = body.dropLast(1) + ",\"expectedRevision\":0}"
        val saved = request("PUT", "$root/$id", setup.token, update, "saved")
        assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(saved.contentAsString).path("revision").asLong()).isEqualTo(1)
        assertThat(request("PUT", "$root/$id", setup.token, update, "saved").contentAsString).isEqualTo(saved.contentAsString)
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_document_draft_activity WHERE document_id='$id'")).isEqualTo("2")
            assertThat(scalar("SELECT count(DISTINCT policy_version) FROM inventory_document_draft_activity WHERE document_id='$id'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_receipt_draft_command WHERE document_id='$id'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
        }
        assertThat(activity(database, id)).isNotEqualTo(original)
    }

    @Test fun `due receipt is terminal with scheduler disabled and keeps original response and stock facts`() {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        policy(database, 2)
        val body = body(setup)
        val created = request("POST", root, setup.token, body, "created")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val before = activity(database, id)
        awaitDeadline(database, id)
        policy(database, 31536000)
        val current = request("GET", "$root/$id", setup.token)
        assertThat(current.status).withFailMessage(current.contentAsString).isEqualTo(200)
        val expired = mapper.readTree(current.contentAsString)
        assertThat(expired.path("state").asString()).isEqualTo("EXPIRED")
        assertThat(expired.path("draftExpiry").path("reason").asString()).isEqualTo("IDLE_DEADLINE")
        assertThat(expired.path("draftEditability").asString()).isEqualTo("NOT_DRAFT")
        for ((method, path, input) in listOf(
            Triple("PUT", "$root/$id", body.dropLast(1) + ",\"expectedRevision\":0}"),
            Triple("POST", "$root/$id/receive", """{"expectedRevision":0}"""),
        )) {
            val response = request(method, path, setup.token, input)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
            assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("DRAFT_EXPIRED")
        }
        assertThat(request("POST", root, setup.token, body, "created").contentAsString).isEqualTo(created.contentAsString)
        for ((status, count) in listOf("DRAFT" to 0L, "EXPIRED" to 1L)) {
            val page = request("GET", "$root?status=$status", setup.token)
            assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(page.contentAsString).path("totalElements").asLong()).isEqualTo(count)
        }
        val failure = catchThrowable { database.transaction { sql("DELETE FROM inventory_document_line WHERE document_id='$id'") } }
        assertThat(sqlFailure(failure).serverErrorMessage?.constraint).isEqualTo("warehouse_draft_expired_ck")
        assertThat(activity(database, id)).isEqualTo(before)
        TenantContext.runAs(database.tenant) {
            assertThat(context.getBean(WarehouseDraftExpiryService::class.java).expireOne()).isTrue()
        }
        val after = mapper.readTree(request("GET", "$root/$id", setup.token).contentAsString)
        assertThat(after.path("draftExpiry").path("deadline")).isEqualTo(expired.path("draftExpiry").path("deadline"))
        database.transaction {
            assertThat(scalar("SELECT state||':'||revision FROM inventory_document WHERE id='$id'")).isEqualTo("DRAFT:0")
            assertThat(scalar("SELECT count(*) FROM inventory_document_draft_expiry WHERE document_id='$id'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_segment")).isEqualTo("0")
        }
    }

    @Test fun `application cannot tamper with policy activity or expiry and an unsealed fake save rolls back`() {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"REEL"}""").path("id").asString()
        val original = activity(database, id)
        for (statement in listOf(
            "UPDATE inventory_document_draft_activity SET deadline='infinity' WHERE document_id='$id'",
            "DELETE FROM inventory_draft_policy",
            "TRUNCATE inventory_document_draft_expiry",
            "INSERT INTO inventory_draft_policy(tenant_id,version,ttl_seconds) VALUES ('${database.tenant}',100,31536000)",
        )) {
            val failure = catchThrowable { database.transaction { sql(statement) } }
            assertThat(sqlFailure(failure).sqlState).isEqualTo("42501")
        }
        val spoof = catchThrowable { database.transaction {
            sql("UPDATE inventory_document SET revision=revision+1 WHERE id='$id'")
            sql("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
                document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch)
                SELECT gen_random_uuid(),tenant_id,'warehouse.receipt.update','forged',actor_id,id,'receipt:'||id,repeat('a',64),
                    id,revision,'UPDATE',200,'{"state":"DRAFT"}',cutover_epoch,authority_epoch FROM inventory_document WHERE id='$id'""")
        } }
        assertThat(sqlFailure(spoof).message).contains("receipt draft save requires canonical command and intake binding")
        assertThat(activity(database, id)).isEqualTo(original)
        database.transaction { assertThat(scalar("SELECT revision FROM inventory_document WHERE id='$id'")).isEqualTo("0") }
    }

    @Test fun `a save waiting on its source row cannot renew after the database deadline`() {
        val setup = setupReceipt()
        val database = fixture(setup.token)
        policy(database, 5)
        val body = body(setup)
        val id = create("receipts", setup.token, body).path("id").asString()
        connection(database, owner = false).use { lock ->
            lock.autoCommit = false
            scope(lock, database)
            val pid = lock.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { r -> r.next(); r.getInt(1) } }
            lock.createStatement().use { it.executeQuery("SELECT id FROM inventory_document WHERE id='$id' FOR UPDATE").close() }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<org.springframework.mock.web.MockHttpServletResponse> {
                    request("PUT", "$root/$id", setup.token, body.dropLast(1) + ",\"expectedRevision\":0}", "waiting-save")
                }
                poll(Duration.ofSeconds(4)) { database.transaction {
                    scalar("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE $pid=ANY(pg_blocking_pids(pid)))::text").toBoolean()
                } }
                awaitDeadline(database, id)
                lock.commit()
                val response = pending.get(30, TimeUnit.SECONDS)
                assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
                assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("DRAFT_EXPIRED")
                database.transaction { assertThat(scalar("SELECT count(*) FROM inventory_document_draft_activity WHERE document_id='$id'")).isEqualTo("1") }
            } finally { lock.rollback(); executor.shutdownNow() }
        }
    }

    private fun body(setup: Setup) = draftBody(setup, """{"skuId":"${setup.cable}","quantityBase":"01000","lotCode":"REEL",
        "conversion":{"numerator":"100","denominator":"1","packageQuantity":"10"},"cost":{"totalMinor":"0007","currency":"IDR"}},
        {"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"EXACT-ONU"}],"cost":{"totalMinor":"0009","currency":"IDR"}}""")

    private fun activity(database: WarehousePostingFixture, id: String) = database.transaction {
        scalar("SELECT jsonb_agg(to_jsonb(activity) ORDER BY source_revision)::text FROM inventory_document_draft_activity activity WHERE document_id='$id'")
    }

    private fun policy(database: WarehousePostingFixture, seconds: Int) = connection(database, owner = true).use { connection ->
        connection.autoCommit = false
        scope(connection, database)
        connection.prepareStatement("""INSERT INTO inventory_draft_policy(tenant_id,version,ttl_seconds)
            SELECT ?,coalesce(max(version),0)+1,? FROM inventory_draft_policy WHERE tenant_id=?""").use {
            it.setObject(1, database.tenant); it.setInt(2, seconds); it.setObject(3, database.tenant); it.executeUpdate()
        }
        connection.commit()
    }

    private fun connection(database: WarehousePostingFixture, owner: Boolean): Connection {
        check(System.getenv("WAREHOUSE_QA") == "true")
        val url = database.context.environment.getRequiredProperty("spring.datasource.url")
        check(url == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        val prefix = if (owner) "SPRING_FLYWAY" else "SPRING_DATASOURCE"
        return DriverManager.getConnection(url, System.getenv("${prefix}_${if (owner) "USER" else "USERNAME"}"), System.getenv("${prefix}_PASSWORD"))
    }

    private fun scope(connection: Connection, database: WarehousePostingFixture) {
        connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)").use { it.setString(1, database.tenant.toString()); it.execute() }
    }

    private fun awaitDeadline(database: WarehousePostingFixture, id: String) = poll(Duration.ofSeconds(10)) {
        database.transaction { scalar("SELECT (deadline<=clock_timestamp())::text FROM inventory_document_draft_activity WHERE document_id='$id' ORDER BY source_revision DESC LIMIT 1").toBoolean() }
    }

    private fun poll(timeout: Duration, ready: () -> Boolean) {
        val end = Instant.now().plus(timeout)
        while (!ready()) { check(Instant.now().isBefore(end)) { "Expected database condition was not reached" }; Thread.sleep(25) }
    }

    private fun sqlFailure(failure: Throwable?): org.postgresql.util.PSQLException =
        generateSequence(failure) { it.cause }.filterIsInstance<org.postgresql.util.PSQLException>().firstOrNull()
            ?: throw AssertionError("Expected a PostgreSQL rejection", failure)
}
