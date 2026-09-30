package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.domain.model.LocationKind
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class WarehouseSiteHistorySeed(val inheritance: Boolean) : AutoCloseable {
    val tenant = UUID.randomUUID(); val slug = "history-$tenant"; val area = UUID.randomUUID()
    val firstSite = UUID.randomUUID(); val secondSite = UUID.randomUUID()
    val root = UUID.randomUUID(); val bridge = UUID.randomUUID(); val conflict = UUID.randomUUID(); val leaf = UUID.randomUUID()
    val target get() = if(inheritance) leaf else root
    val database = WarehouseSchemaDatabase("172")
    init {
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','$slug','History')")
            sql.execute("INSERT INTO area(id,tenant_id,code,name) VALUES ('$area','$tenant','HISTORY','History')")
        } }
        database.migrate(if(inheritance) "174.10" else "174.9")
        database.ownerFixture { connection ->
            connection.autoCommit = false
            connection.createStatement().use { sql ->
                sql.execute("SET LOCAL app.tenant_id='$tenant'")
                sql.execute("INSERT INTO site(id,tenant_id,code,name,location,area_id) VALUES ('$firstSite','$tenant','SITE-A','Site A',ST_SetSRID(ST_MakePoint(1,1),4326),'$area'),('$secondSite','$tenant','SITE-B','Site B',ST_SetSRID(ST_MakePoint(1,1),4326),'$area')")
                sql.execute("INSERT INTO inventory_location(id,tenant_id,code,name,kind,area_id,site_id) VALUES ('$root','$tenant','WH','Warehouse','WAREHOUSE','$area','$firstSite')")
                if(inheritance) {
                    sql.execute("INSERT INTO inventory_location(id,tenant_id,code,name,kind,area_id,parent_location_id,site_id) VALUES ('$bridge','$tenant','BRIDGE','Bridge','BIN','$area','$root',NULL),('$conflict','$tenant','CONFLICT','Conflict','BIN','$area','$bridge','$secondSite'),('$leaf','$tenant','TRANSITIVE-LEAF','Leaf','BIN','$area','$conflict',NULL)")
                    sql.execute("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,canonical_serial_candidate,status,location_id,custody_owner_id,custody_owner_kind,warehouse_admission) VALUES ('${UUID.randomUUID()}','$tenant','${UUID.randomUUID()}','TRANSITIVE-ASSET','TRANSITIVE-ASSET','AVAILABLE','$leaf','$leaf','WAREHOUSE','LEGACY_UNRESOLVED')")
                } else {
                    val hidden=UUID.randomUUID()
                    sql.execute("INSERT INTO area(id,tenant_id,code,name) VALUES ('$hidden','$tenant','HIDDEN','Hidden')")
                    sql.execute("UPDATE site SET area_id='$hidden' WHERE id='$firstSite'")
                }
            }
            connection.commit()
        }
    }
    fun recordReplay(fixture: WarehousePostingFixture, actor: UUID): Pair<String,String> {
        val mapper=jacksonObjectMapper(); val key=UUID.randomUUID().toString()
        val input=LocationInput(if(inheritance) "TRANSITIVE-LEAF" else "WH",if(inheritance) "Leaf" else "Warehouse",
            if(inheritance) LocationKind.BIN else LocationKind.WAREHOUSE,if(inheritance) bridge else null,
            if(inheritance) null else firstSite,area)
        val canonical=WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to null,"input" to input)))
        val snapshot=LocationSnapshot(target,0,WarehouseMasterState.ACTIVE,input.code,input.name,input.kind,input.parentLocationId,input.siteId,area,null,false)
        fixture.transaction {
            sql("INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch) VALUES ('${UUID.randomUUID()}','$tenant','$actor','$root','$actor',0)")
            context.getBean(WarehouseOperationStore::class.java).storeMaster(MasterKind.LOCATION,MasterAction.CREATE,key,target,0,actor,0,0,
                canonical.json,canonical.hash,mapper.writeValueAsString(snapshot),null)
        }
        return mapper.writeValueAsString(input) to key
    }
    override fun close() = database.close()
}
