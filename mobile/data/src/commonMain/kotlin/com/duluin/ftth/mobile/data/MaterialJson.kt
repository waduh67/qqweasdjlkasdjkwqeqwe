package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import kotlinx.serialization.json.*

internal object MaterialJson {
    fun parse(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    fun uuid(value: String): String = value.also { require(it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Referensi material tidak valid." } }
    fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString && it.content.isNotBlank()); it.content }
    fun JsonObject.id(key: String) = uuid(text(key))
    fun JsonObject.optional(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }
    fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); it.long.also { value -> require(value >= 0) } }
    fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
    fun JsonObject.quantity(key: String) = MaterialQuantity.base(text(key))
    fun JsonObject.obj(key: String) = getValue(key).jsonObject
    fun JsonObject.rows(key: String) = getValue(key).jsonArray.also { require(it.size <= 100) }.map(JsonElement::jsonObject)
    fun <T> page(value: String, decode: (JsonObject) -> T): MaterialPage<T> {
        val r = parse(value); val size = r.number("size"); val page = r.number("page")
        require(size in 1..100 && page <= Int.MAX_VALUE)
        val rows = r.rows("items"); require(rows.size <= size)
        return MaterialPage(rows.map(decode), page.toInt(), size.toInt(), r.number("totalElements").also { require(it >= rows.size) })
    }
    fun job(r: JsonObject) = MaterialJob(r.id("id"), r.text("code"), r.text("updatedAt"))
    fun person(r: JsonObject) = MaterialPerson(r.id("id"), r.text("name"))
    fun sku(r: JsonObject) = MaterialSku(r.id("id"), r.text("code"), r.text("name"), MaterialTracking.valueOf(r.text("tracking")), MaterialUnit.valueOf(r.text("baseUnit")))
    fun location(r: JsonObject) = MaterialLocation(r.id("id"), r.text("code"), r.optional("name"))
    fun context(value: String, id: String): MaterialContext {
        val r = parse(value)
        require(r.id("id") == id)
        val field = r["field"]?.takeUnless { it is JsonNull }?.jsonObject?.let { f ->
            require(r.flag("currentAssignee") && f.id("workOrderId") == id && f.number("workOrderRevision") == r.number("workOrderRevision"))
            val plan = f["plan"]?.takeUnless { it is JsonNull }?.jsonObject
            MaterialField(plan?.id("id"), plan?.number("planRevision"), plan?.text("materialMode")?.let(MaterialMode::valueOf), f.optional("planState"),
                f.number("useRevision"), f.optional("latestUsageId")?.let(::uuid), f.optional("reworkId")?.let(::uuid), f.optional("evidenceRevision"),
                if (f.containsKey("hasMeasuredMaterials")) f.flag("hasMeasuredMaterials") else true)
        }
        return MaterialContext(id, r.text("code"), r.number("workOrderRevision"), r.flag("currentAssignee"), r.flag("active"), r.text("technicalState"), r.optional("qaState"), field)
    }
    fun issue(r: JsonObject, work: String): MaterialIssue {
        require(r.id("workOrderId") == work && r.text("state") in setOf("DISPATCHED", "PART_RECEIVED", "RECEIVED"))
        val lines = r.rows("lines").map { l ->
            val sku = sku(l.obj("sku")); val unit = MaterialUnit.valueOf(l.text("baseUnit"))
            val dispatched = l.quantity("dispatchedBase"); val accepted = l.quantity("acceptedBase"); val remaining = l.quantity("remainingBase")
            val serial = l.optional("serial")
            require(sku.baseUnit == unit && accepted.value <= dispatched.value && dispatched.value - accepted.value == remaining.value)
            require(sku.tracking != MaterialTracking.SERIAL || (unit == MaterialUnit.EA && dispatched.value == 1L && !serial.isNullOrBlank()))
            MaterialIssueLine(l.id("id"), l.id("stockIdentityId"), sku, unit, dispatched, accepted, remaining, serial, l.optional("lotCode"))
        }
        require(lines.map { it.id }.toSet().size == lines.size)
        return MaterialIssue(r.id("id"), r.text("code"), work, r.number("workOrderRevision"), r.number("revision"), r.text("state"), person(r.obj("sender")), person(r.obj("receiver")), lines)
    }
    fun custody(r: JsonObject): MaterialCustody {
        val sku = sku(r.obj("sku")); val unit = MaterialUnit.valueOf(r.text("baseUnit")); val quantity = r.quantity("quantityBase"); val serial = r.optional("serial")
        require(quantity.value > 0 && sku.baseUnit == unit)
        require(sku.tracking != MaterialTracking.SERIAL || (unit == MaterialUnit.EA && quantity.value == 1L && !serial.isNullOrBlank()))
        return MaterialCustody(r.id("id"), r.id("receiptId"), r.id("issueId"), r.text("issueCode"), r.id("issueLineId"), r.id("planId"), r.id("planLineId"), sku,
            r.optional("sourceUsageId")?.let(::uuid), quantity, unit, r.number("stockRevision"), location(r.obj("location")), serial, r.optional("lotCode"), r.flag("initialUseSource"))
    }
    fun fieldGuard(context: MaterialContext) = buildJsonObject {
        put("workOrderRevision", context.workOrderRevision)
        context.field?.let { f ->
            put("planId", f.planId); put("planRevision", f.planRevision); put("materialMode", f.mode?.name); put("planState", f.planState)
            put("useRevision", f.useRevision); put("latestUsageId", f.latestUsageId); put("reworkId", f.reworkId); put("evidenceRevision", f.evidenceRevision)
            if (!f.hasMeasuredMaterials) put("hasMeasuredMaterials", false)
        }
    }
    fun sourceGuard(s: MaterialCustody) = buildJsonObject {
        put("id", s.id); put("receiptId", s.receiptId); put("issueLineId", s.issueLineId); put("planId", s.planId); put("planLineId", s.planLineId)
        put("quantityBase", s.quantityBase.base); put("baseUnit", s.baseUnit.name); put("stockRevision", s.stockRevision); put("sourceUsageId", s.sourceUsageId)
        put("initialUseSource", s.initialUseSource); put("locationId", s.location.id)
    }
}
