package com.duluin.ftth.mobile.storage

import com.duluin.ftth.mobile.domain.TechnicianCredentialStore
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosTechnicianCredentials : TechnicianCredentialStore {
    private val service = "com.duluin.ftth.technician.session"
    private fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T {
        val attributes = requireNotNull(CFDictionaryCreateMutable(kCFAllocatorDefault, 6, null, null))
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain("current")
        try {
            CFDictionaryAddValue(attributes, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(attributes, kSecAttrService, serviceRef)
            CFDictionaryAddValue(attributes, kSecAttrAccount, accountRef)
            return block(attributes)
        } finally {
            CFRelease(attributes)
            CFRelease(serviceRef)
            CFRelease(accountRef)
        }
    }
    override fun read(): String? = memScoped {
        withQuery { attributes ->
            CFDictionaryAddValue(attributes, kSecMatchLimit, kSecMatchLimitOne)
            CFDictionaryAddValue(attributes, kSecReturnData, kCFBooleanTrue)
            val result = alloc<CFTypeRefVar>()
            when (val status = SecItemCopyMatching(attributes, result.ptr)) {
                errSecItemNotFound -> null
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as? NSData ?: error("Keychain session is not data")
                    requireNotNull(data.bytes).readBytes(data.length.toInt()).decodeToString()
                }
                else -> throw IosKeychainFailure("read session", status)
            }
        }
    }
    override fun write(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.isNotEmpty())
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        val dataRef = CFBridgingRetain(data)
        try {
            withQuery { attributes ->
                CFDictionaryAddValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                CFDictionaryAddValue(attributes, kSecValueData, dataRef)
                when (val status = SecItemAdd(attributes, null)) {
                    errSecSuccess -> Unit
                    errSecDuplicateItem -> {
                        val update = requireNotNull(CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null))
                        try {
                            CFDictionaryAddValue(update, kSecValueData, dataRef)
                            val changed = withQuery { SecItemUpdate(it, update) }
                            if (changed != errSecSuccess) throw IosKeychainFailure("write session", changed)
                        } finally { CFRelease(update) }
                    }
                    else -> throw IosKeychainFailure("write session", status)
                }
            }
        } finally { CFRelease(dataRef) }
    }
    override fun clear() {
        withQuery { attributes ->
            when (val status = SecItemDelete(attributes)) {
                errSecSuccess, errSecItemNotFound -> Unit
                else -> throw IosKeychainFailure("delete session", status)
            }
        }
    }
}
