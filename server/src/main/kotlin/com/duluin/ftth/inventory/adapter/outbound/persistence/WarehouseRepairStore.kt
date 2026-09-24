package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseRepairStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun dispatch(source: WarehouseReturnView, progress: WarehouseRepairProgress, request: WarehouseRepairDispatch) = jdbc.execute { sql ->
        if (sql.value("SELECT id FROM inventory_repair_case WHERE tenant_id=? AND return_document_id=?", sql.tenant, source.id) != null)
            sql.fail(WarehouseErrorCode.STALE_REVISION)
        sql.update("""INSERT INTO inventory_repair_case(id,tenant_id,return_document_id,asset_id,legal_owner,vendor_id,
            vendor_reference,outbound_document_id,state,source_return_revision,source_snapshot,dispatch_request)
            VALUES (?,?,?,?,?,?,?,?,'OUTBOUND',?,?::jsonb,?::jsonb)""", progress.id, sql.tenant, source.id, source.stockIdentityId,
            source.legalOwner, progress.vendorId, progress.vendorReference, source.id, source.revision,
            mapper.writeValueAsString(source), mapper.writeValueAsString(request))
        Unit
    }

    fun receive(id: UUID, request: WarehouseRepairReceipt) = jdbc.execute { sql ->
        if (sql.update("""UPDATE inventory_repair_case SET state='RETURNED',revision=revision+1,result=?,
            receive_revision=?,receive_request=?::jsonb,updated_at=clock_timestamp()
            WHERE tenant_id=? AND id=? AND state='OUTBOUND' AND revision=0""", request.result,
            request.expectedRevision + 1, mapper.writeValueAsString(request), sql.tenant, id) != 1)
            sql.fail(WarehouseErrorCode.STALE_REVISION)
        Unit
    }

    fun inspected(view: WarehouseReturnView) = jdbc.execute { sql ->
        val repair = view.repair ?: return@execute
        if (view.condition != WarehouseCondition.SERVICEABLE) return@execute
        sql.update("""UPDATE inventory_repair_case SET state='CLOSED',revision=revision+1,
            inspected_return_document_id=?,inspected_revision=?,updated_at=clock_timestamp()
            WHERE tenant_id=? AND id=? AND state='RETURNED' AND revision=1""", view.id, view.revision, sql.tenant, repair.id)
    }
}
