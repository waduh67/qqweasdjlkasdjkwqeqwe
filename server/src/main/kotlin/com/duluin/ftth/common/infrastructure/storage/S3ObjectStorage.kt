package com.duluin.ftth.common.infrastructure.storage

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.ObjectPage
import com.duluin.ftth.common.storage.DeleteGuard
import com.duluin.ftth.common.storage.StoredObjectMetadata
import com.duluin.ftth.common.storage.StoredObject
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import java.security.MessageDigest

/**
 * Adapter [ObjectStorage] di atas S3/MinIO. Semua objek satu tenant hidup dalam
 * satu bucket, dipisah lewat prefix kunci (`<tenant>/wo/...`, `<tenant>/billing/...`);
 * pemisahan tenant yang mengikat tetap ditegakkan di lapisan metadata (Hibernate + RLS),
 * prefix ini sekadar tata letak + pertahanan berlapis.
 */
@Component
@Profile("!test")
class S3ObjectStorage(
    private val s3: S3Client,
    private val properties: StorageProperties,
) : ObjectStorage {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pastikan bucket ada saat start; jangan gagalkan boot bila storage sedang mati. */
    @PostConstruct
    fun ensureBucket() {
        try {
            s3.headBucket { it.bucket(properties.bucket) }
        } catch (_: NoSuchBucketException) {
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.bucket).build())
            log.info("Bucket storage '{}' dibuat", properties.bucket)
        } catch (ex: Exception) {
            log.warn("Tidak bisa memverifikasi bucket '{}' saat start: {}", properties.bucket, ex.message)
        }
    }

    override fun put(key: String, contentType: String, bytes: ByteArray) {
        require(key.isNotBlank() && !key.startsWith("/")) { "Invalid storage key" }
        s3.putObject(
            PutObjectRequest.builder().bucket(properties.bucket).key(key).contentType(contentType)
                .metadata(mapOf("sha256" to sha256(bytes))).build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun get(key: String): StoredObject {
        val response = try {
            s3.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket).key(key).build())
        } catch (_: NoSuchKeyException) {
            throw NotFoundException("Objek $key tidak ditemukan di storage")
        }
        return StoredObject(
            contentType = response.response().contentType() ?: "application/octet-stream",
            bytes = response.asByteArray(),
            etag = response.response().eTag(),
            version = response.response().versionId(),
        )
    }

    override fun head(tenantId: String, key: String): StoredObjectMetadata {
        requireTenantKey(tenantId, key)
        val response = s3.headObject { it.bucket(properties.bucket).key(key) }
        return StoredObjectMetadata(key, response.contentType() ?: "application/octet-stream", response.contentLength(), response.metadata()["sha256"], response.eTag(), response.versionId())
    }

    override fun list(tenantId: String, prefix: String, pageToken: String?, pageSize: Int): ObjectPage {
        requireTenantKey(tenantId, prefix)
        val response = s3.listObjectsV2(
            ListObjectsV2Request.builder().bucket(properties.bucket).prefix(prefix)
                .continuationToken(pageToken).maxKeys(pageSize.coerceIn(1, 1000)).build(),
        )
        val objects = response.contents().map { item ->
            val metadata = head(tenantId, item.key())
            metadata.copy(size = item.size())
        }
        return ObjectPage(objects, response.nextContinuationToken())
    }

    override fun deleteIfMatch(tenantId: String, key: String, guard: DeleteGuard): Boolean {
        requireTenantKey(tenantId, key)
        val request = DeleteObjectRequest.builder().bucket(properties.bucket).key(key)
            .apply { guard.version?.let(::versionId); guard.etag?.let(::ifMatch) }.build()
        return try {
            s3.deleteObject(request)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket).key(key).build())
    }

    private fun requireTenantKey(tenantId: String, key: String) {
        require(tenantId.isNotBlank() && key.startsWith("$tenantId/")) { "Cross-tenant storage access" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
