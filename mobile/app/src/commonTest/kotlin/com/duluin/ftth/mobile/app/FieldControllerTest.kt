package com.duluin.ftth.mobile.app

import com.duluin.ftth.mobile.data.ReferenceTechnicianRepository
import com.duluin.ftth.mobile.data.TechnicianAuthentication
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.storage.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

class FieldControllerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun offlineStartupShowsPersistedCommandsBeforeWorkflowRead() = runTest {
        val user = "11111111-1111-4111-8111-111111111111"
        val tenant = "22222222-2222-4222-8222-222222222222"
        var offline = false
        val transport = object : FieldHttpTransport {
            override suspend fun send(server: String, path: String, token: String?, body: String?, key: String?, evidence: FieldEvidence?): FieldHttpResponse {
                check(!offline) { "offline" }
                return FieldHttpResponse(200, """{"accessToken":"access","refreshToken":"refresh","user":{"id":"$user","tenantId":"$tenant","name":"Budi","email":"budi@example.test","permissions":[]}}""")
            }
        }
        val credentials = object : TechnicianCredentialStore {
            var saved: String? = null
            override fun read() = saved
            override fun write(value: String) { saved = value }
            override fun clear() { saved = null }
        }
        val records = object : SecureOutboxRecords {
            val rows = linkedMapOf<String, SecureOutboxRecord>()
            override fun entries() = rows.values.toList()
            override fun write(record: SecureOutboxRecord) { rows[record.operation.userId + ":" + record.operation.namespace + ":" + record.operation.key] = record }
            override fun delete(userId: String) { rows.entries.removeAll { it.value.operation.userId == userId } }
            override fun retry(key: String) = rows.containsKey(key)
            override fun remove(key: String) { rows.remove(key) }
        }
        val cipher = object : OutboxCipher {
            override fun encrypt(payload: ByteArray) = EncryptedBlob("test-only", payload.copyOf())
            override fun decrypt(blob: EncryptedBlob) = blob.bytes.copyOf()
        }
        val outbox = EncryptedOutbox(records, cipher)
        val auth = TechnicianAuthentication(transport, credentials, { "identity" }, outbox::purge)
        auth.login("http://localhost", "budi@example.test", "password")
        val repository = ReferenceTechnicianRepository(auth, outbox, { "retained-key" })
        repository.queueRequest(2, null, "Kabel baru", MaterialUnit.MM, MaterialQuantity.base("12501"), "Restok", true)
        offline = true
        val restored = ReferenceTechnicianRepository(TechnicianAuthentication(transport, credentials, { error("Restore identity") }, outbox::purge), outbox, { error("Retain command") })
        val controller = FieldController(restored, this)

        controller.load()
        runCurrent()

        assertEquals(listOf("retained-key"), controller.state.value.pending.map { it.key })
        assertEquals(SecureDeliveryState.QUEUED, controller.state.value.pending.single().state)
        assertNull(controller.state.value.work)
        assertNull(controller.state.value.epoch)
        assertFalse(controller.state.value.snapshotCurrent)
        assertEquals("offline", controller.state.value.error)
        assertFalse(controller.state.value.busy)
    }
}
