package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.storage.S3ObjectStorage
import com.duluin.ftth.common.infrastructure.storage.S3StorageConfig
import com.duluin.ftth.common.infrastructure.storage.StorageProperties
import com.duluin.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import software.amazon.awssdk.services.s3.S3Client

@TestConfiguration
class ReceiptRealStorage {
    @Bean(destroyMethod = "close") fun receiptS3Client(properties: StorageProperties): S3Client = S3StorageConfig().s3Client(properties)
    @Bean @Primary fun receiptStorage(client: S3Client, properties: StorageProperties): ObjectStorage = S3ObjectStorage(client, properties).also { it.ensureBucket() }
}

@Import(ReceiptRealStorage::class)
class WarehouseReceiptITAttachments : WarehouseReceiptHttpFixture() {
    @Autowired private lateinit var storage: ObjectStorage
    @Test fun `authenticated receipt evidence uses real private object storage and rejects MIME mismatch`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"R1"}""")
        val id = draft.path("id").asString()
        val bytes = "%PDF-1.4\nreceipt proof\n%%EOF".toByteArray()
        fun upload(type: String, data: ByteArray = bytes, key: String = "attachment-key") = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
            .file(MockMultipartFile("file", "proof.pdf", type, data)).param("expectedRevision", "0")
            .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", key)).andReturn().response
        assertThat(upload("image/png").status).isEqualTo(400)
        assertThat(upload("application/pdf", ByteArray(15728641)).status).isEqualTo(400)
        val result = upload("application/pdf")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        val evidence = mapper.readTree(result.contentAsString)
        val tenantId = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("tenantId").asString()
        val objectKey = "$tenantId/warehouse/receipts/$id/${evidence.path("id").asString()}"
        try {
            assertThat(storage).isInstanceOf(S3ObjectStorage::class.java)
            assertThat(storage.get(objectKey).bytes).isEqualTo(bytes)
            assertThat(result.contentAsString).doesNotContain(objectKey, "http://", "https://")
            assertThat(upload("application/pdf").contentAsString).isEqualTo(result.contentAsString)
            val downloaded = request("GET", "/api/v1/warehouse/receipts/$id/attachments/${evidence.path("id").asString()}", setup.token)
            assertThat(downloaded.status).isEqualTo(200)
            assertThat(downloaded.contentAsByteArray).isEqualTo(bytes)
            assertThat(request("GET", "/api/v1/warehouse/receipts/$id/attachments/${evidence.path("id").asString()}", tenant()).status).isEqualTo(404)
        } finally { storage.delete(objectKey) }
    }
}
