package com.duluin.ftth

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.common.storage.DeleteGuard
import com.duluin.ftth.common.storage.ObjectPage
import com.duluin.ftth.common.storage.StoredObjectMetadata
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

/**
 * Storage tiruan untuk test: menyimpan byte di memori, meniru kontrak [ObjectStorage]
 * (termasuk melempar saat kunci tak ada) tanpa perlu MinIO/S3. Menggantikan
 * `S3ObjectStorage` yang aktif hanya di profil non-test.
 */
@Component
@Profile("test")
class InMemoryObjectStorage : ObjectStorage {

    private val objects = ConcurrentHashMap<String, StoredObject>()

    override fun put(key: String, contentType: String, bytes: ByteArray) {
        objects[key] = StoredObject(contentType, bytes.copyOf())
    }

    override fun get(key: String): StoredObject =
        objects[key] ?: throw NotFoundException("Objek $key tidak ditemukan di storage")

    override fun delete(key: String) {
        objects.remove(key)
    }

    override fun head(tenantId: String, key: String): StoredObjectMetadata {
        requireTenant(tenantId, key)
        val value = objects[key] ?: throw NotFoundException("Objek $key tidak ditemukan di storage")
        return value.metadata(key)
    }

    override fun list(tenantId: String, prefix: String, pageToken: String?, pageSize: Int): ObjectPage {
        require(prefix.startsWith("$tenantId/")) { "cross-tenant prefix" }
        val keys = objects.keys.filter { it.startsWith(prefix) }.sorted()
        val start = pageToken?.toIntOrNull() ?: 0
        val end = minOf(start + pageSize.coerceAtLeast(1), keys.size)
        return ObjectPage(keys.subList(start, end).map { objects.getValue(it).metadata(it) }, end.takeIf { it < keys.size }?.toString())
    }

    override fun deleteIfMatch(tenantId: String, key: String, guard: DeleteGuard): Boolean {
        requireTenant(tenantId, key)
        val value = objects[key] ?: return false
        if (guard.etag != null && guard.etag != value.etag) return false
        objects.remove(key, value)
        return true
    }

    private fun requireTenant(tenantId: String, key: String) {
        require(key.startsWith("$tenantId/")) { "cross-tenant key" }
    }

    private fun StoredObject.metadata(key: String) = StoredObjectMetadata(
        key, contentType, size, sha256(bytes), etag, version,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
