package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.common.storage.DeleteGuard
import com.karuhun.ftth.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO

internal fun fieldHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@Component
class FieldPhotoStorage(private val storage: ObjectStorage) {
    private val logger = LoggerFactory.getLogger(javaClass)
    fun validate(photo: B2BPhotoInput) {
        if(photo.bytes.size !in 1..5*1024*1024 || photo.contentType !in setOf("image/jpeg","image/png"))
            throw ValidationException("Foto harus JPEG atau PNG, maksimal 5 MB per foto")
        val signature = if(photo.contentType=="image/png") byteArrayOf(-119,80,78,71,13,10,26,10) else byteArrayOf(-1,-40,-1)
        if(!photo.bytes.take(signature.size).toByteArray().contentEquals(signature)) throw ValidationException("Isi foto tidak sesuai format")
        ImageIO.createImageInputStream(ByteArrayInputStream(photo.bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if(!readers.hasNext()) throw ValidationException("Foto tidak dapat dibaca")
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                if(width<=0 || height<=0 || width.toLong()*height>25_000_000) throw ValidationException("Ukuran gambar terlalu besar")
                reader.read(0) ?: throw ValidationException("Foto tidak dapat dibaca")
            } catch(failure: java.io.IOException) { throw ValidationException("Foto rusak atau tidak dapat dibaca") }
            finally { reader.dispose() }
        }
    }
    fun put(tenant: UUID, key: String, photo: B2BPhotoInput): String {
        val hash = fieldHash(photo.bytes)
        TransactionSynchronizationManager.registerSynchronization(object: TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if(status != TransactionSynchronization.STATUS_ROLLED_BACK) return
                try {
                    val value = storage.get(key)
                    if(fieldHash(value.bytes) != hash) return
                    val meta = storage.head(tenant.toString(), key)
                    if(!storage.deleteIfMatch(tenant.toString(), key, DeleteGuard(meta.etag, meta.version)))
                        logger.warn("Field photo rollback cleanup pending: {}",key)
                } catch(failure: Exception) { logger.warn("Field photo rollback cleanup failed: {}",key,failure) }
            }
        })
        storage.put(key,photo.contentType,photo.bytes)
        read(key,photo.contentType,photo.bytes.size.toLong(),hash)
        return hash
    }
    fun read(key: String, contentType: String, size: Long, hash: String): B2BPhotoInput {
        val value = storage.get(key)
        if(value.contentType != contentType || value.size != size || fieldHash(value.bytes) != hash)
            throw ConflictException("Bukti foto berubah di penyimpanan")
        return B2BPhotoInput(contentType,value.bytes)
    }
}
