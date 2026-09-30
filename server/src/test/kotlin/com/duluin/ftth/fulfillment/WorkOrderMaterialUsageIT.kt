package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialUsageRequest
import java.util.UUID

class WorkOrderMaterialUsageIT : MaterialUsageFixture() {
    @Test fun `same key replays exact immutable bytes when usage already posted`() {
        val case = usageCase()
        val original = used(case)
        val before = usageAccounting(case)

        val replay = use(case)

        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(original)
        assertThat(usageAccounting(case)).isEqualTo(before)
        val id = mapper.readTree(original).path("usageId").asString()
        assertThat(request("GET", "/api/v1/warehouse/my-material-usage/$id", case.receipt.receiver.first).contentAsString).isEqualTo(original)
    }

    @ParameterizedTest @ValueSource(strings = ["101000", "0", "-1", "82500.0", "82.500", "082500", "9223372036854775808"])
    fun `invalid measured quantity leaves custody unchanged`(quantity: String) {
        val case = usageCase()
        val before = usageAccounting(case)

        val response = use(case, case.input.copy(lines = case.input.lines.map { it.copy(quantityBase = quantity) }))

        assertThat(response.status).isIn(400, 409)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["duplicate", "identity", "receipt", "empty", "stale-plan", "stale-wo", "stale-use", "none"])
    fun `invalid source or revision cannot post physical use`(mode: String) {
        val case = usageCase()
        val before = usageAccounting(case)
        val input = when (mode) {
            "duplicate" -> case.input.copy(lines = case.input.lines + case.input.lines)
            "identity" -> case.input.copy(lines = case.input.lines.map { it.copy(stockIdentityId = UUID.randomUUID()) })
            "receipt" -> case.input.copy(lines = case.input.lines.map { it.copy(receiptId = UUID.randomUUID()) })
            "empty" -> case.input.copy(lines = emptyList())
            "stale-plan" -> case.input.copy(planRevision = 2)
            "stale-wo" -> case.input.copy(workOrderRevision = 0)
            "stale-use" -> case.input.copy(expectedRevision = 1)
            "none" -> case.input.copy(materialMode = MaterialMode.NONE, lines = emptyList(), reason = "No materials")
            else -> error("Unknown fixture")
        }

        val response = use(case, input)

        assertThat(response.status).isIn(400, 404, 409)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["tenantId", "actorId", "custodianId", "movementId", "postingId", "approver", "destination", "policy", "customerId"])
    fun `client authority fields are rejected without physical effects`(field: String) {
        val case = usageCase()
        val before = usageAccounting(case)
        val body = mapper.writeValueAsString(case.input).dropLast(1) + ",\"$field\":\"${UUID.randomUUID()}\"}"

        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/materials/report-use", case.receipt.receiver.first, body)

        assertThat(response.status).isEqualTo(400)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["new-key", "changed-payload", "correction"])
    fun `existing usage cannot be resubmitted or overwritten`(mode: String) {
        val case = usageCase()
        used(case)
        val before = usageAccounting(case)

        val response = when (mode) {
            "new-key" -> use(case, key = "new-key")
            "changed-payload" -> use(case, case.input.copy(evidenceReference = "changed"))
            "correction" -> use(case, case.input.copy(expectedRevision = 1), "correction")
            else -> error("Unknown fixture")
        }

        assertThat(response.status).isEqualTo(409)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["admin", "foreign", "scope", "reassign", "permission"])
    fun `current authority is checked before stored usage is returned`(mode: String) {
        val case = usageCase()
        used(case)
        val receipt = case.receipt
        val token = when (mode) {
            "admin" -> receipt.stock.token
            "foreign" -> tenant()
            "scope" -> {
                assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${receipt.field}", receipt.stock.token,
                    """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
                receipt.receiver.first
            }
            "reassign" -> { assign(receipt.stock.token, receipt.workOrder, technician(receipt.stock.token).second); receipt.receiver.first }
            "permission" -> {
                assertThat(request("PUT", "/api/users/${receipt.receiver.second}/access", receipt.stock.token, """{"roleIds":[],"areaIds":[]}""").status).isEqualTo(200)
                receipt.receiver.first
            }
            else -> error("Unknown fixture")
        }
        val before = usageAccounting(case)

        val response = request("POST", "/api/work-orders/${receipt.workOrder}/materials/report-use", token, mapper.writeValueAsString(case.input), "measured-use")

        assertThat(response.status).isIn(403, 404, 409)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `serialized equipment remains issued when measured usage is requested`() {
        val case = usageCase(serial = true)
        val before = usageAccounting(case)

        val response = use(case)

        assertThat(response.status).isEqualTo(409)
        assertThat(usageAccounting(case)).isEqualTo(before)
    }

    @Test fun `fungible EA consumption retains exactly eighteen acknowledged units`() {
        val case = usageCase(fungible = true)

        val response = use(case)

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(usageAccounting(case)).startsWith("18|82|1|1|1|1|")
    }

    @Test fun `explicit NONE usage creates a snapshot without physical effects`() {
        val stock = setupReceipt()
        val receiver = technician(stock.token)
        val wo = workOrder(stock.token)
        assign(stock.token, wo, receiver.second)
        putPlan(stock.token, wo, plan(stock.token, wo, "[]", mode = "NONE", reason = "Inspection only"))
        action(stock.token, wo, "submit-request", command(stock.token, wo, 1))
        val revision = summary(receiver.first, wo).path("revisions").path("workOrderRevision").asLong()
        val input = MaterialUsageRequest(0, 1, revision, MaterialMode.NONE, "inspection-log", emptyList(), "Inspection only")

        val response = request("POST", "/api/work-orders/$wo/materials/report-use", receiver.first, mapper.writeValueAsString(input))

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_usage_snapshot WHERE material_mode='NONE' AND cardinality(posting_ids)=0")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
        }
    }

    @Test
    fun `reported use consumes eighty two point five metres when one hundred metres acknowledged`() {
        val case = receiptCase()
        create("locations", case.stock.token, """{"code":"CONSUMED","name":"Physical consumption sink","kind":"TRANSIT"}""")
        val acknowledgement = acknowledge(case, case.input.copy(lines = case.input.lines.map {
            it.copy(acceptedBase = "100000", missingBase = "0")
        }))
        assertThat(acknowledgement.status).withFailMessage(acknowledgement.contentAsString).isEqualTo(200)
        val receipt = mapper.readTree(acknowledgement.contentAsString)
        val body = mapper.writeValueAsString(mapOf(
            "expectedRevision" to 0, "planRevision" to 1,
            "workOrderRevision" to case.input.workOrderRevision,
            "materialMode" to "MATERIAL_REQUIRED", "evidenceReference" to "measured-cable-82.500m",
            "lines" to listOf(mapOf(
                "receiptId" to receipt.path("receiptId").asString(),
                "issueLineId" to case.input.lines.single().issueLineId,
                "stockIdentityId" to receipt.path("lines")[0].path("accepted").path("stockIdentityId").asString(),
                "quantityBase" to "82500", "baseUnit" to "MM"))))

        val result = request("POST", "/api/work-orders/${case.workOrder}/materials/report-use", case.receiver.first, body, "use-82.500m")

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(case.stock.token).transaction {
            assertThat(scalar("""SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection
                WHERE custody_owner_kind='TECHNICIAN' AND custody_owner_id='${case.receiver.second}' AND status='ISSUED'"""))
                .isEqualTo("17500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'"))
                .isEqualTo("82500")
            assertThat(scalar("SELECT count(*) FROM inventory_usage_snapshot")).isEqualTo("1")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_customer_material_fact")).isEqualTo("82500")
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("1")
        }
        val material = summary(case.receiver.first, case.workOrder).path("lines")[0]
        assertThat(material.path("physicallyUsedBase").asString()).isEqualTo("82500")
        assertThat(material.path("stillAccountableBase").asString()).isEqualTo("17500")
    }

    @Test
    fun `acknowledgement leaves one hundred metres accountable when no usage is reported`() {
        val case = receiptCase()
        val input = case.input.copy(lines = case.input.lines.map {
            it.copy(acceptedBase = "100000", missingBase = "0")
        })

        val result = acknowledge(case, input)

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(case.stock.token).transaction {
            assertThat(scalar("""SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection
                WHERE custody_owner_kind='TECHNICIAN' AND custody_owner_id='${case.receiver.second}'
                AND status='ISSUED'""")).isEqualTo("100000")
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='CONSUMED'"))
                .isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_usage_snapshot")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
            assertThat(scalar("SELECT sum(accepted_base) FROM inventory_document_line WHERE document_id='${case.input.issueId}'"))
                .isEqualTo("0")
        }
    }
}
