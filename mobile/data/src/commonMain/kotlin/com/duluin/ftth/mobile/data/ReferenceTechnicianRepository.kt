package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.data.MaterialJson.id
import com.duluin.ftth.mobile.data.MaterialJson.number
import com.duluin.ftth.mobile.data.MaterialJson.obj
import com.duluin.ftth.mobile.data.MaterialJson.rows
import com.duluin.ftth.mobile.data.MaterialJson.text
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class ReferenceTechnicianRepository(
    val auth: TechnicianAuthentication, private val outbox: SecureOutboxPort, private val newKey: () -> String,
    private val online: () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val root = "/api/v2/warehouse"
    private fun namespace(session: TechnicianSession) = "reference.${session.account.tenantId}"
    private suspend fun get(path: String, session: TechnicianSession = auth.capture()): String {
        val response = auth.request(path, expected = session); ensureSuccess(response); return response.body
    }
    suspend fun workflow(): Long {
        val row = MaterialJson.parse(get("$root/workflow")).obj("snapshot")
        require(row.text("workflow") == "REFERENCE") { "Gudang tenant belum menggunakan alur baru. Hubungi Admin." }
        require(row.id("tenantId") == auth.capture().account.tenantId)
        return row.number("epoch")
    }
    suspend fun work(page: Int = 0, search: String = "") = MaterialJson.page(get("/api/v2/work-orders?page=$page&size=25&search=${queryValue(search)}"), FieldJson::work)
    suspend fun workDetail(id: String) = FieldJson.detail(get("/api/v2/work-orders/${MaterialJson.uuid(id)}"))
    suspend fun photos(id: String) = Json.parseToJsonElement(get("/api/v2/work-orders/${MaterialJson.uuid(id)}/evidence")).jsonArray.map { FieldJson.photo(it.jsonObject) }
    suspend fun materials(page: Int = 0, search: String = "") = MaterialJson.page(get("$root/my-materials?page=$page&size=25&search=${queryValue(search)}"), FieldJson::stock)
    suspend fun skus(page: Int = 0, search: String = "") = MaterialJson.page(get("$root/skus?page=$page&size=25&search=${queryValue(search)}"), MaterialJson::sku)
    suspend fun warehouses(page: Int = 0, search: String = "") = MaterialJson.page(get("$root/locations?page=$page&size=25&search=${queryValue(search)}"), FieldJson::warehouse)
    suspend fun requests(page: Int = 0, search: String = "") = MaterialJson.page(get("$root/requests?page=$page&size=25&search=${queryValue(search)}"), FieldJson::request)
    suspend fun returns(page: Int = 0, search: String = "") = MaterialJson.page(get("$root/returns?page=$page&size=25&search=${queryValue(search)}"), FieldJson::returned)

    fun queueRequest(epoch: Long, sku: MaterialSku?, proposedName: String, unit: MaterialUnit, quantity: MaterialQuantity, reason: String, procurement: Boolean): FieldPending {
        val session = auth.capture()
        require(reason.isNotBlank() && quantity.value > 0 && (sku != null || procurement && proposedName.isNotBlank())) { "Isi material, jumlah dan alasan permintaan." }
        require(sku == null || sku.baseUnit == unit)
        val body = buildJsonObject {
            put("kind", if (procurement) "PROCUREMENT" else "RESTOCK"); put("reason", reason); put("technicianId", session.account.id)
            put("lines", buildJsonArray { add(buildJsonObject {
                put("baseUnit", unit.name); put("requestedBase", quantity.base)
                if (sku != null) put("skuId", sku.id) else put("proposedName", proposedName.trim())
            }) })
        }
        return queue(FieldCommandKind.REQUEST, "Permintaan ${sku?.name ?: proposedName}", "$root/requests", body, epoch, 0, guards(null, emptyList()))
    }
    fun queueReturn(epoch: Long, warehouse: FieldWarehouse, use: FieldMaterialUse, reason: String): FieldPending {
        validateFieldUse(use, auth.capture().account.id)
        require(reason.isNotBlank() && warehouse.active && warehouse.kind == "WAREHOUSE") { "Pilih gudang aktif dan isi alasan retur." }
        val body = buildJsonObject {
            put("warehouseId", MaterialJson.uuid(warehouse.id)); put("skuId", use.source.sku.id); put("reason", reason)
            put("lines", uses(listOf(use)))
        }
        return queue(FieldCommandKind.RETURN, "Retur ${use.source.sku.name}", "$root/returns", body, epoch, use.source.revision, guards(null, listOf(use)))
    }
    fun queuePhoto(epoch: Long, work: FieldWork, evidence: FieldEvidence): FieldPending {
        require(work.technicianId == auth.capture().account.id && work.state in setOf(FieldWorkState.PENDING, FieldWorkState.BLOCKED) && evidence.slot in work.type.photoSlots) { "Slot foto atau penugasan tidak sesuai." }
        val body = buildJsonObject { put("expectedRevision", work.revision); put("slot", evidence.slot) }
        return queue(FieldCommandKind.PHOTO, "${work.code}: ${evidence.slot}", "/api/v2/work-orders/${work.id}/evidence", body, epoch, work.revision, guards(work, emptyList()), evidence)
    }
    fun queueCompletion(epoch: Long, work: FieldWork, photos: List<FieldPhoto>, materials: List<FieldMaterialUse>, notes: String): FieldPending {
        validateFieldCompletion(work, auth.capture().account.id, photos, materials)
        val body = buildJsonObject { put("expectedRevision", work.revision); put("notes", notes); put("materials", uses(materials)) }
        return queue(FieldCommandKind.COMPLETE, "Selesaikan ${work.code}", "/api/v2/work-orders/${work.id}/complete", body, epoch, work.revision, guards(work, materials))
    }
    fun queueProgress(epoch: Long, work: FieldWork, notes: String, blocked: Boolean): FieldPending {
        require(work.technicianId == auth.capture().account.id && work.state in setOf(FieldWorkState.PENDING, FieldWorkState.BLOCKED))
        require(!blocked || notes.isNotBlank()) { "Isi kendala pekerjaan." }
        val body = buildJsonObject { put("expectedRevision", work.revision); put("notes", notes); put("state", if (blocked) "BLOCKED" else "PENDING") }
        return queue(FieldCommandKind.PROGRESS, "Perbarui ${work.code}", "/api/v2/work-orders/${work.id}/progress", body, epoch, work.revision, guards(work, emptyList()))
    }
    private fun queue(kind: FieldCommandKind, label: String, path: String, body: JsonObject, epoch: Long, revision: Long, guards: JsonObject, evidence: FieldEvidence? = null): FieldPending {
        val session = auth.capture(); val key = newKey()
        require(key.matches(Regex("[!-~]{1,240}")) && epoch >= 0)
        val envelope = buildJsonObject {
            put("version", 1); put("kind", kind.name); put("label", label); put("path", path); put("epoch", epoch)
            put("tenantId", session.account.tenantId); put("server", session.server); put("body", body); put("guards", guards)
            evidence?.let { put("photo", buildJsonObject { put("slot", it.slot); put("contentType", it.contentType); put("hex", it.bytes.hex()) }) }
        }.toString().encodeToByteArray()
        val operation = SecureOutboxOperation(session.account.id, session.identity.deviceId, session.identity.sessionId, namespace(session), key, digest(envelope), revision, envelope)
        auth.check(session)
        require(outbox.enqueueSecure(operation) != EnqueueResult.Conflict) { "Perintah sudah dipakai dengan isi berbeda." }
        return FieldPending(key, kind, label, SecureDeliveryState.QUEUED)
    }
    fun pending(): List<FieldPending> {
        val session = auth.capture()
        return outbox.entries(session.identity, namespace(session)).map { summary(it, envelope(it, session)) }
    }
    fun discard(key: String) {
        val session = auth.capture(); val entry = outbox.entries(session.identity, namespace(session)).single { it.operation.key == key }
        require(entry.state != SecureDeliveryState.ATTEMPTED) { "Hasil belum pasti. Kirim ulang perintah yang sama untuk memastikan." }
        outbox.complete(session.identity, namespace(session), key)
    }
    suspend fun deliver(key: String): FieldDelivery = mutex.withLock {
        val session = auth.capture(); val ns = namespace(session)
        val entry = outbox.entries(session.identity, ns).single { it.operation.key == key }
        val value = envelope(entry, session); val pending = summary(entry, value)
        if (entry.state in setOf(SecureDeliveryState.CONFLICT, SecureDeliveryState.REJECTED)) return@withLock FieldDelivery.Failed(key, "Perintah ditolak. Hapus draf ini lalu muat ulang sumber.", entry.state == SecureDeliveryState.CONFLICT)
        if (!online()) return@withLock FieldDelivery.Pending(pending)
        var attempted = entry.state == SecureDeliveryState.ATTEMPTED
        try {
            require(workflow() == value.number("epoch")) { "Alur gudang berubah. Muat ulang sebelum membuat draf baru." }
            auth.check(session)
            if (!attempted) validate(value, pending.kind, session)
            if (!online()) return@withLock FieldDelivery.Pending(pending)
            auth.check(session)
            val evidence = value["photo"]?.jsonObject?.let { FieldEvidence(it.text("slot"), it.text("contentType"), it.text("hex").unhex()) }
            outbox.mark(session.identity, ns, key, SecureDeliveryState.ATTEMPTED); attempted = true
            val result = auth.request(value.text("path"), value.obj("body").toString(), key, evidence, session)
            ensureSuccess(result)
            val resultId = MaterialJson.parse(result.body).id("id")
            if (pending.kind in setOf(FieldCommandKind.COMPLETE, FieldCommandKind.PHOTO, FieldCommandKind.PROGRESS)) require(resultId == value.obj("guards").id("workId"))
            auth.check(session); outbox.complete(session.identity, ns, key)
            FieldDelivery.Accepted(key)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            auth.check(session)
            val terminal = failure is FieldApiFailure && failure.status in 400..499 || !attempted && failure is IllegalArgumentException
            if (terminal) {
                val conflict = failure !is FieldApiFailure || failure.status == 409
                outbox.mark(session.identity, ns, key, if (conflict) SecureDeliveryState.CONFLICT else SecureDeliveryState.REJECTED)
                FieldDelivery.Failed(key, failure.message ?: "Perintah ditolak.", conflict)
            } else FieldDelivery.Pending(pending.copy(state = if (attempted) SecureDeliveryState.ATTEMPTED else SecureDeliveryState.QUEUED))
        }
    }
    private suspend fun validate(value: JsonObject, kind: FieldCommandKind, session: TechnicianSession) {
        val guard = value.obj("guards"); val uses = guard.rows("sources")
        val sources = mutableListOf<FieldMaterialUse>()
        if (uses.isNotEmpty()) {
            val remaining = uses.associateBy { it.id("id") }.toMutableMap(); var page = 0
            do {
                val current = materials(page++)
                current.items.forEach { source -> remaining.remove(source.id)?.let { saved ->
                    require(source.revision == saved.number("revision") && source.sku.id == saved.id("skuId") && source.sku.baseUnit.name == saved.text("baseUnit")) { "Stok berubah sejak draf dibuat." }
                    val use = FieldMaterialUse(source, MaterialQuantity.base(saved.text("quantityBase"))); validateFieldUse(use, session.account.id); sources.add(use)
                } }
            } while (remaining.isNotEmpty() && page.toLong() * 25 < current.totalElements)
            require(remaining.isEmpty()) { "Material tidak tersedia lagi pada teknisi ini." }
        }
        guard["workId"]?.let {
            val work = workDetail(it.jsonPrimitive.content).work
            require(work.revision == guard.number("revision") && work.assignmentGeneration == guard.number("generation") && work.technicianId == session.account.id && work.state in setOf(FieldWorkState.PENDING, FieldWorkState.BLOCKED)) { "Revisi atau penugasan WO berubah." }
            if (kind == FieldCommandKind.COMPLETE) validateFieldCompletion(work, session.account.id, photos(work.id), sources)
        }
        auth.check(session)
    }
    private fun guards(work: FieldWork?, materials: List<FieldMaterialUse>) = buildJsonObject {
        work?.let { put("workId", it.id); put("revision", it.revision); put("generation", it.assignmentGeneration) }
        put("sources", buildJsonArray { materials.forEach { use -> add(buildJsonObject {
            put("id", use.source.id); put("skuId", use.source.sku.id); put("baseUnit", use.source.sku.baseUnit.name)
            put("revision", use.source.revision); put("quantityBase", use.quantity.base)
        }) } })
    }
    private fun uses(materials: List<FieldMaterialUse>) = buildJsonArray { materials.forEach { add(buildJsonObject { put("stockIdentityId", it.source.id); put("quantityBase", it.quantity.base) }) } }
    private fun envelope(entry: SecureOutboxEntry, session: TechnicianSession) = MaterialJson.parse(entry.operation.payload.decodeToString()).also {
        require(digest(entry.operation.payload) == entry.operation.payloadHash && it.number("version") == 1L && it.id("tenantId") == session.account.tenantId && it.text("server") == session.server)
    }
    private fun summary(entry: SecureOutboxEntry, row: JsonObject) = FieldPending(entry.operation.key, FieldCommandKind.valueOf(row.text("kind")), row.text("label"), entry.state)
}

private fun digest(bytes: ByteArray) = CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(bytes).hex()
private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
private fun String.unhex(): ByteArray { require(length % 2 == 0 && length <= 10 * 1024 * 1024); return chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
private fun queryValue(value: String) = value.encodeToByteArray().joinToString("") { byte ->
    val number = byte.toInt() and 255
    if (number in 65..90 || number in 97..122 || number in 48..57 || number in listOf(45, 46, 95, 126)) number.toChar().toString() else "%" + number.toString(16).padStart(2, '0')
}
