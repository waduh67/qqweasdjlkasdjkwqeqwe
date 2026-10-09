package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.storage.*
import kotlinx.serialization.json.*

internal fun fieldTokens(access: String = "access", refresh: String = "refresh") = buildJsonObject {
    put("accessToken", access); put("refreshToken", refresh)
    put("user", buildJsonObject {
        put("id", USER); put("tenantId", TENANT); put("name", "Budi"); put("email", "budi@example.test")
        put("permissions", buildJsonArray { add("warehouse.material.own"); add("warehouse.request.create"); add("wo.view.own") })
    })
}.toString()
internal class FieldCredentials : TechnicianCredentialStore {
    var saved: String? = null
    var corrupt = false
    override fun read(): String? { check(!corrupt); return saved }
    override fun write(value: String) { saved = value }
    override fun clear() { saved = null; corrupt = false }
}
internal data class FieldCall(val path: String, val token: String?, val body: String?, val key: String?, val evidence: FieldEvidence?)
internal class FieldTransport : FieldHttpTransport {
    val calls = mutableListOf<FieldCall>()
    var response: suspend (FieldCall) -> FieldHttpResponse = { call ->
        FieldHttpResponse(200, when (call.path) {
            "/api/auth/login", "/api/auth/refresh" -> fieldTokens()
            "/api/v2/warehouse/workflow" -> """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":2}}"""
            else -> """{"id":"$WORK"}"""
        })
    }
    override suspend fun send(server: String, path: String, token: String?, body: String?, key: String?, evidence: FieldEvidence?): FieldHttpResponse {
        val call = FieldCall(path, token, body, key, evidence); calls.add(call); return response(call)
    }
}
internal class FieldRecords : SecureOutboxRecords {
    val rows = linkedMapOf<String, SecureOutboxRecord>()
    private fun id(record: SecureOutboxRecord) = record.operation.userId + ":" + record.operation.namespace + ":" + record.operation.key
    override fun entries() = rows.values.toList()
    override fun write(record: SecureOutboxRecord) { rows[id(record)] = record }
    override fun delete(userId: String) { rows.entries.removeAll { it.value.operation.userId == userId } }
    override fun retry(key: String) = rows.containsKey(key)
    override fun remove(key: String) { rows.remove(key) }
}
internal object FieldCipher : OutboxCipher {
    override fun encrypt(payload: ByteArray) = EncryptedBlob("test-only", payload.map { (it.toInt() xor 91).toByte() }.toByteArray())
    override fun decrypt(blob: EncryptedBlob) = blob.bytes.map { (it.toInt() xor 91).toByte() }.toByteArray()
}
internal fun fieldWork() = FieldWork(WORK, "WO-PSB", "Pasang", "", 5, USER, 2, FieldWorkState.PENDING,
    FieldWorkType("PSB", true, listOf("Bukti Pasang", "Bukti Kedatangan")), null)
internal fun fieldDetail(work: FieldWork = fieldWork()) = buildJsonObject {
    put("workOrder", buildJsonObject {
        put("id", work.id); put("code", work.code); put("title", work.title); put("description", work.description)
        put("revision", work.revision); put("technicianId", work.technicianId); put("assignmentGeneration", work.assignmentGeneration)
        put("state", work.state.name); put("scheduledAt", JsonNull)
        put("type", buildJsonObject { put("name", work.type.name); put("materialRequired", work.type.materialRequired)
            put("photoSlots", JsonArray(work.type.photoSlots.map(::JsonPrimitive))) })
    })
    put("overdue", false); put("timeline", buildJsonArray {})
}.toString()
internal fun fieldStock() = FieldStock(SOURCE, MaterialSku(ISSUE, "DROP", "Kabel drop", MaterialTracking.LOT, MaterialUnit.MM),
    MaterialQuantity.base("100000"), 7, "Tas Budi", USER, "ISSUED", null, null)
