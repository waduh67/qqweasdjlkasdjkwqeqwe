package com.duluin.ftth.common.infrastructure.storage

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.ObjectStorage
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
        s3.putObject(
            PutObjectRequest.builder().bucket(properties.bucket).key(key).contentType(contentType).build(),
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
        )
    }

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket).key(key).build())
    }
}
