package com.duluin.ftth.mobile.domain

import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.StateFlow

enum class MaterialUnit { EA, MM }
enum class MaterialTracking { SERIAL, LOT, BULK }
enum class MaterialMode { NONE, MATERIAL_REQUIRED }

/** Millimetres and whole units cross the wire as checked integer strings, never floating point. */
@JvmInline
value class MaterialQuantity private constructor(val base: String) {
    val value: Long get() = base.toLong()
    fun display(unit: MaterialUnit): String = if (unit == MaterialUnit.EA) base else {
        val padded = base.padStart(4, '0')
        padded.dropLast(3) + "." + padded.takeLast(3)
    }
    companion object {
        fun base(input: String): MaterialQuantity {
            require(input.matches(Regex("0|[1-9][0-9]*")) && input.toLongOrNull() != null) { "Jumlah harus bilangan dasar nonnegatif dalam batas 64 bit." }
            return MaterialQuantity(input)
        }
        fun measured(input: String, unit: MaterialUnit, zero: Boolean = false): MaterialQuantity {
            val normalized = input.trim().replace(',', '.')
            val pattern = if (unit == MaterialUnit.MM) "[0-9]+(\\.[0-9]{1,3})?" else "[0-9]+"
            require(normalized.matches(Regex(pattern))) { "Jumlah unit harus bulat; meter paling banyak tiga desimal." }
            val raw = if (unit == MaterialUnit.MM) normalized.substringBefore('.') + normalized.substringAfter('.', "").padEnd(3, '0') else normalized
            return base(raw.trimStart('0').ifEmpty { "0" }).also { require(zero || it.value > 0) { "Jumlah harus lebih dari nol." } }
        }
    }
}

data class MaterialSession(val tenantId: String, val identity: OutboxIdentity, val fieldAllowed: Boolean, val readOnly: Boolean)
interface MaterialSessionPort {
    val state: StateFlow<MaterialSession?>
    val connectivity: StateFlow<Boolean>
    fun current(): MaterialSession? = state.value
    fun online(): Boolean = connectivity.value
}
data class MaterialPage<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long)
data class MaterialJob(val id: String, val code: String, val updatedAt: String)
data class MaterialPerson(val id: String, val name: String)
data class MaterialSku(val id: String, val code: String, val name: String, val tracking: MaterialTracking, val baseUnit: MaterialUnit)
data class MaterialLocation(val id: String, val code: String, val name: String?)
data class MaterialField(val planId: String?, val planRevision: Long?, val mode: MaterialMode?, val planState: String?, val useRevision: Long,
    val latestUsageId: String?, val reworkId: String?, val evidenceRevision: String?)
data class MaterialContext(val id: String, val code: String, val workOrderRevision: Long, val currentAssignee: Boolean, val active: Boolean,
    val technicalState: String, val qaState: String?, val field: MaterialField?)
data class MaterialIssueLine(val id: String, val stockIdentityId: String, val sku: MaterialSku, val baseUnit: MaterialUnit,
    val dispatchedBase: MaterialQuantity, val acceptedBase: MaterialQuantity, val remainingBase: MaterialQuantity, val serial: String?, val lotCode: String?)
data class MaterialIssue(val id: String, val code: String, val workOrderId: String, val workOrderRevision: Long, val revision: Long, val state: String,
    val sender: MaterialPerson, val receiver: MaterialPerson, val lines: List<MaterialIssueLine>)
data class MaterialCustody(val id: String, val receiptId: String, val issueId: String, val issueCode: String, val issueLineId: String,
    val planId: String, val planLineId: String, val sku: MaterialSku, val sourceUsageId: String?, val quantityBase: MaterialQuantity,
    val baseUnit: MaterialUnit, val stockRevision: Long, val location: MaterialLocation, val serial: String?, val lotCode: String?, val initialUseSource: Boolean)
data class MaterialWorkspace(val context: MaterialContext, val issues: MaterialPage<MaterialIssue>, val custody: MaterialPage<MaterialCustody>)
data class MaterialMeasuredUse(val source: MaterialCustody, val quantityBase: MaterialQuantity)
data class MaterialMeasuredReceipt(val lineId: String, val acceptedBase: MaterialQuantity, val missingBase: MaterialQuantity,
    val rejectedBase: MaterialQuantity, val reason: String?, val observedSerial: String?)

sealed interface MaterialDraft {
    val context: MaterialContext
    val evidenceReference: String
    data class ReportUse(override val context: MaterialContext, val lines: List<MaterialMeasuredUse>, override val evidenceReference: String, val reason: String?) : MaterialDraft
    data class Acknowledge(override val context: MaterialContext, val issue: MaterialIssue, val lines: List<MaterialMeasuredReceipt>, override val evidenceReference: String) : MaterialDraft
}

enum class MaterialCommandKind { REPORT_USE, CORRECT_USE, ACKNOWLEDGE }
data class MaterialPending(val key: String, val workOrderId: String, val workOrderCode: String, val kind: MaterialCommandKind, val state: SecureDeliveryState)
sealed interface MaterialDelivery {
    data class Pending(val operation: MaterialPending) : MaterialDelivery
    data class Accepted(val key: String, val documentId: String, val revision: Long) : MaterialDelivery
    data class Conflict(val key: String, val message: String) : MaterialDelivery
    data class Rejected(val key: String?, val message: String) : MaterialDelivery
}

interface MaterialPort {
    suspend fun jobs(page: Int = 0): MaterialPage<MaterialJob>
    suspend fun workspace(workOrderId: String, issuePage: Int = 0, custodyPage: Int = 0): MaterialWorkspace
    suspend fun submit(draft: MaterialDraft): MaterialDelivery
    suspend fun retry(key: String): MaterialDelivery
    fun pending(): List<MaterialPending>
    fun discard(key: String)
    /** Called before displaying a changed account, including logout. Purges the prior user's queued work. */
    fun sessionChanged()
}

fun materialCanUse(context: MaterialContext, source: MaterialCustody): Boolean {
    val field = context.field ?: return false
    if (!context.currentAssignee || !context.active || field.planState != "SUBMITTED" || source.sku.tracking == MaterialTracking.SERIAL) return false
    return if (field.latestUsageId == null) source.initialUseSource && source.planId == field.planId
    else field.reworkId != null || source.sourceUsageId == field.latestUsageId
}
