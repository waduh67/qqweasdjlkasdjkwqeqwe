package com.duluin.ftth.inventory

import java.sql.Connection
import java.util.UUID

/** Only used before V173, as migration owner; never seeds admitted stock. */
internal class WarehouseMigrationLegacyFixture {
    val tenant = UUID.randomUUID()
    val otherTenant = UUID.randomUUID()
    val location = UUID.randomUUID()
    val sku = UUID.randomUUID()
    val customer = UUID.randomUUID()
    val first = UUID.randomUUID()
    val second = UUID.randomUUID()
    val installed = UUID.randomUUID()
    val episode = UUID.randomUUID()
    val unmatchedEpisode = UUID.randomUUID()
    val balance = UUID.randomUUID()
    val applied = UUID.randomUUID()
    val pending = UUID.randomUUID()
    val serializedLeg = UUID.randomUUID()
    val bulkLeg = UUID.randomUUID()

    fun seed(connection: Connection) = connection.createStatement().use { sql ->
        sql.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','migration-$tenant','Migration'),('$otherTenant','migration-$otherTenant','Waiting')")
        sql.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$location','$tenant','legacy','WAREHOUSE')")
        sql.execute("""INSERT INTO customer(id,tenant_id,code,name,address,location,location_status)
            VALUES ('$customer','$tenant','C-OLD','Legacy customer','Private address excluded from snapshot',ST_SetSRID(ST_MakePoint(106,-6),4326),'LOCATED')""")
        for ((id, raw, mac, status) in listOf(
            listOf(first.toString(), " Serial-A ", "aa-bb-cc-dd-ee-ff", "AVAILABLE"),
            listOf(second.toString(), "serial-a", "AA:BB:CC:DD:EE:FF", "AVAILABLE"),
            listOf(installed.toString(), "INSTALLED-UNIQUE", "11:22:33:44:55:66", "CONSUMED"),
        )) {
            sql.execute("""INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,mac_address,status,location_id,custody_owner_id,custody_owner_kind,installed_onu_id)
                VALUES ('$id','$tenant','$sku','$raw','$mac','$status','$location','$location','WAREHOUSE',${if (id == installed.toString()) "'$episode'" else "NULL"})""")
        }
        sql.execute("""INSERT INTO onu(id,tenant_id,customer_id,serial_number,model,status,installed_at)
            VALUES ('$episode','$tenant','$customer','INSTALLED-UNIQUE','Old ONU','ONLINE','2020-01-02T03:04:05Z'),
                   ('$unmatchedEpisode','$tenant','$customer','UNMATCHED-ONU','Unknown origin','ONLINE','2021-02-03T04:05:06Z')""")
        sql.execute("INSERT INTO inventory_serial_tombstone(id,tenant_id,serial_number) VALUES ('${UUID.randomUUID()}','$tenant','RETIRED-OLD')")
        for ((id, state) in listOf(applied to "APPLIED", pending to "PENDING_APPROVAL")) {
            sql.execute("""INSERT INTO inventory_movement(id,tenant_id,operation_namespace,operation_key,payload_hash,actor_id,reason,server_received_at,kind,state)
                VALUES ('$id','$tenant','legacy','$id','old-hash','$customer','legacy',now(),'RECEIPT','$state')""")
        }
        sql.execute("""INSERT INTO inventory_movement_leg(id,tenant_id,movement_id,direction,item_id,sku_id,location_id,quantity,serialized,custody_owner_id,custody_owner_kind,status)
            VALUES ('$serializedLeg','$tenant','$applied','IN','$first','$sku','$location',1,true,'$location','WAREHOUSE','AVAILABLE'),
                   ('$bulkLeg','$tenant','$applied','IN','${UUID.randomUUID()}','$sku','$location',82500,false,'$location','WAREHOUSE','AVAILABLE')""")
        sql.execute("""INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,quantity,rebuilt_at)
            VALUES ('$balance','$tenant','${UUID.randomUUID()}','$sku','$location','$location','WAREHOUSE','AVAILABLE',82500,now())""")
    }
}
