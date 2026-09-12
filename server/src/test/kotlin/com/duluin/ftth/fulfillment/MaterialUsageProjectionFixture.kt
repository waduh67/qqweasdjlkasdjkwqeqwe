package com.duluin.ftth.fulfillment

import java.util.UUID

abstract class MaterialUsageProjectionFixture : MaterialUsageFixture() {
    protected data class ConsumedCase(val usage: UsageCase, val body: String, val identity: String, val id: String)

    protected fun consumedCase(): ConsumedCase {
        val usage = usageCase()
        val body = used(usage)
        val snapshot = mapper.readTree(body)
        return ConsumedCase(usage, body, snapshot.path("lines")[0].path("consumed").path("stockIdentityId").asString(),
            snapshot.path("usageId").asString())
    }

    protected fun changeProjection(case: ConsumedCase, mode: String) = fixture(case.usage.receipt.stock.token).transaction {
        val selection = "stock_identity_id='${case.identity}' AND status='CONSUMED'"
        val row = scalar("SELECT to_jsonb(balance)::text FROM inventory_balance_projection balance WHERE $selection")
        when (mode) {
            "zero" -> sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE $selection")
            "delete" -> sql("DELETE FROM inventory_balance_projection WHERE $selection")
            "wrong-quantity" -> sql("UPDATE inventory_balance_projection SET quantity_base=1,revision=revision+1 WHERE $selection")
            "wrong-location", "wrong-owner", "wrong-identity" -> {
                sql("DELETE FROM inventory_balance_projection WHERE $selection")
                val field = when (mode) {
                    "wrong-location" -> "'location_id','${case.usage.receipt.field}'"
                    "wrong-owner" -> "'custody_owner_id','${UUID.randomUUID()}'"
                    "wrong-identity" -> "'stock_identity_id','${case.usage.input.lines.single().stockIdentityId}'"
                    else -> error("Unknown replacement")
                }
                sql("""INSERT INTO inventory_balance_projection SELECT (jsonb_populate_record(NULL::inventory_balance_projection,
                    '$row'::jsonb || jsonb_build_object('id',gen_random_uuid(),$field))).*""")
            }
            "direct-child", "deep-child" -> {
                sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE $selection")
                sql("UPDATE inventory_segment SET state='SPLIT',revision=revision+1 WHERE id='${case.identity}'")
                val first = UUID.randomUUID()
                val second = UUID.randomUUID()
                sql("""INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base)
                    SELECT '$first'::uuid,tenant_id,sku_id,lot_id,id,'CUT',base_unit,40000 FROM inventory_segment WHERE id='${case.identity}'
                    UNION ALL SELECT '$second'::uuid,tenant_id,sku_id,lot_id,id,'REMNANT',base_unit,42500 FROM inventory_segment WHERE id='${case.identity}'""")
                val spendable = if (mode == "deep-child") {
                    val grandchild = UUID.randomUUID()
                    sql("UPDATE inventory_segment SET state='SPLIT',revision=revision+1 WHERE id='$first'")
                    sql("""INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base)
                        SELECT '$grandchild',tenant_id,sku_id,lot_id,id,'CUT',base_unit,quantity_base FROM inventory_segment WHERE id='$first'""")
                    grandchild
                } else first
                for ((identity, amount) in listOf(spendable to 40000L, second to 42500L)) {
                    sql("""INSERT INTO inventory_balance_projection SELECT (jsonb_populate_record(NULL::inventory_balance_projection,
                        '$row'::jsonb || jsonb_build_object('id',gen_random_uuid(),'item_id','$identity','stock_identity_id','$identity',
                            'quantity_base',$amount,'status','ISSUED','location_id','${case.usage.receipt.field}','revision',0))).*""")
                }
            }
            "zero-restore" -> {
                sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE $selection")
                sql("UPDATE inventory_balance_projection SET quantity_base=82500,revision=revision+1 WHERE $selection")
            }
            "delete-reinsert", "replace-id" -> {
                sql("DELETE FROM inventory_balance_projection WHERE $selection")
                val replacement = if (mode == "replace-id") " || jsonb_build_object('id',gen_random_uuid())" else ""
                sql("INSERT INTO inventory_balance_projection SELECT (jsonb_populate_record(NULL::inventory_balance_projection,'$row'::jsonb$replacement)).*")
            }
            else -> error("Unknown projection mutation")
        }
    }
}
