package com.duluin.ftth.mobile.domain

data class TechnicianAccount(val id: String, val tenantId: String, val name: String, val email: String, val permissions: Set<String>)
data class TechnicianSession(val server: String, val identity: OutboxIdentity, val account: TechnicianAccount, val accessToken: String, val refreshToken: String)
interface TechnicianCredentialStore { fun read(): String?; fun write(value: String); fun clear() }
data class FieldHttpResponse(val status: Int, val body: String)
interface FieldHttpTransport {
    suspend fun send(server: String, path: String, token: String? = null, body: String? = null, key: String? = null, evidence: FieldEvidence? = null): FieldHttpResponse
}
enum class FieldWorkState { PENDING, BLOCKED, COMPLETED, CANCELLED }
data class FieldWorkType(val name: String, val materialRequired: Boolean, val photoSlots: List<String>)
data class FieldWork(val id: String, val code: String, val title: String, val description: String, val revision: Long,
    val technicianId: String, val assignmentGeneration: Long, val state: FieldWorkState, val type: FieldWorkType, val scheduledAt: String?)
data class FieldPhoto(val id: String, val slot: String, val assignmentGeneration: Long, val current: Boolean)
data class FieldActivity(val action: String, val actor: String, val notes: String, val recordedAt: String)
data class FieldWorkDetail(val work: FieldWork, val overdue: Boolean, val timeline: List<FieldActivity>)
data class FieldStock(val id: String, val sku: MaterialSku, val quantity: MaterialQuantity, val revision: Long,
    val locationName: String, val holderId: String, val status: String, val serial: String?, val mac: String?)
data class FieldWarehouse(val id: String, val name: String, val kind: String, val active: Boolean)
data class FieldRequestLine(val name: String, val unit: MaterialUnit, val requested: MaterialQuantity, val approved: MaterialQuantity,
    val received: MaterialQuantity, val fulfilled: MaterialQuantity)
data class FieldRequest(val id: String, val reason: String, val state: String, val requesterName: String, val lines: List<FieldRequestLine>)
data class FieldReturn(val id: String, val reason: String, val state: String, val skuName: String, val unit: MaterialUnit,
    val quantity: MaterialQuantity, val warehouseName: String)
data class FieldMaterialUse(val source: FieldStock, val quantity: MaterialQuantity)
data class FieldEvidence(val slot: String, val contentType: String, val bytes: ByteArray) {
    init { require(contentType in setOf("image/jpeg", "image/png", "image/webp") && bytes.size in 1..5 * 1024 * 1024) { "Foto JPG, PNG atau WebP harus berukuran paling banyak 5 MB." } }
}
enum class FieldCommandKind { REQUEST, RETURN, PHOTO, COMPLETE, PROGRESS }
data class FieldPending(val key: String, val kind: FieldCommandKind, val label: String, val state: SecureDeliveryState)
sealed interface FieldDelivery {
    data class Accepted(val key: String) : FieldDelivery
    data class Pending(val command: FieldPending) : FieldDelivery
    data class Failed(val key: String, val message: String, val conflict: Boolean) : FieldDelivery
}

fun validateFieldCompletion(work: FieldWork, userId: String, photos: List<FieldPhoto>, materials: List<FieldMaterialUse>) {
    require(work.technicianId == userId && work.state in setOf(FieldWorkState.PENDING, FieldWorkState.BLOCKED)) { "WO sudah ditutup atau penugasan berubah." }
    val slots = photos.filter { it.current && it.assignmentGeneration == work.assignmentGeneration }.map { it.slot }.toSet()
    require(slots.containsAll(work.type.photoSlots)) { "Lengkapi foto wajib: ${work.type.photoSlots.filterNot(slots::contains).joinToString()}." }
    require(!work.type.materialRequired || materials.isNotEmpty()) { "Pekerjaan ini wajib mencatat material." }
    require(materials.map { it.source.id }.toSet().size == materials.size) { "Material yang sama dipilih dua kali." }
    materials.forEach { validateFieldUse(it, userId) }
}

fun validateFieldUse(use: FieldMaterialUse, userId: String) {
    require(use.source.holderId == userId && use.source.status == "ISSUED") { "Material tidak tersedia pada teknisi ini." }
    require(use.quantity.value in 1..use.source.quantity.value) { "Jumlah melebihi stok yang tersedia." }
    require(use.source.sku.tracking != MaterialTracking.SERIAL || use.quantity.value == 1L) { "Perangkat serial harus satu unit." }
}
