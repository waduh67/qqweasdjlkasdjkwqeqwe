package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.reflect.full.memberProperties

class MaterialWarehouseContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val id = "00000000-0000-0000-0000-000000000001"

    @Test
    fun `material API covers every named workflow without generic action dispatch`() {
        assertThat(InventoryMaterialApi::class.java.declaredMethods.map { it.name }).containsExactlyInAnyOrder(
            "summary", "history", "replacePlan", "submitRequest", "reserve", "release", "pick", "dispatch",
            "acknowledge", "reportUse", "returnMaterial", "reallocate", "verifySettlement",
        )
    }

    @Test
    fun `physical handover and return commands have strict nested quantities`() {
        val fixtures = mapOf(
            "PickMaterialRequest" to """{"documentId":"$id","expectedRevision":2,"lines":[{"demandLineId":"$id","stockIdentityId":"$id","quantityBase":"100000","baseUnit":"MM"}]}""",
            "AcknowledgeMaterialRequest" to """{"documentId":"$id","expectedRevision":2,"lines":[{"issueLineId":"$id","acceptedBase":"60000","rejectedBase":"0","missingBase":"40000","reason":"Partial delivery"}]}""",
            "ReturnMaterialRequest" to """{"documentId":"$id","expectedRevision":2,"returnLocationId":"$id","lines":[{"issueLineId":"$id","stockIdentityId":"$id","quantityBase":"17500","baseUnit":"MM"}],"reason":"Unused remnant"}""",
            "ReallocateMaterialRequest" to """{"documentId":"$id","expectedRevision":2,"targetWorkOrderId":"$id","targetPlanRevision":3,"lines":[{"issueLineId":"$id","stockIdentityId":"$id","quantityBase":"17500","baseUnit":"MM"}],"reason":"Revised job"}""",
        )
        fixtures.forEach { (name, json) ->
            val type = Class.forName("com.duluin.ftth.inventory.$name")
            val actual = mapper.readValue(json, type)
            assertThat(mapper.readTree(mapper.writeValueAsString(actual))).isEqualTo(mapper.readTree(json))
            listOf("tenantId", "actorId", "approverId", "tier", "payloadHash", "movementId", "authorityEpoch").forEach { key ->
                assertThatThrownBy { mapper.readValue(json.dropLast(1) + ",\"$key\":\"forged\"}", type) }
                    .isInstanceOf(tools.jackson.core.JacksonException::class.java)
                val nested = json.replace("\"lines\":[{", "\"lines\":[{\"$key\":\"forged\",")
                assertThatThrownBy { mapper.readValue(nested, type) }.isInstanceOf(tools.jackson.core.JacksonException::class.java)
            }
        }
    }

    @Test
    fun `portal asset has no operational or private proof fields`() {
        val type = Class.forName("com.duluin.ftth.customer.PortalCustomerAsset")
        assertThat(type.declaredFields.map { it.name }).containsExactlyInAnyOrder(
            "deviceLabel", "serialNumber", "ownershipMode", "legalOwner", "provenance", "installedAt", "removedAt",
        )
    }

    @Test
    fun `legacy count and V2 length fixtures keep different semantics`() {
        assertThat(MaterialConsumptionApi::class.java.getMethod("forCustomer", UUID::class.java, UUID::class.java)
            .isAnnotationPresent(Deprecated::class.java)).isTrue()
        val legacy = """{"tenantId":"$id","customerId":"$id","workOrderId":"$id","itemCategory":"ONT","quantity":1,"installed":true,"returned":false,"recordedAt":"2026-09-07T00:00:00Z"}"""
        assertThat(mapper.readValue(legacy, CustomerMaterialFactRef::class.java).quantity).isEqualTo(1)
        val quantity = mapper.readValue("""{"quantityBase":"82500","baseUnit":"MM","displayQuantity":"82.500","displayUnit":"M"}""", WarehouseQuantity::class.java)
        assertThat(quantity.quantityBase).isEqualTo("82500")
        assertThat(quantity.displayQuantity).isEqualTo("82.500")
        assertThat(quantity.baseUnit).isEqualTo(WarehouseBaseUnit.MM)
    }

    @Test
    fun `validation context requires both fences in the pinned global lock order`() {
        assertThat(DeploymentValidationContext::class.memberProperties.map { it.name })
            .containsExactlyInAnyOrder("binding", "authorityFence", "cutoverFence")
        assertThat(DeploymentValidationContext::class.memberProperties).allSatisfy {
            assertThat(it.returnType.isMarkedNullable).isFalse()
        }
        assertThat(com.duluin.ftth.common.security.WarehouseLockStage.entries.map { it.name }).containsExactly(
            "TENANT_CUTOVER", "CURRENT_AUTHORITY", "WORK_ORDER_CONTEXT", "INVENTORY_DOCUMENT_ASSIGNMENT", "STOCK_DIMENSION",
        )
    }

    @Test
    fun `standalone network work order does not fabricate customer or subscription`() {
        val json = """{"workOrderId":"$id","code":"WO-NETWORK","customerId":null,"subscriptionId":null,"orderId":null,"visitId":null,"areaId":null,"activeAssigneeIds":[],"active":true,"cancelled":false,"action":"NETWORK","workType":"REPAIR","workOrderRevision":1,"scheduledAt":null,"scheduledEndAt":null}"""
        val type = Class.forName("com.duluin.ftth.workorder.WorkOrderMaterialContext")
        assertThat(mapper.readTree(mapper.writeValueAsString(mapper.readValue(json, type)))).isEqualTo(mapper.readTree(json))
    }

    @Test
    fun `attribution takes sample context but receipt clock belongs to the owner`() {
        val type = Class.forName("com.duluin.ftth.customer.CustomerAssetObservationQuery")
        val json = """{"canonicalSerial":"ABC123","observedAt":"2026-09-07T00:00:00Z","deviceId":null,"pathId":null,"clock":"AUTHENTICATED_COLLECTOR"}"""
        val parsed = mapper.readValue(json, type)
        assertThat(type.declaredFields.map { it.name }).doesNotContain("receivedAt", "tenantId", "actorId")
        assertThat(mapper.readTree(mapper.writeValueAsString(parsed))).isEqualTo(mapper.readTree(json))
        assertThatThrownBy { mapper.readValue(json.dropLast(1) + ",\"receivedAt\":\"2026-09-07T00:00:00Z\"}", type) }
            .isInstanceOf(tools.jackson.core.JacksonException::class.java)
    }
}
