package com.duluin.ftth.inventory

import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.node.ObjectNode

class WarehouseMasterITDecoding : WarehouseMasterHttpFixture() {
    @ParameterizedTest
    @CsvSource("skus,CREATE", "skus,UPDATE", "skus,ARCHIVE", "locations,CREATE", "locations,UPDATE", "locations,ARCHIVE",
        "suppliers,CREATE", "suppliers,UPDATE", "suppliers,ARCHIVE")
    fun `AV7-01 exact JSON types are required and rejected requests leave no writes`(resource: String, action: String) {
        val token = tenant()
        val body = when (resource) {
            "skus" -> """{"code":"MASTER","name":"Master","category":"Category","model":"Model","tracking":"SERIAL","baseUnit":"EA","minimumQuantityBase":"1","inspectionRequired":true,"allowedOwnershipModes":["LOAN","SALE"]}"""
            "locations" -> """{"code":"MASTER","name":"Master","kind":"WAREHOUSE","areaId":"${area(token)}","issueEligible":false}"""
            else -> """{"code":"MASTER","name":"Master","contactReference":"Contact"}"""
        }
        val id = if (action == "CREATE") null else create(resource, token, body).path("id").asString()
        val path = "/api/v1/warehouse/$resource" + if (id == null) "" else "/$id" + if (action == "ARCHIVE") "/archive" else ""
        val method = if (action == "UPDATE") "PUT" else "POST"
        val valid = if (action == "ARCHIVE") """{"expectedRevision":0}""" else
            if (action == "UPDATE") body.dropLast(1) + ",\"expectedRevision\":0}" else body
        fun replaced(field: String, json: String): String = (mapper.readTree(valid) as ObjectNode).also {
            it.set(field, mapper.readTree(json))
        }.toString()
        val invalid = mutableListOf<Pair<String, String>>()
        if (action != "ARCHIVE") {
            val strings = listOf("code", "name") + when (resource) {
                "skus" -> listOf("category", "model", "minimumQuantityBase")
                "suppliers" -> listOf("contactReference")
                else -> emptyList()
            }
            strings.forEach { field -> listOf("123", "true", "1.5", "[]", "{}").forEach { value -> invalid += "$field=$value" to replaced(field,value) } }
            if (resource == "skus") {
                for (field in listOf("tracking", "baseUnit", "allowedOwnershipModes")) invalid += "$field numeric enum" to replaced(field, if (field == "allowedOwnershipModes") "[0]" else "0")
                invalid += "numeric boolean" to replaced("inspectionRequired", "1")
                invalid += "string boolean" to replaced("inspectionRequired", "\"true\"")
            }
            if (resource == "locations") {
                invalid += "numeric kind" to replaced("kind", "0")
                invalid += "numeric issueEligible" to replaced("issueEligible", "1")
                invalid += "string issueEligible" to replaced("issueEligible", "\"false\"")
                for (field in listOf("areaId", "siteId", "parentLocationId", "custodianId")) invalid += "$field numeric reference" to replaced(field, "123")
            }
        }
        for (value in listOf("0.9", "0.0", "0e0", "\"0\"", "true", "[]", "{}")) invalid += "revision=$value" to replaced("expectedRevision", value)
        val duplicateField = if (action == "ARCHIVE") "expectedRevision" else "code"
        invalid += "duplicate property" to (valid.dropLast(1) + ",\"$duplicateField\":" + if (action == "ARCHIVE") "0}" else "\"MASTER\"}")
        invalid += "unknown property" to (valid.dropLast(1) + ",\"authority\":true}")
        val fixture = fixture(token)
        fun state() = fixture.transaction {
            scalar("SELECT json_build_array((SELECT coalesce(jsonb_agg(to_jsonb(master) ORDER BY id),'[]') FROM inventory_${if(resource=="skus") "sku" else if(resource=="locations") "location" else "supplier"} master),(SELECT count(*) FROM inventory_operation),(SELECT count(*) FROM inventory_command_identity))::text")
        }
        val before = state()
        val assertions = SoftAssertions()
        invalid.forEach { (label, json) ->
            val response = request(method, path, token, json)
            println("AV7-01 $resource/$action $label status=${response.status}")
            assertions.assertThat(response.status).describedAs("$resource/$action $label").isEqualTo(400)
            assertions.assertThat(state()).describedAs("zero writes for $resource/$action $label").isEqualTo(before)
        }
        assertions.assertAll()
    }
}
