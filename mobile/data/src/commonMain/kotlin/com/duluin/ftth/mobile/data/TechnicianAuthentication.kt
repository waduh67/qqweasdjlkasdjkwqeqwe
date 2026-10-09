package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.data.MaterialJson.id
import com.duluin.ftth.mobile.data.MaterialJson.obj
import com.duluin.ftth.mobile.data.MaterialJson.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class FieldApiFailure(val status: Int, val code: String? = null) : IllegalStateException(when {
    code == "TWO_FACTOR_REQUIRED" -> "Masukkan kode autentikator untuk melanjutkan."
    status == 401 -> "Sesi berakhir atau kredensial tidak sesuai. Masuk kembali."
    status == 403 -> "Akun ini tidak memiliki akses untuk tindakan tersebut."
    status == 409 -> "Data sudah berubah. Muat ulang sebelum membuat draf baru."
    status in 400..499 -> "Permintaan ditolak ($status). Periksa isian dan akses."
    else -> "Server belum dapat memproses permintaan ($status). Coba lagi."
})

class TechnicianAuthentication(
    private val transport: FieldHttpTransport, private val credentials: TechnicianCredentialStore,
    private val newKey: () -> String, private val purge: (String) -> Unit,
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow(restore())
    val session = current.asStateFlow()

    suspend fun login(server: String, email: String, password: String, otp: String? = null) = mutex.withLock {
        val origin = normalizeServer(server)
        val response = transport.send(origin, "/api/auth/login", body = buildJsonObject {
            put("email", email.trim()); put("password", password); otp?.takeIf(String::isNotBlank)?.let { put("otpCode", it.trim()) }
        }.toString())
        ensureSuccess(response)
        val parsed = MaterialJson.parse(response.body)
        val account = account(parsed.obj("user"))
        val identity = OutboxIdentity(account.id, newKey(), newKey())
        val next = tokens(parsed, origin, identity)
        current.value?.let { purge(it.account.id) }
        credentials.write(encode(next)); current.value = next
    }

    suspend fun logout() = mutex.withLock {
        val previous = current.value ?: return@withLock
        current.value = null
        try {
            credentials.clear(); purge(previous.account.id)
        } finally {
            try { transport.send(previous.server, "/api/auth/logout", body = buildJsonObject { put("refreshToken", previous.refreshToken) }.toString()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Local logout has already removed credentials and drafts. */ }
        }
    }

    fun capture(): TechnicianSession = requireNotNull(current.value) { "Masuk untuk membuka pekerjaan teknisi." }
    fun check(expected: TechnicianSession) {
        val actual = current.value
        require(actual?.identity == expected.identity && actual.server == expected.server && actual.account.tenantId == expected.account.tenantId) { "Sesi berubah. Draf akun lama tidak dikirim." }
    }

    suspend fun request(path: String, body: String? = null, key: String? = null, evidence: FieldEvidence? = null, expected: TechnicianSession = capture()): FieldHttpResponse {
        check(expected)
        val sent = capture()
        var response = transport.send(sent.server, path, sent.accessToken, body, key, evidence)
        check(expected)
        if (response.status == 401) {
            val refreshed = refresh(sent)
            check(expected)
            response = transport.send(refreshed.server, path, refreshed.accessToken, body, key, evidence)
            check(expected)
        }
        return response
    }

    private suspend fun refresh(sent: TechnicianSession): TechnicianSession = mutex.withLock {
        check(sent)
        val latest = capture()
        if (latest.accessToken != sent.accessToken) return@withLock latest
        val result = transport.send(latest.server, "/api/auth/refresh", body = buildJsonObject { put("refreshToken", latest.refreshToken) }.toString())
        if (result.status in setOf(400, 401, 403)) {
            current.value = null; credentials.clear(); purge(latest.account.id)
            throw FieldApiFailure(401)
        }
        ensureSuccess(result)
        val next = tokens(MaterialJson.parse(result.body), latest.server, latest.identity)
        require(next.account.id == latest.account.id && next.account.tenantId == latest.account.tenantId) { "Identitas refresh tidak sesuai." }
        credentials.write(encode(next)); current.value = next; next
    }

    private fun restore(): TechnicianSession? {
        return try {
            val saved = credentials.read() ?: return null
            val row = MaterialJson.parse(saved)
            val user = account(row.obj("user"))
            val identity = OutboxIdentity(user.id, row.text("deviceId"), row.text("sessionId"))
            tokens(row, normalizeServer(row.text("server")), identity)
        } catch (_: Exception) { credentials.clear(); null }
    }
    private fun tokens(row: JsonObject, server: String, identity: OutboxIdentity) = TechnicianSession(server, identity, account(row.obj("user")), row.text("accessToken"), row.text("refreshToken"))
    private fun account(row: JsonObject) = TechnicianAccount(row.id("id"), row.id("tenantId"), row.text("name"), row.text("email"),
        row.getValue("permissions").jsonArray.map { it.jsonPrimitive.let { value -> require(value.isString); value.content } }.toSet())
    private fun encode(value: TechnicianSession) = buildJsonObject {
        put("server", value.server); put("deviceId", value.identity.deviceId); put("sessionId", value.identity.sessionId)
        put("accessToken", value.accessToken); put("refreshToken", value.refreshToken)
        put("user", buildJsonObject { put("id", value.account.id); put("tenantId", value.account.tenantId); put("name", value.account.name); put("email", value.account.email)
            put("permissions", JsonArray(value.account.permissions.map(::JsonPrimitive))) })
    }.toString()
}

fun ensureSuccess(response: FieldHttpResponse) {
    if (response.status !in 200..299) {
        val code = runCatching { MaterialJson.parse(response.body)["code"]?.jsonPrimitive?.content }.getOrNull()
        throw FieldApiFailure(response.status, code)
    }
}

fun normalizeServer(input: String): String {
    val server = input.trim().trimEnd('/')
    require(server.matches(Regex("https://[a-zA-Z0-9.-]+(:[0-9]{1,5})?")) || server.matches(Regex("""http://(localhost|127\.0\.0\.1|10\.0\.2\.2)(:[0-9]{1,5})?"""))) { "Gunakan alamat HTTPS server, atau HTTP localhost untuk pengujian." }
    return server
}
