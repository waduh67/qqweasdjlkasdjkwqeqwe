package com.duluin.ftth.mobile.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.duluin.ftth.mobile.domain.SecureOutboxOperation
import com.duluin.ftth.mobile.domain.SecureOutboxPort
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureOutbox(context: Context) : SecureOutboxPort by EncryptedOutbox(
    AndroidOutboxRecords(context.applicationContext),
    AndroidKeystoreCipher(),
)

private class AndroidKeystoreCipher : OutboxCipher {
    private val alias = "ftth.mobile.outbox.v1"
    private val version = "v1"

    override fun encrypt(payload: ByteArray): EncryptedBlob {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce)) }
        return EncryptedBlob(version, nonce + cipher.doFinal(payload))
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        if (blob.keyVersion != version || blob.bytes.size < 13) throw OutboxDecryptionException()
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.bytes.copyOfRange(0, 12))) }
                .doFinal(blob.bytes.copyOfRange(12, blob.bytes.size))
        } catch (_: Exception) {
            throw OutboxDecryptionException()
        }
    }

    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}

private class AndroidOutboxRecords(context: Context) : SecureOutboxRecords {
    private val preferences = context.getSharedPreferences("ftth.secure.outbox", Context.MODE_PRIVATE)
    override fun entries(): List<SecureOutboxRecord> = preferences.all.values.mapNotNull { decode(it as? String ?: return@mapNotNull null) }
    override fun write(record: SecureOutboxRecord) { preferences.edit().putString(id(record), encode(record)).commit() }
    override fun delete(userId: String) { preferences.edit().also { editor -> entries().filter { it.operation.userId == userId }.forEach { editor.remove(id(it)) } }.commit() }
    override fun retry(key: String): Boolean = entries().firstOrNull { id(it) == key }?.let { write(it.copy(retries = it.retries + 1)); true } ?: false

    private fun id(record: SecureOutboxRecord) = "${record.operation.namespace}:${record.operation.key}"
    private fun encode(record: SecureOutboxRecord): String = listOf(record.operation.userId, record.operation.deviceId, record.operation.sessionId, record.operation.namespace, record.operation.key, record.operation.payloadHash, record.operation.revision.toString(), record.retries.toString(), record.payload.keyVersion, Base64.getEncoder().encodeToString(record.payload.bytes)).joinToString(".") { Base64.getEncoder().encodeToString(it.encodeToByteArray()) }
    private fun decode(value: String): SecureOutboxRecord? = try {
        val fields = value.split('.').map { Base64.getDecoder().decode(it).decodeToString() }
        val operation = SecureOutboxOperation(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6].toLong(), byteArrayOf())
        SecureOutboxRecord(operation, EncryptedBlob(fields[8], Base64.getDecoder().decode(fields[9])), fields[7].toInt())
    } catch (_: Exception) { null }
}
