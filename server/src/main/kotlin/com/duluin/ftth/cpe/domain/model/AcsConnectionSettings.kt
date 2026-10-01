package com.duluin.ftth.cpe.domain.model

import com.duluin.ftth.common.domain.error.ValidationException
import java.net.URI
import java.util.UUID

class AcsConnectionSettings(
    val version: UUID,
    val nbiUrl: String,
    val username: String,
    val password: String?,
    val cwmpUrl: String?,
    val persisted: Boolean,
) {
    fun updated(nbiUrl: String, username: String?, password: String?, cwmpUrl: String): AcsConnectionSettings {
        val user = username?.trim().orEmpty()
        if (user.length > 255 || user.contains(':') || user.any { it.isISOControl() }) {
            throw ValidationException("Username API tidak valid")
        }
        if (password != null && password.length > 512) throw ValidationException("Password API maksimal 512 karakter")
        val secret = password?.takeIf { it.isNotBlank() } ?: this.password
        if (user.isNotEmpty() && secret.isNullOrBlank()) throw ValidationException("Password API wajib diisi untuk autentikasi")
        return AcsConnectionSettings(UUID.randomUUID(), url(nbiUrl, "NBI URL").trimEnd('/'), user, secret, url(cwmpUrl, "CWMP URL"), true)
    }

    companion object {
        val SINGLETON_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val ENV_VERSION: UUID = UUID.fromString("00000000-0000-4000-8000-000000000000")

        private fun url(value: String, label: String): String {
            val text = value.trim()
            val uri = runCatching { URI(text) }.getOrNull()
            if (text.length > 2048 || uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0) {
                throw ValidationException("$label harus berupa URL http:// atau https:// tanpa kredensial, query atau fragment")
            }
            return text
        }
    }
}
