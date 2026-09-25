package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.storage.S3ObjectStorage
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationEvidenceStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.StoredMigrationEvidence
import com.duluin.ftth.inventory.application.service.MigrationEvidenceReconciler
import com.duluin.ftth.inventory.application.service.ReceiptEvidenceReconciliation
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReceiptRealStorage::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMigrationEvidenceIT : WarehousePolicyHttpFixture() {
    companion object {
        private val legacy = List(3) { WarehouseMigrationLegacyFixture() }
        private val database = WarehouseSchemaDatabase("172").also { db -> db.ownerFixture { connection -> legacy.forEach { it.seed(connection) } } }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var storage: ObjectStorage
    @Autowired private lateinit var dataSource: DataSource
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private data class Source(val token: String, val actor: UUID, val tenant: UUID, val batch: UUID, val case: UUID,
        val hash: String, val location: UUID) {
        val path = "/api/v1/warehouse/provenance/batches/$batch/cases/$case/evidence"
        val prefix = "$tenant/warehouse/migrations/$batch/$case/"
    }
    private fun setup(index: Int): Source {
        val old = legacy[index]
        val token = tenant("migration-${old.tenant}")
        val actor = UUID.fromString(mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString())
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${old.tenant}'")
            sql.execute("UPDATE inventory_location SET name='Gudang lama',area_id='${area(token)}',revision=revision+1 WHERE id='${old.location}'")
            sql.execute("UPDATE customer SET area_id='${area(token)}' WHERE id='${old.customer}'")
        } }
        grant(token, actor.toString(), listOf(old.location.toString()))
        val summary = mapper.readTree(request("GET", "/api/v1/warehouse/provenance", token).contentAsString)
        val begin = request("POST", "/api/v1/warehouse/provenance/batches", token,
            mapper.writeValueAsString(mapOf("expectedEpoch" to 0, "expectedPreservationHash" to summary.path("preservationHash").asString())))
        assertThat(begin.status).withFailMessage(begin.contentAsString).isEqualTo(201)
        val row = mapper.readTree(request("GET", "/api/v1/warehouse/provenance/cases?sourceTable=inventory_balance_projection", token).contentAsString).path("items")[0]
        return Source(token, actor, old.tenant, UUID.fromString(mapper.readTree(begin.contentAsString).path("batch").path("id").asString()),
            UUID.fromString(row.path("id").asString()), row.path("sourceHash").asString(), old.location)
    }

    @Test fun `real private evidence rejects mismatch and response loss replays one persisted file under current authority`() {
        val source = setup(0)
        val pdf = ReceiptEvidenceFixtures.pdf()
        assertThat(storage).isInstanceOf(S3ObjectStorage::class.java)
        assertThat(send(source, "bad-mime", pdf, "image/png").statusCode()).isEqualTo(400)
        assertThat(send(source, "bad-pdf", "not a PDF".toByteArray()).statusCode()).isEqualTo(400)
        assertThat(send(source, "bad-label", pdf, label = "bad\u0000label").statusCode()).isEqualTo(400)
        val input = payload(source, pdf)
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("POST ${source.path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${source.token}\r\n" +
                    "Idempotency-Key: lost-migration-file\r\nContent-Type: multipart/form-data; boundary=migration-evidence-boundary\r\nContent-Length: ${input.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(input); socket.getOutputStream().flush()
            }
            val fixture = fixture(source.token)
            await().atMost(Duration.ofSeconds(20)).until { fixture.transaction { scalar("SELECT count(*) FROM inventory_migration_evidence") == "1" } }
            val original = requireNotNull(fixture.transaction { scalar("SELECT original_body FROM inventory_migration_evidence") })
            val replay = send(source, "lost-migration-file", pdf)
            assertThat(replay.statusCode()).withFailMessage(replay.body()).isEqualTo(201)
            assertThat(replay.body()).isEqualTo(original).doesNotContain(source.prefix, "http://", "https://")
            val id = UUID.fromString(mapper.readTree(original).path("id").asString())
            val objectKey = source.prefix + id
            assertThat(storage.list(source.tenant.toString(), source.prefix).objects).hasSize(1)
            assertThat(storage.get(objectKey).bytes).isEqualTo(pdf)
            val downloaded = request("GET", "${source.path}/$id", source.token)
            assertThat(downloaded.status).isEqualTo(200)
            assertThat(downloaded.contentAsByteArray).isEqualTo(pdf)
            assertThat(downloaded.getHeader("Cache-Control")).contains("no-store")
            assertThat(downloaded.getHeader("Content-Disposition")).contains("attachment")
            val list = request("GET", "${source.path}?size=1", source.token)
            assertThat(mapper.readTree(list.contentAsString).path("totalElements").asInt()).isEqualTo(1)
            assertThat(send(source, "lost-migration-file", pdf, label = "Changed evidence label").statusCode()).isEqualTo(409)
            assertThat(send(source.copy(hash = "0".repeat(64)), "wrong-source", pdf).statusCode()).isEqualTo(409)
            val other = user(source.token, setOf("inventory.provenance.manage"))
            grant(source.token, other.second, listOf(source.location.toString()))
            assertThat(send(source.copy(token = other.first), "lost-migration-file", pdf).statusCode()).isEqualTo(403)
            assertThat(request("GET", "${source.path}/$id", tenant()).status).isEqualTo(404)
            val persisted = requireNotNull(fixture.transaction { context.getBean(MigrationEvidenceStore::class.java).get(source.batch, source.case, id) })
            assertThat(context.getBean(MigrationEvidenceReconciler::class.java).reconcile(source.tenant, persisted)).isEqualTo(ReceiptEvidenceReconciliation.RETAINED)
            fixture.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_migration_evidence")).isEqualTo("1")
                assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
            }
            storage.delete(objectKey)
            assertThat(request("GET", "${source.path}/$id", source.token).status).isEqualTo(409)
            assertThat(send(source, "lost-migration-file", pdf).statusCode()).isEqualTo(409)
        } finally { storage.list(source.tenant.toString(), source.prefix).objects.forEach { storage.delete(it.key) } }
    }

    @Test fun `database backend termination after object write rolls back metadata and reconciles only that private object`() {
        val source = setup(1)
        val marker = "migration_upload_${UUID.randomUUID().toString().replace("-", "")}"
        val lockKey = (UUID.randomUUID().leastSignificantBits and 0x7fffffff).toInt()
        context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource.connection.use { lock ->
            lock.createStatement().use { sql ->
                sql.execute("SELECT pg_advisory_lock($lockKey)")
                sql.execute("CREATE FUNCTION $marker() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_advisory_xact_lock($lockKey); RETURN NEW; END'")
                sql.execute("""CREATE CONSTRAINT TRIGGER $marker AFTER INSERT ON inventory_migration_evidence DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW WHEN (NEW.batch_id='${source.batch}') EXECUTE FUNCTION $marker()""")
            }
            try {
                val response = client.sendAsync(uploadRequest(source, "terminated", ReceiptEvidenceFixtures.pdf()), HttpResponse.BodyHandlers.ofString())
                dataSource.connection.use { observer ->
                    await().atMost(Duration.ofSeconds(20)).until { observer.createStatement().use { sql ->
                        sql.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=$lockKey AND NOT granted").use { rows -> rows.next(); rows.getInt(1) == 1 }
                    } }
                    assertThat(storage.list(source.tenant.toString(), source.prefix).objects).hasSize(1)
                    observer.createStatement().use { it.execute("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=$lockKey AND NOT granted") }
                }
                assertThat(response.get(20, TimeUnit.SECONDS).statusCode()).isGreaterThanOrEqualTo(400)
                fixture(source.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_migration_evidence")).isEqualTo("0") }
                await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(storage.list(source.tenant.toString(), source.prefix).objects).isEmpty() }
            } finally {
                lock.createStatement().use { sql ->
                    sql.execute("SELECT pg_advisory_unlock($lockKey)")
                    sql.execute("DROP TRIGGER $marker ON inventory_migration_evidence")
                    sql.execute("DROP FUNCTION $marker()")
                }
                storage.list(source.tenant.toString(), source.prefix).objects.forEach { storage.delete(it.key) }
            }
        }
    }

    @Test fun `unsettled batch retains object until metadata absence is confirmed in a fresh transaction`() {
        val source = setup(2)
        val id = UUID.randomUUID()
        val key = source.prefix + id
        val pdf = ReceiptEvidenceFixtures.pdf()
        val hash = MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) }
        val view = WarehouseMigrationEvidence(id, source.batch, source.case, source.hash, "Unsettled evidence", "application/pdf", pdf.size.toLong(), hash, source.actor, Instant.now())
        val candidate = StoredMigrationEvidence(view, key, mapper.writeValueAsString(view), source.actor, "a".repeat(64), 1)
        val reconciler = context.getBean(MigrationEvidenceReconciler::class.java)
        storage.put(key, "application/pdf", pdf)
        val foreignKey = "${UUID.randomUUID()}/warehouse/migrations/${source.batch}/${source.case}/$id"
        storage.put(foreignKey, "application/pdf", pdf)
        try {
            assertThat(reconciler.reconcile(source.tenant, candidate.copy(objectKey = foreignKey))).isEqualTo(ReceiptEvidenceReconciliation.UNRESOLVED)
            assertThat(storage.get(foreignKey).bytes).isEqualTo(pdf)
            dataSource.connection.use { lock ->
                lock.autoCommit = false
                try {
                    lock.createStatement().use { sql ->
                        sql.execute("SET LOCAL app.tenant_id='${source.tenant}'")
                        sql.execute("SELECT pg_advisory_xact_lock(hashtextextended('${source.tenant}|migration-batch|${source.batch}',0))")
                    }
                    assertThat(reconciler.reconcile(source.tenant, candidate)).isEqualTo(ReceiptEvidenceReconciliation.UNRESOLVED)
                    assertThat(storage.get(key).bytes).isEqualTo(pdf)
                } finally { lock.rollback() }
            }
            assertThat(reconciler.reconcile(source.tenant, candidate)).isEqualTo(ReceiptEvidenceReconciliation.DELETED)
            assertThat(storage.list(source.tenant.toString(), source.prefix).objects).isEmpty()
        } finally { storage.delete(key); storage.delete(foreignKey) }
    }

    private fun send(source: Source, key: String, bytes: ByteArray, type: String = "application/pdf", label: String = "Bukti saldo lama") =
        client.send(uploadRequest(source, key, bytes, type, label), HttpResponse.BodyHandlers.ofString())
    private fun uploadRequest(source: Source, key: String, bytes: ByteArray, type: String = "application/pdf", label: String = "Bukti saldo lama") =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port${source.path}")).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${source.token}").header("Idempotency-Key", key)
            .header("Content-Type", "multipart/form-data; boundary=migration-evidence-boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload(source, bytes, type, label))).build()
    private fun payload(source: Source, bytes: ByteArray, type: String = "application/pdf", label: String = "Bukti saldo lama"): ByteArray {
        val body = mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedCaseHash" to source.hash, "label" to label))
        return ("--migration-evidence-boundary\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n$body\r\n" +
            "--migration-evidence-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof.pdf\"\r\nContent-Type: $type\r\n\r\n").toByteArray() +
            bytes + "\r\n--migration-evidence-boundary--\r\n".toByteArray()
    }
}
