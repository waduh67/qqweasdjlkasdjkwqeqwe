package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import java.sql.SQLException
import java.util.UUID

abstract class WarehouseInheritanceFixture : WarehouseMasterHttpFixture() {
    protected fun site(token: String, code: String): String {
        val response = request("POST", "/api/sites",token,"""{"code":"SITE-$code","name":"Site","location":{"longitude":106.8,"latitude":-6.2},"areaId":"${area(token)}"}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }
    protected fun locationBody(token: String, code: String, parent: String?, site: String?) = mapper.writeValueAsString(mapOf(
        "code" to code,"name" to code,"kind" to if(parent==null) "WAREHOUSE" else "BIN",
        "areaId" to area(token),"siteId" to site,"parentLocationId" to parent))
    protected fun location(token: String, code: String, parent: String?, site: String?): String =
        create("locations",token,locationBody(token,code,parent,site)).path("id").asString()
    internal fun WarehousePostingFixture.insertLocation(id: UUID, parent: String?, site: String?, area: String, code: String = id.toString()) {
        sql("INSERT INTO inventory_location(id,tenant_id,code,name,kind,parent_location_id,site_id,area_id) VALUES ('$id','$tenant','$code','$code','BIN',${parent?.let { "'$it'" } ?: "NULL"},${site?.let { "'$it'" } ?: "NULL"},'$area')")
    }
    protected fun sqlState(failure: Throwable): String = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState
}
