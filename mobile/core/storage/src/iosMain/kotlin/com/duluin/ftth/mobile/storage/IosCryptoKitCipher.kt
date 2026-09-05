package com.duluin.ftth.mobile.storage

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

internal class IosCryptoKitCipher(private val keyStore: IosKeychainKeyStore) : OutboxCipher {
    private val algorithm = CryptographyProvider.CryptoKit.get(AES.GCM)

    override fun encrypt(payload: ByteArray): EncryptedBlob {
        val version = keyStore.currentVersion()
        val key = algorithm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, keyStore.read(version))
        return EncryptedBlob(version, key.cipher().encryptBlocking(payload))
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        if (blob.bytes.size < 28) throw OutboxDecryptionException()
        return try {
            val key = algorithm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, keyStore.read(blob.keyVersion))
            key.cipher().decryptBlocking(blob.bytes)
        } catch (failure: IosKeychainFailure) {
            throw failure
        } catch (_: Throwable) {
            throw OutboxDecryptionException()
        }
    }
}
