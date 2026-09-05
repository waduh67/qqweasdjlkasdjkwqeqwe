package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.SecureOutboxOperation
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.readBytes
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL

@OptIn(ExperimentalForeignApi::class)

internal class IosOutboxRecords(private val userId: String) : SecureOutboxRecords {
    private val directory: NSURL = ((NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        .first() as NSURL)
        .URLByAppendingPathComponent("com.duluin.ftth.mobile/outbox/${userId.encodeToByteArray().toHex()}", isDirectory = true)
        ?: error("Application Support URL unavailable")
        ).also { NSFileManager.defaultManager.createDirectoryAtURL(it, true, null, null) }

    override fun entries(): List<SecureOutboxRecord> = NSFileManager.defaultManager.contentsOfDirectoryAtURL(directory, null, 0.toULong(), null)
        .orEmpty().mapNotNull { url -> NSData.dataWithContentsOfURL(url as NSURL)?.toByteArray()?.let(::decode) }

    override fun write(record: SecureOutboxRecord) {
        val bytes = encode(record)
        bytes.usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong()) }
            .writeToURL(directory.URLByAppendingPathComponent(fileName(record), isDirectory = false) ?: error("Record URL unavailable"), atomically = true)
    }

    override fun delete(userId: String) {
        entries().filter { it.operation.userId == userId }.forEach { record ->
            NSFileManager.defaultManager.removeItemAtURL(directory.URLByAppendingPathComponent(fileName(record), isDirectory = false) ?: return@forEach, null)
        }
    }

    override fun retry(key: String): Boolean = entries().firstOrNull { identity(it) == key }?.let { record ->
        write(record.copy(retries = record.retries + 1))
        true
    } ?: false

    private fun identity(record: SecureOutboxRecord) = "${record.operation.userId}:${record.operation.namespace}:${record.operation.key}"
    private fun fileName(record: SecureOutboxRecord) = identity(record).encodeToByteArray().toHex() + ".outbox"

    private fun encode(record: SecureOutboxRecord): ByteArray = listOf(
        record.operation.userId, record.operation.deviceId, record.operation.sessionId,
        record.operation.namespace, record.operation.key, record.operation.payloadHash,
        record.operation.revision.toString(), record.retries.toString(), record.payload.keyVersion,
        record.payload.bytes.toHex(),
    ).joinToString("\u0000").encodeToByteArray()

    private fun decode(bytes: ByteArray): SecureOutboxRecord? = runCatching {
        val fields = bytes.decodeToString().split('\u0000')
        require(fields.size == 10)
        val operation = SecureOutboxOperation(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6].toLong(), byteArrayOf())
        SecureOutboxRecord(operation, EncryptedBlob(fields[8], fields[9].fromHex()), fields[7].toInt())
    }.getOrNull()

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun String.fromHex(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun NSData.toByteArray(): ByteArray? = bytes?.readBytes(length.toInt())
}
