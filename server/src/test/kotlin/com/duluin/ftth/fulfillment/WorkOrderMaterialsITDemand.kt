package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkOrderMaterialsITDemand : MaterialWorkflowFixture() {
    @Test fun `PSB template manual revision submission partial supply release and immutable history use owner APIs`() {
        val setup = setupReceipt()
        receiveStock(setup)
        val token = setup.token
        val customer = request("POST", "/api/customers", token,
            """{"code":"MAT-CUSTOMER","name":"Material customer","areaId":"${area(token)}","address":"Test","location":{"longitude":106.99,"latitude":-6.24}}""")
        assertThat(customer.status).withFailMessage(customer.contentAsString).isEqualTo(201)
        val id = workOrder(token, "PSB", mapper.readTree(customer.contentAsString).path("id").asString())
        val template = request("PUT", "/api/v1/warehouse/material-templates/PSB/INSTALL", token,
            """{"expectedRevision":0,"lines":[${line(setup.cable)}]}""", "template-v1")
        assertThat(template.status).withFailMessage(template.contentAsString).isEqualTo(200)
        val first = putPlan(token, id, plan(token, id, null))
        assertThat(first.path("templateId").asString()).isEqualTo(mapper.readTree(template.contentAsString).path("id").asString())
        val second = putPlan(token, id, plan(token, id, "[${line(setup.cable, "120000")}]", 1))
        assertThat(second.path("templateId").isNull).isTrue()
        assertThat(second.path("lines")[0].path("quantityBase").asString()).isEqualTo("120000")
        val submitted = action(token, id, "submit-request", command(token, id, 2), "submit-v2")
        assertThat(submitted.path("lines")[0].path("requestedBase").asString()).isEqualTo("120000")
        assertThat(submitted.path("lines")[0].path("backorderBase").asString()).isEqualTo("120000")
        val reserved = action(token, id, "reserve", command(token, id, 2), "reserve-v2")
        assertThat(reserved.path("demandState").asString()).isEqualTo("PART_RESERVED")
        assertThat(reserved.path("lines")[0].path("reservedUnpickedBase").asString()).isEqualTo("60000")
        assertThat(reserved.path("lines")[0].path("backorderBase").asString()).isEqualTo("60000")
        assertThat(action(token, id, "reserve", command(token, id, 2), "reserve-v2")).isEqualTo(reserved)
        assertThat(request("PUT", "/api/work-orders/$id/materials/plan", token, plan(token, id, "[]", 2, "NONE", "No longer needed")).status).isEqualTo(409)
        action(token, id, "release", command(token, id, 2, "Plan replacement"))
        assertThat(summary(token, id).path("lines")[0].path("reservedUnpickedBase").asString()).isEqualTo("0")
        putPlan(token, id, plan(token, id, "[]", 2, "NONE", "Inspection only"))
        val history = request("GET", "/api/work-orders/$id/materials/history?size=2", token)
        assertThat(history.status).isEqualTo(200)
        assertThat(mapper.readTree(history.contentAsString).path("totalElements").asLong()).isEqualTo(3)
        assertThat(mapper.readTree(history.contentAsString).path("items")[1].path("plan")).isEqualTo(second)
        fixture(token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='DEMAND'")).isEqualTo("1")
            assertThat(scalar("SELECT sum(reserved_unpicked_base) FROM inventory_reservation")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind NOT IN ('RESERVE','RELEASE')")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_movement_leg")).isEqualTo("4")
        }
    }
    @Test fun `authorized substitution preserves the original master snapshot and never edits history`() {
        val setup = setupReceipt()
        val id = workOrder(setup.token)
        val original = putPlan(setup.token, id, plan(setup.token, id, "[${line(setup.cable)}]"))
        val replacement = create("skus", setup.token, """{"code":"ALT","name":"Alternate cable","tracking":"LOT","baseUnit":"MM"}""").path("id").asString()
        val substitute = line(replacement).dropLast(1) + """, "substitution":{"originalPlanLineId":"${original.path("lines")[0].path("id").asString()}","originalSkuId":"${setup.cable}","reason":"Approved equivalent"}}"""
        val revised = putPlan(setup.token, id, plan(setup.token, id, "[$substitute]", 1))
        assertThat(revised.path("lines")[0].path("originalSku").path("code").asString()).isEqualTo("CABLE")
        assertThat(revised.path("lines")[0].path("sku").path("code").asString()).isEqualTo("ALT")
        val history = mapper.readTree(request("GET", "/api/work-orders/$id/materials/history", setup.token).contentAsString)
        assertThat(history.path("items")[1].path("plan")).isEqualTo(original)
    }
}
