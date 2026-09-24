package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import kotlinx.serialization.json.*

internal data class PreparedMaterial(val kind: MaterialCommandKind, val body: JsonObject, val guards: JsonObject)
internal fun prepareMaterial(draft: MaterialDraft, session: MaterialSession): PreparedMaterial {
    val context = draft.context
    require(session.fieldAllowed && !session.readOnly && context.currentAssignee && context.active) { "Aksi material memerlukan penugasan aktif dan izin teknisi." }
    val evidence = draft.evidenceReference.trim()
    require(evidence.isNotEmpty() && evidence.length <= 500) { "Isi referensi bukti, maksimal 500 karakter." }
    require(context.workOrderRevision >= 0)
    MaterialJson.uuid(context.id)
    return when (draft) {
        is MaterialDraft.Acknowledge -> {
            val issue = draft.issue
            require(issue.workOrderId == context.id && issue.receiver.id == session.identity.userId && issue.workOrderRevision == context.workOrderRevision && issue.state != "RECEIVED") { "Pengiriman atau penerima berubah." }
            require(draft.lines.size in 1..100 && draft.lines.map { it.lineId }.toSet().size == draft.lines.size)
            val lines = draft.lines.map { measured ->
                val line = issue.lines.single { it.id == measured.lineId }
                val accepted = measured.acceptedBase.value; val missing = measured.missingBase.value; val rejected = measured.rejectedBase.value
                require(accepted > 0 && accepted <= line.remainingBase.value && missing <= line.remainingBase.value - accepted && rejected == line.remainingBase.value - accepted - missing) { "Jumlah diterima, kurang, dan ditolak harus sama dengan jumlah menunggu." }
                require(measured.reason.orEmpty().length <= 1000 && ((missing == 0L && rejected == 0L) || !measured.reason.isNullOrBlank())) { "Isi alasan selisih penerimaan." }
                require(line.sku.tracking != MaterialTracking.SERIAL || (line.serial == measured.observedSerial?.trim()?.uppercase() && accepted == 1L)) { "Serial fisik tidak cocok." }
                buildJsonObject {
                    put("issueLineId", line.id); put("stockIdentityId", line.stockIdentityId); put("baseUnit", line.baseUnit.name)
                    put("acceptedBase", measured.acceptedBase.base); put("missingBase", measured.missingBase.base); put("rejectedBase", measured.rejectedBase.base)
                    measured.reason?.trim()?.takeIf(String::isNotEmpty)?.let { put("reason", it) }
                    if (line.sku.tracking == MaterialTracking.SERIAL) put("serial", line.serial)
                }
            }
            PreparedMaterial(MaterialCommandKind.ACKNOWLEDGE, buildJsonObject {
                put("issueId", issue.id); put("expectedRevision", issue.revision); put("workOrderRevision", context.workOrderRevision); put("evidenceReference", evidence); put("lines", JsonArray(lines))
            }, buildJsonObject { put("issueId", issue.id); put("issueRevision", issue.revision) })
        }
        is MaterialDraft.ReportUse -> {
            val field = requireNotNull(context.field)
            require(field.planId != null && field.planRevision != null && field.planState == "SUBMITTED") { "Rencana material belum diajukan." }
            val reason = draft.reason?.trim()?.takeIf(String::isNotEmpty)
            require(reason.orEmpty().length <= 1000)
            if (field.mode == MaterialMode.NONE) require(field.useRevision == 0L && draft.lines.isEmpty() && reason != null) { "Deklarasi tanpa material hanya dicatat sekali dan memerlukan alasan." }
            else require(draft.lines.size in 1..100 && (field.useRevision == 0L || draft.lines.size == 1))
            require(draft.lines.map { it.source.id }.toSet().size == draft.lines.size)
            val lines = draft.lines.map { measured ->
                val s = measured.source
                require(materialCanUse(context, s) && measured.quantityBase.value in 1..s.quantityBase.value) { "Pilih sisa barang yang sudah diterima; perangkat berserial memakai alur pemasangan aset." }
                buildJsonObject { put("receiptId", s.receiptId); put("issueLineId", s.issueLineId); put("stockIdentityId", s.id); put("quantityBase", measured.quantityBase.base); put("baseUnit", s.baseUnit.name) }
            }
            val kind = if (field.useRevision == 0L) MaterialCommandKind.REPORT_USE else MaterialCommandKind.CORRECT_USE
            PreparedMaterial(kind, buildJsonObject {
                put("expectedRevision", field.useRevision); put("workOrderRevision", context.workOrderRevision); put("evidenceReference", evidence)
                reason?.let { put("reason", it) }
                if (kind == MaterialCommandKind.REPORT_USE) {
                    put("planRevision", field.planRevision); put("materialMode", requireNotNull(field.mode).name); put("lines", JsonArray(lines))
                } else {
                    require(field.latestUsageId != null && reason != null) { "Tambahan pemakaian memerlukan riwayat dan alasan." }
                    put("previousUsageId", field.latestUsageId); lines.single().forEach { (key, value) -> put(key, value) }
                    field.reworkId?.let { put("reworkId", it); field.evidenceRevision?.let { revision -> put("evidenceRevision", revision) } }
                }
            }, buildJsonObject { put("field", MaterialJson.fieldGuard(context)); put("sources", JsonArray(draft.lines.map { MaterialJson.sourceGuard(it.source) })) })
        }
    }
}
