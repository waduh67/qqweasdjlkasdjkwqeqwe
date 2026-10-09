package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.storage.EncryptedOutbox
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ReferenceTechnicianRepositoryTest {
    @Test fun changedStockRevisionConflictsBeforeSendingQueuedReturn() = runTest {
        val records = FieldRecords(); val http = FieldTransport(); val outbox = EncryptedOutbox(records, FieldCipher)
        val auth = TechnicianAuthentication(http, FieldCredentials(), { "identity" }, outbox::purge)
        auth.login("http://localhost", "budi@example.test", "password")
        val repository = ReferenceTechnicianRepository(auth, outbox, { "return-key" })
        val stock = fieldStock()
        repository.queueReturn(2, FieldWarehouse(ISSUE, "Gudang Utama", "WAREHOUSE", true),
            FieldMaterialUse(stock, MaterialQuantity.base("12501")), "Sisa pemasangan")
        http.response = { call -> FieldHttpResponse(200, if (call.path.endsWith("/workflow"))
            """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":2}}""" else
            """{"items":[{"stockIdentityId":"$SOURCE","revision":8,"quantityBase":"100000","locationName":"Tas Budi","holderId":"$USER","status":"ISSUED","serial":null,"mac":null,"skuId":"$ISSUE","skuCode":"DROP","skuName":"Kabel drop","tracking":"LOT","baseUnit":"MM"}],"page":0,"size":25,"totalElements":1}""") }
        val result = assertIs<FieldDelivery.Failed>(repository.deliver("return-key"))
        assertTrue(result.conflict)
        assertEquals("Stok berubah sejak draf dibuat.", result.message)
        assertEquals(SecureDeliveryState.CONFLICT, repository.pending().single().state)
        assertTrue(http.calls.none { it.key != null })
    }
    @Test fun uncertainPhotoReplaysOriginalBytesDespiteLaterAssignmentChanges() = runTest {
        val records = FieldRecords(); val credentials = FieldCredentials(); val http = FieldTransport()
        val outbox = EncryptedOutbox(records, FieldCipher)
        val auth = TechnicianAuthentication(http, credentials, { "identity" }, outbox::purge)
        auth.login("http://localhost", "budi@example.test", "password")
        val repository = ReferenceTechnicianRepository(auth, outbox, { "photo-key" })
        val original = byteArrayOf(-1, -40, -1, 12, 34, 56, -1, -39)
        repository.queuePhoto(2, fieldWork(), FieldEvidence("Bukti Pasang", "image/jpeg", original))
        original.fill(0)
        http.response = { call -> when {
            call.path.endsWith("/workflow") -> FieldHttpResponse(200, """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":2}}""")
            call.path == "/api/v2/work-orders/$WORK" -> FieldHttpResponse(200, fieldDetail())
            else -> throw IllegalStateException("response lost after evidence upload")
        } }
        assertEquals(SecureDeliveryState.ATTEMPTED, assertIs<FieldDelivery.Pending>(repository.deliver("photo-key")).command.state)
        val restartedAuth = TechnicianAuthentication(http, credentials, { error("Retain identity") }, outbox::purge)
        val restarted = ReferenceTechnicianRepository(restartedAuth, EncryptedOutbox(records, FieldCipher), { error("Retain key") })
        http.response = { call -> FieldHttpResponse(200, when {
            call.path.endsWith("/workflow") -> """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":2}}"""
            call.path == "/api/v2/work-orders/$WORK" -> fieldDetail(fieldWork().copy(revision = 6, assignmentGeneration = 3))
            else -> """{"id":"$WORK"}"""
        }) }
        assertIs<FieldDelivery.Accepted>(restarted.deliver("photo-key"))
        val writes = http.calls.filter { it.key == "photo-key" }
        assertEquals(2, writes.size); assertEquals(writes[0].body, writes[1].body)
        assertEquals(writes[0].path, writes[1].path); assertEquals(writes[0].evidence?.slot, writes[1].evidence?.slot)
        assertContentEquals(byteArrayOf(-1, -40, -1, 12, 34, 56, -1, -39), requireNotNull(writes[1].evidence).bytes)
        assertTrue(restarted.pending().isEmpty())
    }
    @Test fun reassignedWorkRejectsUnsentPhotoEvenWhenRevisionIsUnchanged() = runTest {
        val records = FieldRecords(); val http = FieldTransport(); val outbox = EncryptedOutbox(records, FieldCipher)
        val auth = TechnicianAuthentication(http, FieldCredentials(), { "identity" }, outbox::purge)
        auth.login("http://localhost", "budi@example.test", "password")
        val repository = ReferenceTechnicianRepository(auth, outbox, { "photo-key" })
        repository.queuePhoto(2, fieldWork(), FieldEvidence("Bukti Pasang", "image/jpeg", byteArrayOf(1, 2, 3)))
        http.response = { call -> FieldHttpResponse(200, if (call.path.endsWith("/workflow"))
            """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":2}}""" else fieldDetail(fieldWork().copy(assignmentGeneration = 3))) }
        assertTrue(assertIs<FieldDelivery.Failed>(repository.deliver("photo-key")).conflict)
        assertTrue(http.calls.none { it.key != null })
    }
    @Test fun uncertainDeliveryReplaysExactRequestAfterRestoredSession() = runTest {
        val records = FieldRecords(); val credentials = FieldCredentials(); val http = FieldTransport()
        val outbox = EncryptedOutbox(records, FieldCipher)
        val auth = TechnicianAuthentication(http, credentials, { "identity" }, outbox::purge)
        auth.login("http://localhost:8080", "budi@example.test", "password")
        val repository = ReferenceTechnicianRepository(auth, outbox, { "request-key" })
        repository.queueRequest(2, fieldStock().sku, "", MaterialUnit.MM, MaterialQuantity.measured("82.501", MaterialUnit.MM), "Perlu kabel", false)
        http.response = { call -> when {
            call.path.endsWith("/workflow") -> FieldHttpResponse(200, """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":2}}""")
            else -> throw IllegalStateException("connection lost after commit")
        } }
        assertEquals(SecureDeliveryState.ATTEMPTED, assertIs<FieldDelivery.Pending>(repository.deliver("request-key")).command.state)
        assertFailsWith<IllegalArgumentException> { repository.discard("request-key") }
        assertFalse(records.rows.values.single().payload.bytes.decodeToString().contains("Perlu kabel"))
        val restoredAuth = TechnicianAuthentication(http, credentials, { error("Must restore session") }, outbox::purge)
        val restarted = ReferenceTechnicianRepository(restoredAuth, EncryptedOutbox(records, FieldCipher), { error("Must retain key") })
        http.response = { call -> FieldHttpResponse(200, if (call.path.endsWith("/workflow")) """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":2}}""" else """{"id":"$WORK"}""") }
        assertIs<FieldDelivery.Accepted>(restarted.deliver("request-key"))
        val writes = http.calls.filter { it.key == "request-key" }
        assertEquals(2, writes.size); assertEquals(writes[0], writes[1])
        val line = MaterialJson.parse(requireNotNull(writes.first().body)).getValue("lines").jsonArray.single().jsonObject
        assertEquals("82501", line.getValue("requestedBase").jsonPrimitive.content)
        assertTrue(restarted.pending().isEmpty())
    }
    @Test fun changedWorkflowConflictsBeforeSendingQueuedRequest() = runTest {
        val records = FieldRecords(); val outbox = EncryptedOutbox(records, FieldCipher); val http = FieldTransport()
        val auth = TechnicianAuthentication(http, FieldCredentials(), { "identity" }, outbox::purge)
        auth.login("http://localhost", "budi@example.test", "password")
        val repository = ReferenceTechnicianRepository(auth, outbox, { "key" })
        repository.queueRequest(2, fieldStock().sku, "", MaterialUnit.MM, MaterialQuantity.base("1234"), "Restok", false)
        http.response = { FieldHttpResponse(200, """{"snapshot":{"workflow":"REFERENCE","tenantId":"$TENANT","epoch":3}}""") }
        assertTrue(assertIs<FieldDelivery.Failed>(repository.deliver("key")).conflict)
        assertTrue(http.calls.none { it.key != null })
        assertEquals(SecureDeliveryState.CONFLICT, repository.pending().single().state)
    }
    @Test fun issuedStockCanCompleteButWarehouseStockAndOldPhotosCannot() {
        val work = fieldWork(); val use = FieldMaterialUse(fieldStock(), MaterialQuantity.measured("82.501", MaterialUnit.MM))
        val photos = work.type.photoSlots.map { FieldPhoto(ISSUE, it, work.assignmentGeneration, true) }
        validateFieldCompletion(work, USER, photos, listOf(use))
        assertFailsWith<IllegalArgumentException> { validateFieldCompletion(work, USER, photos.map { it.copy(assignmentGeneration = 1) }, listOf(use)) }
        assertFailsWith<IllegalArgumentException> { validateFieldCompletion(work, USER, photos, emptyList()) }
        assertFailsWith<IllegalArgumentException> { validateFieldUse(use.copy(source = use.source.copy(status = "AVAILABLE")), USER) }
        assertFailsWith<IllegalArgumentException> { validateFieldUse(use.copy(quantity = MaterialQuantity.base("100001")), USER) }
    }
}
