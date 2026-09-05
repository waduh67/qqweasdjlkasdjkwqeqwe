package com.duluin.ftth.mobile.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Security.kSecRandomDefault
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.kCFAllocatorDefault

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosKeychainFailure(val operation: String, val status: Int) : IllegalStateException(
    "Keychain $operation failed with status $status",
)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosKeychainKeyStore(val userId: String) {
    private val service = "com.duluin.ftth.mobile.secure-outbox"
    private val manifestAccount = "user:$userId:manifest"

    init {
        if (readManifest() == null) {
            writeManifest(listOf("v1"))
            write("v1", generateKey())
        }
    }

    fun currentVersion(): String = readManifest()?.firstOrNull() ?: error("Keychain manifest missing")

    fun versions(): List<String> = readManifest().orEmpty()

    fun rotate(): String {
        val version = "v${(readManifest().orEmpty().mapNotNull { it.removePrefix("v").toIntOrNull() }.maxOrNull() ?: 0) + 1}"
        write(version, generateKey())
        writeManifest(listOf(version) + readManifest().orEmpty())
        return version
    }

    fun read(version: String): ByteArray = copy(version) ?: throw IosKeychainFailure("read", errSecItemNotFound)

    fun delete(version: String) {
        val status = SecItemDelete(query(account(version)))
        when (status) {
            errSecSuccess, errSecItemNotFound -> Unit
            else -> throw IosKeychainFailure("delete", status)
        }
        writeManifest(readManifest().orEmpty().filterNot { it == version })
    }

    fun deleteAllVersions() {
        readManifest().orEmpty().forEach { version -> delete(version) }
        val status = SecItemDelete(query(manifestAccount))
        when (status) {
            errSecSuccess, errSecItemNotFound -> Unit
            else -> throw IosKeychainFailure("delete", status)
        }
    }

    private fun generateKey(): ByteArray = ByteArray(32).also { bytes ->
        bytes.usePinned { pinned -> check(SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), pinned.addressOf(0)) == 0) }
    }

    private fun write(version: String, bytes: ByteArray) {
        val value = bytes.usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong()) }
        val addStatus = SecItemAdd(query(account(version), value), null)
        when (addStatus) {
            errSecSuccess -> return
            errSecDuplicateItem -> Unit
            else -> throw IosKeychainFailure("add", addStatus)
        }
        if (addStatus == errSecDuplicateItem) {
            val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null)
            CFDictionaryAddValue(attributes, kSecValueData, CFBridgingRetain(value))
            val updateStatus = SecItemUpdate(query(account(version)), attributes)
            if (updateStatus != errSecSuccess) throw IosKeychainFailure("update", updateStatus)
        }
    }

    private fun copy(version: String): ByteArray? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query(account(version), returnData = true), result.ptr)
        when (status) {
            errSecItemNotFound -> null
            errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()
                ?: throw IosKeychainFailure("copy", status)
            else -> throw IosKeychainFailure("copy", status)
        }
    }

    private fun readManifest(): List<String>? = copy(manifestAccount)?.decodeToString()?.split(',')?.filter(String::isNotBlank)
    private fun writeManifest(versions: List<String>) = write(manifestAccount, versions.joinToString(",").encodeToByteArray())
    private fun account(version: String) = "user:$userId:key:$version"

    private fun NSData.toByteArray(): ByteArray? = bytes?.readBytes(length.toInt())

    private fun query(account: String, value: NSData? = null, returnData: Boolean = false): CFDictionaryRef? =
        CFDictionaryCreateMutable(kCFAllocatorDefault, 8, null, null).also { dictionary ->
            CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dictionary, kSecAttrService, CFBridgingRetain(service))
            CFDictionaryAddValue(dictionary, kSecAttrAccount, CFBridgingRetain(account))
            CFDictionaryAddValue(dictionary, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            CFDictionaryAddValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
            if (returnData) CFDictionaryAddValue(dictionary, kSecReturnData, kCFBooleanTrue)
            if (value != null) CFDictionaryAddValue(dictionary, kSecValueData, CFBridgingRetain(value))
        }
}
