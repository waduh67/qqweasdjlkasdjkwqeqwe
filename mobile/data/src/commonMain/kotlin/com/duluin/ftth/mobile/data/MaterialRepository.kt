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

data class MaterialHttpResponse(val status: Int, val body: String)
/** Platform HTTP binds credentials to this captured tenant/user/session, and preserves Idempotency-Key on refresh. */
interface MaterialHttpPort {
    suspend fun get(path: String, session: MaterialSession): MaterialHttpResponse
    suspend fun post(path: String, body: String, idempotencyKey: String, session: MaterialSession): MaterialHttpResponse
}
class MaterialHttpFailure(val status: Int) : IllegalStateException("Permintaan material ditolak ($status).")

/** Typed wire adapter plus durable, encrypted delivery. Only receipt and measured-use commands can enter this outbox. */
class MaterialRepository(
    private val http: MaterialHttpPort,
    private val outbox: SecureOutboxPort,
    private val sessions: MaterialSessionPort,
    private val operationKey: () -> String,
) : MaterialPort {
    private val mutex = Mutex()
    private var bound = sessions.current()

    override fun sessionChanged() {
        val current = sessions.current()
        val prior = bound
        if (prior != null && (prior.tenantId != current?.tenantId || prior.identity != current?.identity)) outbox.purge(prior.identity.userId)
        bound = current
    }
    private fun session(write: Boolean = false): MaterialSession {
        sessionChanged()
        return requireNotNull(sessions.current()) { "Sesi teknisi berakhir." }.also {
            require(it.fieldAllowed && (!write || !it.readOnly)) { "Akses material tidak tersedia untuk akun ini." }
            MaterialJson.uuid(it.tenantId)
        }
    }
    private fun checkSession(expected: MaterialSession, write: Boolean = false) {
        val current = session(write)
        require(current.tenantId == expected.tenantId && current.identity == expected.identity) { "Sesi berubah. Perintah akun lama tidak dikirim." }
    }
    private fun namespace(session: MaterialSession) = "materials.${session.tenantId}"
    private fun own(work: String) = "/api/v1/warehouse/my-materials/${MaterialJson.uuid(work)}"
    private suspend fun get(path: String, session: MaterialSession): String {
        checkSession(session)
        val result = http.get(path, session)
        checkSession(session)
        if (result.status != 200) throw MaterialHttpFailure(result.status)
        return result.body
    }
    override suspend fun jobs(page: Int): MaterialPage<MaterialJob> {
        require(page >= 0)
        return MaterialJson.page(get("/api/v1/warehouse/my-materials?page=$page&size=25", session()), MaterialJson::job)
    }
    override suspend fun workspace(workOrderId: String, issuePage: Int, custodyPage: Int): MaterialWorkspace {
        require(issuePage >= 0 && custodyPage >= 0)
        val current = session(); val path = own(workOrderId)
        val context = MaterialJson.context(get(path, current), workOrderId)
        val issues = MaterialJson.page(get("$path/issues?page=$issuePage&size=10", current)) { MaterialJson.issue(it, workOrderId) }
        val custody = MaterialJson.page(get("$path/custody?page=$custodyPage&size=25", current), MaterialJson::custody)
        return MaterialWorkspace(context, issues, custody)
    }

    override suspend fun submit(draft: MaterialDraft): MaterialDelivery = mutex.withLock {
        var key: String? = null
        try {
            val current = session(write = true); val prepared = prepareMaterial(draft, current)
            key = operationKey().also { require(it.matches(Regex("[!-~]{1,240}"))) }
            val envelope = buildJsonObject {
                put("version", 1); put("tenantId", current.tenantId); put("userId", current.identity.userId); put("deviceId", current.identity.deviceId); put("sessionId", current.identity.sessionId)
                put("workOrderId", draft.context.id); put("workOrderCode", draft.context.code); put("workOrderRevision", draft.context.workOrderRevision)
                put("kind", prepared.kind.name); put("body", prepared.body); put("guards", prepared.guards)
            }.toString().encodeToByteArray()
            val operation = SecureOutboxOperation(current.identity.userId, current.identity.deviceId, current.identity.sessionId, namespace(current), key, hash(envelope), draft.context.workOrderRevision, envelope)
            checkSession(current, write = true)
            require(outbox.enqueueSecure(operation) != EnqueueResult.Conflict) { "Identitas perintah sudah dipakai dengan isi berbeda." }
            deliver(key, current)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { MaterialDelivery.Rejected(key, failure.message ?: "Perintah belum dapat disimpan.") }
    }

    override suspend fun retry(key: String): MaterialDelivery = mutex.withLock {
        try { deliver(key, session(write = true)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { MaterialDelivery.Rejected(key, failure.message ?: "Perintah belum dapat dikirim.") }
    }
    override fun pending(): List<MaterialPending> {
        val current = session()
        return outbox.entries(current.identity, namespace(current)).map { entry -> summary(entry, envelope(entry, current)) }
    }
    override fun discard(key: String) {
        val current = session()
        val entry = outbox.entries(current.identity, namespace(current)).single { it.operation.key == key }
        require(entry.state != SecureDeliveryState.ATTEMPTED) { "Hasil belum pasti. Periksa ulang dengan perintah yang sama." }
        outbox.complete(current.identity, namespace(current), key)
    }
    private fun hash(bytes: ByteArray) = CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun envelope(entry: SecureOutboxEntry, current: MaterialSession): JsonObject {
        val op = entry.operation
        require(op.payloadHash == hash(op.payload)) { "Isi antrean tidak valid." }
        return MaterialJson.parse(op.payload.decodeToString()).also {
            require(it.number("version") == 1L && it.id("tenantId") == current.tenantId && it.text("userId") == current.identity.userId && it.text("deviceId") == current.identity.deviceId && it.text("sessionId") == current.identity.sessionId)
            require(it.number("workOrderRevision") == op.revision)
        }
    }
    private fun summary(entry: SecureOutboxEntry, value: JsonObject) = MaterialPending(entry.operation.key, value.id("workOrderId"), value.text("workOrderCode"), MaterialCommandKind.valueOf(value.text("kind")), entry.state)

    private suspend fun deliver(key: String, current: MaterialSession): MaterialDelivery {
        val entry = outbox.entries(current.identity, namespace(current)).single { it.operation.key == key }
        val value = envelope(entry, current); val pending = summary(entry, value)
        if (entry.state == SecureDeliveryState.CONFLICT) return MaterialDelivery.Conflict(key, "Perintah konflik. Muat ulang sumber dan buat draf baru.")
        if (entry.state == SecureDeliveryState.REJECTED) return MaterialDelivery.Rejected(key, "Perintah ditolak. Periksa akses dan buat draf baru.")
        if (!sessions.online()) return MaterialDelivery.Pending(pending)
        var attempted = entry.state == SecureDeliveryState.ATTEMPTED
        try {
            // A previously attempted command may already have consumed its source. Its exact replay is reauthorized by the server.
            val context = MaterialJson.context(get(own(pending.workOrderId), current), pending.workOrderId)
            if (!attempted) revalidate(value, pending.kind, context, current)
            checkSession(current, write = true)
            if (!sessions.online()) return MaterialDelivery.Pending(pending)
            outbox.mark(current.identity, namespace(current), key, SecureDeliveryState.ATTEMPTED)
            attempted = true
            val suffix = when (pending.kind) { MaterialCommandKind.ACKNOWLEDGE -> "acknowledge"; MaterialCommandKind.REPORT_USE -> "report-use"; MaterialCommandKind.CORRECT_USE -> "correct-use" }
            val result = http.post("/api/work-orders/${pending.workOrderId}/materials/$suffix", value.obj("body").toString(), key, current)
            checkSession(current, write = true)
            if (result.status in setOf(400, 401, 402, 403, 404, 409, 422)) throw MaterialHttpFailure(result.status)
            if (result.status !in setOf(200, 201)) return MaterialDelivery.Pending(pending.copy(state = SecureDeliveryState.ATTEMPTED))
            val received = MaterialJson.parse(result.body)
            val accepted = if (pending.kind == MaterialCommandKind.ACKNOWLEDGE) {
                require(received.text("state") in setOf("PART_RECEIVED", "RECEIVED"))
                MaterialDelivery.Accepted(key, received.id("receiptId"), received.number("revision"))
            } else {
                require(received.id("workOrderId") == pending.workOrderId)
                MaterialDelivery.Accepted(key, received.id("usageId"), received.number("useRevision"))
            }
            outbox.complete(current.identity, namespace(current), key)
            return accepted
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            checkSession(current, write = true)
            val terminal = failure is MaterialHttpFailure && failure.status in setOf(400, 401, 402, 403, 404, 409, 422)
            if (terminal || (!attempted && failure is IllegalArgumentException)) {
                val conflict = failure !is MaterialHttpFailure || failure.status == 409
                outbox.mark(current.identity, namespace(current), key, if (conflict) SecureDeliveryState.CONFLICT else SecureDeliveryState.REJECTED)
                return if (conflict) MaterialDelivery.Conflict(key, failure.message ?: "Sumber berubah.") else MaterialDelivery.Rejected(key, failure.message ?: "Akses ditolak.")
            }
            return MaterialDelivery.Pending(pending.copy(state = if (attempted) SecureDeliveryState.ATTEMPTED else SecureDeliveryState.QUEUED))
        }
    }
    private suspend fun revalidate(value: JsonObject, kind: MaterialCommandKind, context: MaterialContext, current: MaterialSession) {
        require(context.currentAssignee && context.active && context.workOrderRevision == value.number("workOrderRevision")) { "Penugasan atau revisi WO berubah." }
        val guards = value.obj("guards")
        if (kind == MaterialCommandKind.ACKNOWLEDGE) {
            val id = guards.id("issueId")
            val issue = MaterialJson.issue(MaterialJson.parse(get("${own(context.id)}/issues/$id", current)), context.id)
            require(issue.id == id && issue.receiver.id == current.identity.userId && issue.revision == guards.number("issueRevision") && issue.workOrderRevision == context.workOrderRevision) { "Pengiriman berubah sejak draf dibuat." }
        } else {
            require(MaterialJson.fieldGuard(context) == guards.obj("field")) { "Rencana atau pemakaian berubah sejak draf dibuat." }
            for (source in guards.rows("sources")) {
                val fresh = MaterialJson.custody(MaterialJson.parse(get("${own(context.id)}/custody/${source.id("id")}", current)))
                require(materialCanUse(context, fresh) && MaterialJson.sourceGuard(fresh) == source) { "Sisa material berubah sejak draf dibuat." }
            }
        }
    }
}
