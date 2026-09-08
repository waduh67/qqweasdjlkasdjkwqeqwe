package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReceiptRealStorage::class)
class WarehouseReceiptITUploadFailures : WarehouseReceiptHttpFixture() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var storage: ObjectStorage
    @Autowired private lateinit var dataSource: DataSource
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun `backend termination after S3 write confirms rollback before removing private object`() {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"FAULT"}""").path("id").asString()
        val database = fixture(setup.token)
        val prefix = "${database.tenant}/warehouse/receipts/$id/"
        val marker = "receipt_upload_${UUID.randomUUID().toString().replace("-", "")}"
        val lockKey = (UUID.randomUUID().leastSignificantBits and 0x7fffffff).toInt()
        context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource.connection.use { lock ->
            lock.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock($lockKey)")
                statement.execute("CREATE FUNCTION $marker() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_advisory_xact_lock($lockKey); RETURN NEW; END'")
                statement.execute("CREATE TRIGGER $marker BEFORE INSERT ON inventory_operation FOR EACH ROW WHEN (NEW.document_id='$id' AND NEW.namespace='warehouse.receipt.attachment') EXECUTE FUNCTION $marker()")
            }
            try {
                val response = client.sendAsync(uploadRequest(setup, id, ReceiptEvidenceFixtures.pdf(), "terminated"), HttpResponse.BodyHandlers.ofString())
                dataSource.connection.use { observer ->
                    await().atMost(Duration.ofSeconds(20)).until { observer.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=$lockKey AND NOT granted").use { rows -> rows.next(); rows.getInt(1) == 1 }
                    } }
                    assertThat(storage.list(database.tenant.toString(), prefix).objects).hasSize(1)
                    observer.createStatement().use { statement -> statement.execute("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=$lockKey AND NOT granted") }
                }
                assertThat(response.get(20, TimeUnit.SECONDS).statusCode()).isGreaterThanOrEqualTo(400)
                database.transaction {
                    assertThat(scalar("SELECT count(*) FROM inventory_receipt_evidence")).isEqualTo("0")
                    assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.receipt.attachment'")).isEqualTo("0")
                    assertThat(scalar("SELECT revision FROM inventory_document WHERE id='$id'")).isEqualTo("0")
                }
                await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(storage.list(database.tenant.toString(), prefix).objects).isEmpty() }
            } finally {
                lock.createStatement().use { statement ->
                    statement.execute("SELECT pg_advisory_unlock($lockKey)")
                    statement.execute("DROP TRIGGER $marker ON inventory_operation")
                    statement.execute("DROP FUNCTION $marker()")
                }
                storage.list(database.tenant.toString(), prefix).objects.forEach { storage.delete(it.key) }
            }
        }
    }

    @Test fun `lost upload response retains committed object and exact replay`() {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"LOST-HTTP"}""").path("id").asString()
        val database = fixture(setup.token)
        val prefix = "${database.tenant}/warehouse/receipts/$id/"
        val pdf = ReceiptEvidenceFixtures.pdf()
        val payload = multipart(pdf)
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("POST /api/v1/warehouse/receipts/$id/attachments HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${setup.token}\r\n" +
                    "Idempotency-Key: lost-upload\r\nContent-Type: multipart/form-data; boundary=receipt-upload-boundary\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
            }
            await().atMost(Duration.ofSeconds(20)).until { database.transaction { scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.receipt.attachment'") == "1" } }
            val original = database.transaction { scalar("SELECT original_body FROM inventory_operation WHERE namespace='warehouse.receipt.attachment'") }
            val response = client.send(uploadRequest(setup, id, pdf, "lost-upload"), HttpResponse.BodyHandlers.ofString())
            assertThat(response.statusCode()).isEqualTo(201)
            assertThat(response.body()).isEqualTo(original)
            val objects = storage.list(database.tenant.toString(), prefix).objects
            assertThat(objects).hasSize(1)
            assertThat(storage.get(objects.single().key).bytes).isEqualTo(pdf)
            val persisted = database.transaction { context.getBean(com.duluin.ftth.inventory.adapter.outbound.persistence.ReceiptEvidencePersistence::class.java)
                .get(UUID.fromString(id), UUID.fromString(mapper.readTree(original).path("id").asString())) }
            assertThat(context.getBean(com.duluin.ftth.inventory.application.service.ReceiptEvidenceReconciler::class.java).reconcile(database.tenant, persisted))
                .isEqualTo(com.duluin.ftth.inventory.application.service.ReceiptEvidenceReconciliation.RETAINED)
            assertThat(storage.get(objects.single().key).bytes).isEqualTo(pdf)
        } finally { storage.list(database.tenant.toString(), prefix).objects.forEach { storage.delete(it.key) } }
    }

    @Test fun `unsettled transaction keeps private object until fresh metadata absence can be confirmed`() {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"UNSETTLED"}""").path("id").asString()
        val database = fixture(setup.token)
        val evidence = UUID.randomUUID()
        val key = "${database.tenant}/warehouse/receipts/$id/$evidence"
        val pdf = ReceiptEvidenceFixtures.pdf()
        val candidate = com.duluin.ftth.inventory.adapter.outbound.persistence.StoredReceiptEvidence(
            com.duluin.ftth.inventory.application.port.inbound.ReceiptEvidenceView(evidence, UUID.fromString(id), "application/pdf", pdf.size.toLong(), "a".repeat(64)), key, null)
        val reconciler = context.getBean(com.duluin.ftth.inventory.application.service.ReceiptEvidenceReconciler::class.java)
        storage.put(key, "application/pdf", pdf)
        try {
            dataSource.connection.use { lock ->
                lock.autoCommit = false
                try {
                    lock.createStatement().use { statement ->
                        statement.execute("SET LOCAL app.tenant_id='${database.tenant}'")
                        statement.execute("SELECT id FROM inventory_document WHERE id='$id' FOR UPDATE")
                    }
                    assertThat(reconciler.reconcile(database.tenant, candidate)).isEqualTo(com.duluin.ftth.inventory.application.service.ReceiptEvidenceReconciliation.UNRESOLVED)
                    assertThat(storage.get(key).bytes).isEqualTo(pdf)
                } finally { lock.rollback() }
            }
            assertThat(reconciler.reconcile(database.tenant, candidate)).isEqualTo(com.duluin.ftth.inventory.application.service.ReceiptEvidenceReconciliation.DELETED)
            assertThat(storage.list(database.tenant.toString(), "${database.tenant}/warehouse/receipts/$id/").objects).isEmpty()
        } finally { storage.delete(key) }
    }

    private fun uploadRequest(setup: Setup, id: String, bytes: ByteArray, key: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/warehouse/receipts/$id/attachments"))
        .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", key)
        .header("Content-Type", "multipart/form-data; boundary=receipt-upload-boundary").POST(HttpRequest.BodyPublishers.ofByteArray(multipart(bytes))).build()
    private fun multipart(bytes: ByteArray): ByteArray = ("--receipt-upload-boundary\r\nContent-Disposition: form-data; name=\"expectedRevision\"\r\n\r\n0\r\n" +
        "--receipt-upload-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").toByteArray() +
        bytes + "\r\n--receipt-upload-boundary--\r\n".toByteArray()
}
