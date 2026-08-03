package com.duluin.ftth

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

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
}
