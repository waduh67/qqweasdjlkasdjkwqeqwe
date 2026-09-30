package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialTemplateStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun lock(workType: String, action: String) = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|material-template|$workType|$action")
    }
    fun current(workType: String, action: String): MaterialTemplateSnapshot? = jdbc.execute { sql ->
        sql.query("""SELECT template.* FROM inventory_material_template template JOIN inventory_material_template_current current
            ON current.tenant_id=template.tenant_id AND current.template_id=template.id WHERE current.tenant_id=? AND current.work_type=? AND current.action=?""",
            sql.tenant, workType, action) {
            MaterialTemplateSnapshot(it.uuid("id"), workType, action, it.getLong("template_revision"),
                mapper.readValue(it.getString("snapshot"), Array<MaterialPlanSnapshotLine>::class.java).toList())
        }.singleOrNull()
    }
    fun insert(template: MaterialTemplateSnapshot, actor: UUID) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_material_template(id,tenant_id,work_type,action,template_revision,actor_id,snapshot) VALUES (?,?,?,?,?,?,?)",
            template.id, sql.tenant, template.workType, template.action, template.revision, actor, mapper.writeValueAsString(template.lines))
        template.lines.forEach { line -> sql.update("""INSERT INTO inventory_material_template_line(id,tenant_id,template_id,line_number,sku_id,
            quantity_base,base_unit,continuous_cut) VALUES (?,?,?,?,?,?,?,?)""", line.id, sql.tenant, template.id, line.lineNumber,
            line.sku.id, line.quantityBase.toLong(), line.sku.baseUnit, line.continuousCut) }
        sql.update("""INSERT INTO inventory_material_template_current(id,tenant_id,work_type,action,template_id) VALUES (?,?,?,?,?)
            ON CONFLICT(tenant_id,work_type,action) DO UPDATE SET template_id=EXCLUDED.template_id,revision=inventory_material_template_current.revision+1,updated_at=clock_timestamp()""",
            UUID.randomUUID(), sql.tenant, template.workType, template.action, template.id)
    }
}
