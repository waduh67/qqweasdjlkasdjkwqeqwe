package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.sql.DriverManager
import java.util.UUID

class WarehouseMasterITIdentityAmbiguity : WarehouseMasterHttpFixture() {
    @ParameterizedTest
    @CsvSource("SERIAL,ONE", "SERIAL,BOTH", "SERIAL,NEITHER", "SERIAL,MULTIPLE", "SERIAL,SINGLE_CONFLICT", "SERIAL,UNIQUE_VISIBLE", "SERIAL,UNIQUE_HIDDEN", "SERIAL,MALFORMED",
        "MAC,ONE", "MAC,BOTH", "MAC,NEITHER", "MAC,MULTIPLE", "MAC,SINGLE_CONFLICT", "MAC,UNIQUE_VISIBLE", "MAC,UNIQUE_HIDDEN", "MAC,MALFORMED")
    fun `AV7-03 tenant ambiguity precedes all visibility filters`(type: String, scenario: String) {
        val admin = tenant()
        val (actor, userId) = user(admin,setOf("inventory.item.view","inventory.provenance.view","inventory.location.manage","inventory.location.view"))
        val me = mapper.readTree(request("GET", "/api/me",actor).contentAsString)
        val roles = me.path("roleIds").asSequence().map { it.asString() }.toList()
        assertThat(request("PUT", "/api/users/$userId/access",admin,mapper.writeValueAsString(mapOf("roleIds" to roles,"areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        fun location(code: String, visible: Boolean) = create("locations",if(visible) actor else admin,
            """{"code":"$code","name":"Location","kind":"WAREHOUSE","areaId":"${area(admin)}"}""").path("id").asString()
        val firstVisible = scenario !in setOf("NEITHER","UNIQUE_HIDDEN")
        val first = location("FIRST",firstVisible)
        val second = location("SECOND",scenario=="BOTH")
        val fixture = fixture(admin)
        val canonical = if(type=="MAC") UUID.randomUUID().toString().replace("-", "").take(12).uppercase() else "LEGACY-${UUID.randomUUID().toString().uppercase()}"
        val claim = UUID.randomUUID()
        val multiple = scenario in setOf("ONE","BOTH","NEITHER","MULTIPLE")
        val conflict = scenario in setOf("ONE","BOTH","NEITHER","SINGLE_CONFLICT")
        val assetIds = List(if(multiple) 2 else 1) { UUID.randomUUID() }
        DriverManager.getConnection(System.getenv("SPRING_FLYWAY_URL"),System.getenv("SPRING_FLYWAY_USER"),System.getenv("SPRING_FLYWAY_PASSWORD")).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { sql ->
                sql.execute("SET LOCAL app.tenant_id='${fixture.tenant}'")
                sql.execute("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state) VALUES ('$claim','${fixture.tenant}','$type','$canonical','${if(conflict) "CONFLICT" else "LEGACY_RESERVED"}')")
                assetIds.forEachIndexed { index, id ->
                    val raw = if(scenario=="MALFORMED") " " else if(type=="MAC") canonical.chunked(2).joinToString(if(index==0) ":" else "-") else if(index==0) canonical.lowercase() else canonical
                    val serial = if(type=="SERIAL") raw else "SERIAL-$id"
                    val mac = if(type=="MAC") "'$raw'" else "NULL"
                    val serialCandidate = if(type=="SERIAL") "'$canonical'" else "NULL"
                    val macCandidate = if(type=="MAC") "'$canonical'" else "NULL"
                    val location = if(index==0) first else second
                    sql.execute("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,mac_address,canonical_serial_candidate,canonical_mac_candidate,status,location_id,custody_owner_id,custody_owner_kind,warehouse_admission) VALUES ('$id','${fixture.tenant}','${UUID.randomUUID()}','$serial',$mac,$serialCandidate,$macCandidate,'AVAILABLE','$location','$location','WAREHOUSE','LEGACY_UNRESOLVED')")
                    sql.execute("INSERT INTO inventory_identity_candidate(id,tenant_id,claim_id,identity_type,source_table,source_id,raw_value,canonical_value) VALUES ('${UUID.randomUUID()}','${fixture.tenant}','$claim','$type','inventory_serialized_asset','$id','$raw','$canonical')")
                }
            }
            connection.commit()
        }
        val before = fixture.transaction { counts() }
        val missing = request("GET", "/api/v1/warehouse/assets/lookup?value=ABSENT",actor)
        val value = if(type=="MAC") canonical.chunked(2).joinToString("-") else canonical.lowercase()
        val response = request("GET", "/api/v1/warehouse/assets/lookup?value=$value",actor)
        println("AV7-03 $type/$scenario status=${response.status} body=${response.contentAsString}")
        assertThat(response.status).isEqualTo(if(scenario=="UNIQUE_VISIBLE") 200 else 404)
        if(scenario=="UNIQUE_VISIBLE") {
            assertThat(mapper.readTree(response.contentAsString).path("assetId").asString()).isEqualTo(assetIds[0].toString())
            assertThat(mapper.readTree(response.contentAsString).path("legacyUnresolved").asBoolean()).isTrue()
            val permissions = mapper.readTree(request("GET", "/api/permissions",admin).contentAsString)
            val ids = permissions.asSequence().filter { it.path("code").asString()=="inventory.item.view" }.map { it.path("id").asString() }.toList()
            val role = mapper.readTree(request("POST","/api/roles",admin,mapper.writeValueAsString(mapOf("name" to "No provenance","permissionIds" to ids))).contentAsString).path("id").asString()
            assertThat(request("PUT", "/api/users/$userId/access",admin,mapper.writeValueAsString(mapOf("roleIds" to listOf(role),"areaIds" to listOf(area(admin))))).status).isEqualTo(200)
            assertThat(request("GET", "/api/v1/warehouse/assets/lookup?value=$value",actor).contentAsString).isEqualTo(missing.contentAsString)
        } else assertThat(response.contentAsString).isEqualTo(missing.contentAsString)
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
    }
}
