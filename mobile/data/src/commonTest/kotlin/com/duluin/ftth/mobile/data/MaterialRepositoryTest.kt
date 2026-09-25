package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.storage.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

internal const val WORK = "11111111-1111-4111-8111-111111111111"
internal const val SOURCE = "22222222-2222-4222-8222-222222222222"
internal const val ISSUE = "33333333-3333-4333-8333-333333333333"
internal const val USER = "44444444-4444-4444-8444-444444444444"
internal const val TENANT = "55555555-5555-4555-8555-555555555555"
internal fun contextJson(revision: Int = 5, use: Int = 0) = """{"id":"$WORK","code":"WO-01","workOrderRevision":$revision,"currentAssignee":true,"active":true,"technicalState":"ASSIGNED","qaState":null,
    "field":{"workOrderId":"$WORK","workOrderRevision":$revision,"plan":{"id":"$ISSUE","planRevision":3,"materialMode":"MATERIAL_REQUIRED"},"planState":"SUBMITTED","useRevision":$use,"latestUsageId":null,"reworkId":null,"evidenceRevision":null}}"""
internal fun sourceJson(revision: Int = 7) = """{"id":"$SOURCE","receiptId":"$ISSUE","issueId":"$ISSUE","issueCode":"ISS-01","issueLineId":"$ISSUE","planId":"$ISSUE","planLineId":"$ISSUE",
    "sku":{"id":"$SOURCE","code":"DROP","name":"Kabel drop","tracking":"LOT","baseUnit":"MM"},"sourceUsageId":null,"quantityBase":"100000","baseUnit":"MM","stockRevision":$revision,
    "location":{"id":"$SOURCE","code":"FIELD","name":"Tas Budi"},"serial":null,"lotCode":"REEL-1","initialUseSource":true}"""
internal fun issueJson() = """{"id":"$ISSUE","code":"ISS-01","workOrderId":"$WORK","workOrderRevision":5,"revision":9,"state":"DISPATCHED","sender":{"id":"$SOURCE","name":"Petugas"},"receiver":{"id":"$USER","name":"Budi"},
    "lines":[{"id":"$ISSUE","stockIdentityId":"$SOURCE","sku":{"id":"$SOURCE","code":"DROP","name":"Kabel drop","tracking":"LOT","baseUnit":"MM"},"baseUnit":"MM","dispatchedBase":"100000","acceptedBase":"0","remainingBase":"100000","serial":null,"lotCode":"REEL-1"}]}"""
