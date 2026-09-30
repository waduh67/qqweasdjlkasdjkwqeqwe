package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WorkOrderMaterialsITAction : MaterialWorkflowFixture() {
    @ParameterizedTest
    @CsvSource("PSB,INSTALL,false", "PSB,INSTALL,true", "REPAIR,REPAIR,false", "REPAIR,REPAIR,true",
        "MIGRATION,REPLACE,false", "MIGRATION,REPLACE,true", "DISMANTLE,REMOVE,false", "DISMANTLE,REMOVE,true",
        "PREVENTIVE,PREVENTIVE,false", "PREVENTIVE,PREVENTIVE,true")
    fun `AV13 explicit work type selects the same action template independently of customer`(type: String, action: String, withCustomer: Boolean) {
        val setup = setupReceipt()
        val body = """{"expectedRevision":0,"lines":[${line(setup.cable, "11000") }]}"""
        val publication = request("PUT", "/api/v1/warehouse/material-templates/$type/$action", setup.token, body)
        assertThat(publication.status).withFailMessage(publication.contentAsString).isEqualTo(200)
        if (type == "REPAIR") assertThat(request("PUT", "/api/v1/warehouse/material-templates/REPAIR/NETWORK", setup.token,
            """{"expectedRevision":0,"lines":[${line(setup.cable, "22000") }]}""").status).isEqualTo(200)
        val customer = if (withCustomer) {
            val response = request("POST", "/api/customers", setup.token,
                """{"code":"ACTION","name":"Action customer","areaId":"${area(setup.token)}","address":"Test","location":{"longitude":106.99,"latitude":-6.24}}""")
            assertThat(response.status).isEqualTo(201)
            mapper.readTree(response.contentAsString).path("id").asString()
        } else null
        val id = workOrder(setup.token, type, customer)
        val result = putPlan(setup.token, id, plan(setup.token, id, null))
        assertThat(result.path("action").asString()).isEqualTo(action)
        assertThat(result.path("lines")[0].path("quantityBase").asString()).isEqualTo("11000")
        assertThat(result.path("customerId").isNull).isEqualTo(!withCustomer)
    }
}
