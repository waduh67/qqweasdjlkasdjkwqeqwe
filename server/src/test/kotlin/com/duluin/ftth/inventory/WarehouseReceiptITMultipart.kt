package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReceiptRealStorage::class)
class WarehouseReceiptITMultipart : WarehouseReceiptHttpFixture() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var storage: ObjectStorage

    @Test fun `service and servlet oversize limits return the same warehouse error envelope`() {
        val setup = setupReceipt()
        val id = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"OVERSIZE"}""").path("id").asString()
        val database = fixture(setup.token)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        for (route in listOf("receipts/$id/attachments", "opening-balances/requests")) {
            val field = if (route.startsWith("receipts")) "expectedRevision" else "request"
            val body = if (field == "expectedRevision") "0" else """{"migrationReference":"COUNT","sourceSnapshot":"Sheet","cutoff":"2026-01-01T00:00:00Z"}"""
            val bodies = listOf(15728641, 21 * 1024 * 1024).map { size ->
                val payload = ("--receipt-boundary\r\nContent-Disposition: form-data; name=\"$field\"\r\n\r\n$body\r\n" +
                    "--receipt-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").toByteArray() +
                    ByteArray(size) + "\r\n--receipt-boundary--\r\n".toByteArray()
                val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/warehouse/$route"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "size-$size")
                    .header("Content-Type", "multipart/form-data; boundary=receipt-boundary").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), HttpResponse.BodyHandlers.ofString())
                assertThat(response.statusCode()).isEqualTo(400)
                val parsed = mapper.readTree(response.body())
                assertThat(parsed.path("code").asString()).isEqualTo("MALFORMED_REQUEST")
                assertThat(parsed.size()).isEqualTo(2)
                parsed
            }
            assertThat(bodies[0]).isEqualTo(bodies[1])
        }
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_receipt_evidence")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.receipt.attachment'")).isEqualTo("0")
            assertThat(scalar("SELECT revision FROM inventory_document WHERE id='$id'")).isEqualTo("0")
        }
        assertThat(storage.list(database.tenant.toString(), "${database.tenant}/warehouse/receipts/$id/").objects).isEmpty()
    }
}