internal fun reportDraft() = MaterialDraft.ReportUse(MaterialJson.context(contextJson(), WORK), listOf(MaterialMeasuredUse(MaterialJson.custody(MaterialJson.parse(sourceJson())), MaterialQuantity.base("82500"))), "Ukuran lapangan", null)
internal class MaterialTestSession : MaterialSessionPort {
    override val state = MutableStateFlow<MaterialSession?>(MaterialSession(TENANT, OutboxIdentity(USER, "device", "session"), fieldAllowed = true, readOnly = false))
    override val connectivity = MutableStateFlow(true)
    var value: MaterialSession?
        get() = state.value
        set(value) { state.value = value }
    var connected: Boolean
        get() = connectivity.value
        set(value) { connectivity.value = value }
}
private class MaterialRecords : SecureOutboxRecords {
    val rows = linkedMapOf<String, SecureOutboxRecord>()
    private fun id(record: SecureOutboxRecord) = "${record.operation.userId}:${record.operation.namespace}:${record.operation.key}"
    override fun entries() = rows.values.toList()
    override fun write(record: SecureOutboxRecord) { rows[id(record)] = record }
    override fun delete(userId: String) { rows.entries.removeAll { it.value.operation.userId == userId } }
    override fun retry(key: String) = rows.containsKey(key)
    override fun remove(key: String) { rows.remove(key) }
}
private object MaterialCipher : OutboxCipher {
    override fun encrypt(payload: ByteArray) = EncryptedBlob("test-only", payload.map { (it.toInt() xor 91).toByte() }.toByteArray())
    override fun decrypt(blob: EncryptedBlob) = blob.bytes.map { (it.toInt() xor 91).toByte() }.toByteArray()
}
private class MaterialHttp : MaterialHttpPort {
    val reads = mutableListOf<String>()
    val writes = mutableListOf<Triple<String, String, String>>()
    var context = contextJson(); var source = sourceJson()
    var afterGet: () -> Unit = {}
    var reply: (String) -> MaterialHttpResponse = { MaterialHttpResponse(200, """{"usageId":"$ISSUE","workOrderId":"$WORK","useRevision":1}""") }
    override suspend fun get(path: String, session: MaterialSession): MaterialHttpResponse {
        reads += path
        afterGet()
        return MaterialHttpResponse(200, when { path.endsWith("/custody/$SOURCE") -> source; path.endsWith("/issues/$ISSUE") -> issueJson(); else -> context })
    }
    override suspend fun post(path: String, body: String, idempotencyKey: String, session: MaterialSession): MaterialHttpResponse {
        writes += Triple(path, body, idempotencyKey)
        return reply(path)
    }
}
class MaterialRepositoryTest {
    @Test fun serialOnlyMetadataPreventsMeasuredUseAndPreservesLegacyGuardBytes() = runTest {
        val legacy = MaterialJson.context(contextJson(), WORK)
        val explicit = MaterialJson.context(contextJson().replace("\"latestUsageId\":null", "\"latestUsageId\":null,\"hasMeasuredMaterials\":true"), WORK)
        assertEquals(MaterialJson.fieldGuard(legacy), MaterialJson.fieldGuard(explicit))
        val http = MaterialHttp().apply { context = contextJson(use = 1).replace("\"latestUsageId\":null", "\"latestUsageId\":null,\"hasMeasuredMaterials\":false") }
        val records = MaterialRecords()
        val repository = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), MaterialTestSession()) { "serial-only" }
        val draft = reportDraft().copy(context = MaterialJson.context(http.context, WORK))
        assertFalse(draft.context.field!!.hasMeasuredMaterials)
        assertFalse(materialCanUse(draft.context, draft.lines.single().source))
        assertIs<MaterialDelivery.Rejected>(repository.submit(draft))
        assertTrue(http.writes.isEmpty()); assertTrue(records.rows.isEmpty())
    }
    @Test fun firstMeasuredUseAfterDeploymentRemainsReportUseWithCurrentPhysicalRevision() = runTest {
        val http = MaterialHttp().apply {
            context = contextJson(use = 1)
            reply = { MaterialHttpResponse(200, """{"usageId":"$ISSUE","workOrderId":"$WORK","useRevision":2}""") }
        }
        val repository = MaterialRepository(http, EncryptedOutbox(MaterialRecords(), MaterialCipher, USER), MaterialTestSession()) { "after-install" }
        val draft = reportDraft().copy(context = MaterialJson.context(http.context, WORK))
        assertIs<MaterialDelivery.Accepted>(repository.submit(draft))
        val write = http.writes.single()
        assertEquals("/api/work-orders/$WORK/materials/report-use", write.first)
        val body = MaterialJson.parse(write.second)
        assertEquals("1", body.getValue("expectedRevision").toString())
        assertEquals("3", body.getValue("planRevision").toString())
        assertFalse(body.containsKey("previousUsageId"))
    }
    @Test fun offlineIsPendingAndChangedSourceConflictsBeforePostingAfterRestart() = runTest {
        val records = MaterialRecords(); val session = MaterialTestSession(); val http = MaterialHttp()
        val outbox = EncryptedOutbox(records, MaterialCipher, USER)
        session.connected = false
        assertIs<MaterialDelivery.Pending>(MaterialRepository(http, outbox, session) { "op-1" }.submit(reportDraft()))
        assertEquals(0, http.writes.size); assertEquals(0, http.reads.size)
        assertFalse(records.rows.values.single().payload.bytes.decodeToString().contains("Ukuran lapangan"))
        val restored = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "never-new-key" }
        assertEquals(SecureDeliveryState.QUEUED, restored.pending().single().state)
        session.connected = true; http.source = sourceJson(8)
        assertIs<MaterialDelivery.Conflict>(restored.retry("op-1"))
        assertEquals(0, http.writes.size); assertEquals(SecureDeliveryState.CONFLICT, restored.pending().single().state)
        restored.discard("op-1"); assertTrue(restored.pending().isEmpty())
    }
    @Test fun responseLossAndRestartReplayExactBytesEvenAfterSourceWasConsumed() = runTest {
        val records = MaterialRecords(); val session = MaterialTestSession(); val http = MaterialHttp()
        http.reply = { throw IllegalStateException("connection lost after commit") }
        val first = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "stable-key" }
        val pending = assertIs<MaterialDelivery.Pending>(first.submit(reportDraft()))
        assertEquals(SecureDeliveryState.ATTEMPTED, pending.operation.state)
        assertFailsWith<IllegalArgumentException> { first.discard("stable-key") }
        http.context = contextJson(use = 1); http.source = "source no longer exists"
        http.reply = { MaterialHttpResponse(200, """{"usageId":"$ISSUE","workOrderId":"$WORK","useRevision":1}""") }
        val restarted = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { error("Must retain original operation identity") }
        assertEquals(1L, assertIs<MaterialDelivery.Accepted>(restarted.retry("stable-key")).revision)
        assertEquals(2, http.writes.size); assertEquals(http.writes[0], http.writes[1]); assertEquals(1, http.reads.count { it.endsWith("/custody/$SOURCE") })
        assertTrue(restarted.pending().isEmpty())
        assertTrue(http.writes.first().second.contains("\"quantityBase\":\"82500\""))
    }
    @Test fun partialReceiptUsesActualRevisionAndDifferenceAndNeverAcceptsAStockReservationOffline() = runTest {
        val records = MaterialRecords(); val session = MaterialTestSession(); val http = MaterialHttp()
        http.reply = { MaterialHttpResponse(200, """{"receiptId":"$ISSUE","revision":10,"state":"PART_RECEIVED"}""") }
        val draft = MaterialDraft.Acknowledge(MaterialJson.context(contextJson(), WORK), MaterialJson.issue(MaterialJson.parse(issueJson()), WORK),
            listOf(MaterialMeasuredReceipt(ISSUE, MaterialQuantity.base("60000"), MaterialQuantity.base("40000"), MaterialQuantity.base("0"), "Belum dikirim", null)), "Bukti penerimaan")
        val repository = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "receipt-key" }
        assertIs<MaterialDelivery.Accepted>(repository.submit(draft))
        val body = MaterialJson.parse(http.writes.single().second)
        assertEquals("9", body.getValue("expectedRevision").toString())
        assertTrue(body.getValue("lines").toString().contains("\"missingBase\":\"40000\""))
        assertEquals("/api/work-orders/$WORK/materials/acknowledge", http.writes.single().first)
    }
    @Test fun logoutDuringPreflightPurgesAndCannotSendUnderAnotherAccount() = runTest {
        val records = MaterialRecords(); val session = MaterialTestSession(); val http = MaterialHttp()
        val repository = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "op" }
        http.afterGet = { session.value = null }
        assertIs<MaterialDelivery.Rejected>(repository.submit(reportDraft()))
        assertTrue(records.rows.isEmpty()); assertTrue(http.writes.isEmpty())
    }
    @Test fun changedSessionOrTenantPurgesOldQueueAndReadOnlyCannotCreateAnIntent() = runTest {
        val records = MaterialRecords(); val session = MaterialTestSession(); val http = MaterialHttp()
        val repository = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "op" }
        session.connected = false; repository.submit(reportDraft())
        session.value = session.value!!.copy(tenantId = SOURCE, identity = session.value!!.identity.copy(sessionId = "new-session"))
        repository.sessionChanged(); assertTrue(repository.pending().isEmpty()); assertTrue(records.rows.isEmpty())
        session.value = session.value!!.copy(readOnly = true)
        assertIs<MaterialDelivery.Rejected>(repository.submit(reportDraft()))
        assertTrue(records.rows.isEmpty()); assertTrue(http.writes.isEmpty())
    }
    @Test fun serverConflictSurvivesRestartAndMalformedSuccessRemainsUncertain() = runTest {
        val records = MaterialRecords(); val session = MaterialTestSession(); val http = MaterialHttp()
        val repository = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "op" }
        http.reply = { MaterialHttpResponse(200, "{}") }
        assertEquals(SecureDeliveryState.ATTEMPTED, assertIs<MaterialDelivery.Pending>(repository.submit(reportDraft())).operation.state)
        http.reply = { MaterialHttpResponse(409, "{}") }
        assertIs<MaterialDelivery.Conflict>(repository.retry("op"))
        val restarted = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "never" }
        assertIs<MaterialDelivery.Conflict>(restarted.retry("op")); assertEquals(2, http.writes.size)
    }
    @Test fun sourceGatesRejectSerialConsumptionAndReceiptForAnotherTechnician() = runTest {
        val records = MaterialRecords(); val session = MaterialTestSession(); val http = MaterialHttp()
        val repository = MaterialRepository(http, EncryptedOutbox(records, MaterialCipher, USER), session) { "op" }
        val draft = reportDraft(); val original = draft.lines.single().source
        val serial = original.copy(sku = original.sku.copy(tracking = MaterialTracking.SERIAL, baseUnit = MaterialUnit.EA), quantityBase = MaterialQuantity.base("1"), baseUnit = MaterialUnit.EA, serial = "ONU-01")
        assertIs<MaterialDelivery.Rejected>(repository.submit(draft.copy(lines = listOf(MaterialMeasuredUse(serial, MaterialQuantity.base("1"))))))
        val issue = MaterialJson.issue(MaterialJson.parse(issueJson()), WORK).copy(receiver = MaterialPerson(SOURCE, "Teknisi lain"))
        assertIs<MaterialDelivery.Rejected>(repository.submit(MaterialDraft.Acknowledge(draft.context, issue, emptyList(), "Bukti")))
        assertTrue(records.rows.isEmpty()); assertTrue(http.writes.isEmpty())
    }
}
