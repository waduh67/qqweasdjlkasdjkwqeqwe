package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WorkOrderMaterialReceiptIT : MaterialReceiptFixture() {
    @Test fun `same key returns original bytes when receipt already posted`() {
        val case = receiptCase()
        val original = received(case)
        val before = accounting(case)

        val replay = acknowledge(case)

        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(original)
        assertThat(accounting(case)).isEqualTo(before)
        val id = mapper.readTree(original).path("receiptId").asString()
        assertThat(request("GET", "/api/v1/warehouse/my-material-receipts/$id", case.receiver.first).contentAsString).isEqualTo(original)
    }

    @Test fun `remaining receipt closes issue when all dispatched quantity accepted`() {
        val case = receiptCase()
        received(case)
        val remainder = case.input.copy(expectedRevision = 3, lines = case.input.lines.map { it.copy(acceptedBase = "40000", missingBase = "0") })

        val result = acknowledge(case, remainder, "remainder")

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(accounting(case)).startsWith("RECEIVED|4|0|100000|2|")
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE state='SPLIT'")).isEqualTo("2")
            assertThat(scalar("SELECT sum(missing_base) FROM inventory_material_receipt_line")).isEqualTo("40000")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["100001", "-1", "1.5", "9223372036854775808", "060000"])
    fun `invalid quantities cannot mutate transit`(quantity: String) {
        val case = receiptCase()
        val before = accounting(case)

        val result = acknowledge(case, case.input.copy(lines = case.input.lines.map { it.copy(acceptedBase = quantity, missingBase = "0") }))

        assertThat(result.status).isIn(400, 409)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `changed payload conflicts when key already used`() {
        val case = receiptCase()
        received(case)
        val before = accounting(case)

        val result = acknowledge(case, case.input.copy(expectedRevision = 3))

        assertThat(result.status).isEqualTo(409)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `foreign stock identity cannot replace dispatched identity`() {
        val case = receiptCase()
        val before = accounting(case)

        val result = acknowledge(case, case.input.copy(lines = case.input.lines.map { it.copy(stockIdentityId = UUID.randomUUID()) }))

        assertThat(result.status).isEqualTo(409)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `stale issue revision cannot acknowledge with a new key`() {
        val case = receiptCase()
        received(case)
        val before = accounting(case)

        val result = acknowledge(case, key = "stale-new-key")

        assertThat(result.status).isEqualTo(409)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @ParameterizedTest @ValueSource(strings = ["actorId", "tenantId", "receiverId", "movementId", "destinationCustody", "approver"])
    fun `authority body fields are rejected before movement`(field: String) {
        val case = receiptCase()
        val before = accounting(case)
        val body = mapper.writeValueAsString(case.input).dropLast(1) + ",\"$field\":\"${UUID.randomUUID()}\"}"

        val result = request("POST", "/api/work-orders/${case.workOrder}/materials/acknowledge", case.receiver.first, body)

        assertThat(result.status).isEqualTo(400)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `foreign tenant cannot read or acknowledge issue`() {
        val case = receiptCase()
        val foreign = tenant()
        val before = accounting(case)

        val result = request("POST", "/api/work-orders/${case.workOrder}/materials/acknowledge", foreign, mapper.writeValueAsString(case.input))

        assertThat(result.status).isEqualTo(404)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `administrator cannot impersonate named receiver`() {
        val case = receiptCase()
        val before = accounting(case)

        val result = request("POST", "/api/work-orders/${case.workOrder}/materials/acknowledge", case.stock.token, mapper.writeValueAsString(case.input))

        assertThat(result.status).isIn(403, 409)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `revoked transit scope denies replay and private receipt despite original token`() {
        val case = receiptCase()
        val original = received(case)
        val before = accounting(case)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receiver.second}/${case.transit}", case.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)

        val replay = acknowledge(case)

        assertThat(replay.status).isEqualTo(404)
        val id = mapper.readTree(original).path("receiptId").asString()
        assertThat(request("GET", "/api/v1/warehouse/my-material-receipts/$id", case.receiver.first).status).isEqualTo(404)
        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test fun `reassignment retains custody and own receipt but denies further acknowledgement`() {
        val case = receiptCase()
        val original = received(case)
        val replacement = technician(case.stock.token)
        assign(case.stock.token, case.workOrder, replacement.second)
        val before = accounting(case)

        val result = acknowledge(case)

        assertThat(result.status).isIn(403, 409)
        assertThat(accounting(case)).isEqualTo(before)
        val id = mapper.readTree(original).path("receiptId").asString()
        val own = request("GET", "/api/v1/warehouse/my-material-receipts/$id", case.receiver.first)
        assertThat(own.status).isEqualTo(200)
        assertThat(own.contentAsString).isEqualTo(original)
        assertThat(request("GET", "/api/v1/warehouse/my-material-receipts/$id", replacement.first).status).isEqualTo(403)
    }

    @Test fun `serial subset enters custody only with exact dispatched serial`() {
        val case = receiptCase(serial = true)
        val before = accounting(case)
        val wrong = case.input.copy(lines = case.input.lines.map { it.copy(serial = "OTHER-SERIAL") })
        assertThat(acknowledge(case, wrong).status).isEqualTo(400)
        assertThat(accounting(case)).isEqualTo(before)

        received(case)

        assertThat(accounting(case)).startsWith("PART_RECEIVED|3|1|1|1|")
        assertThat(acknowledge(case, case.input.copy(expectedRevision = 3), "double-serial").status).isEqualTo(409)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset WHERE custody_owner_kind='TECHNICIAN' AND status='ISSUED'")).isEqualTo("1")
        }
    }

    @ParameterizedTest @ValueSource(strings = ["snapshot", "line", "state", "no-tenant", "foreign-tenant", "restored-tenant"])
    fun `app role cannot rewrite receipt or fabricate received lifecycle`(mode: String) {
        val case = receiptCase()
        received(case)
        val before = accounting(case)

        assertThatThrownBy { fixture(case.stock.token).transaction {
            when (mode) {
                "snapshot" -> sql("UPDATE inventory_material_receipt SET evidence_reference='rewritten'")
                "line" -> sql("UPDATE inventory_material_receipt_line SET accepted_base=100000")
                else -> {
                    val tenant = scalar("SELECT current_setting('app.tenant_id')")
                    sql("UPDATE inventory_document SET state='RECEIVED',revision=revision+1 WHERE id='${case.input.issueId}'")
                    if (mode == "no-tenant" || mode == "restored-tenant") sql("SET LOCAL app.tenant_id=''")
                    if (mode == "foreign-tenant") sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    if (mode == "restored-tenant") sql("SET LOCAL app.tenant_id='$tenant'")
                    sql("SET CONSTRAINTS warehouse_material_receipt_issue_bound IMMEDIATE")
                }
            }
        } }.hasStackTraceContaining(if (mode in setOf("no-tenant", "foreign-tenant")) "row tenant scope" else if (mode in setOf("snapshot", "line")) "warehouse_append_only" else "quantitative acknowledgement")

        assertThat(accounting(case)).isEqualTo(before)
    }

    @Test
    fun `acknowledgement moves sixty metres into named receiver custody when one hundred metres dispatched`() {
        val stock = setupReceipt()
        receiveStock(stock, "1000000")
        val receiver = technician(stock.token)
        val workOrder = workOrder(stock.token)
        assign(stock.token, workOrder, receiver.second)
        putPlan(stock.token, workOrder, plan(stock.token, workOrder, "[${line(stock.cable)}]"))
        action(stock.token, workOrder, "submit-request", command(stock.token, workOrder, 1))
        action(stock.token, workOrder, "reserve", command(stock.token, workOrder, 1))
        val transit = create("locations", stock.token, """{"code":"WO_TRANSIT","name":"Transit","kind":"TRANSIT"}""").path("id").asString()
        val setup = IssueSetup(stock, workOrder, receiver.second)
        val field = create("locations", setup.stock.token, """{"code":"FIELD_STOCK","name":"Accountable field stock","kind":"TECHNICIAN","custodianId":"${receiver.second}"}""").path("id").asString()
        for (location in listOf(stock.bin, transit, field)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receiver.second}/$location", stock.token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val picked = action(setup.stock.token, setup.workOrder, "pick", pickBody(setup))
        val dispatched = action(setup.stock.token, setup.workOrder, "dispatch", transitionBody(setup, picked))
        val source = dispatched.path("lines")[0]
        summary(receiver.first, workOrder)
        val body = mapper.writeValueAsString(mapOf(
            "issueId" to dispatched.path("issueId").asString(),
            "expectedRevision" to dispatched.path("revision").asLong(),
            "workOrderRevision" to dispatched.path("workOrderRevision").asLong(),
            "evidenceReference" to "signed-handover-60m",
            "lines" to listOf(mapOf("issueLineId" to source.path("id").asString(),
                "stockIdentityId" to source.path("dimension").path("stockIdentityId").asString(),
                "acceptedBase" to "60000", "missingBase" to "40000", "rejectedBase" to "0",
                "baseUnit" to "MM", "reason" to "Forty metres not delivered"))))

        val result = request("POST", "/api/work-orders/${setup.workOrder}/materials/acknowledge", receiver.first, body, "receive-sixty")

        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(result.contentAsString).path("state").asString()).isEqualTo("PART_RECEIVED")
        fixture(setup.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='IN_TRANSIT'"))
                .isEqualTo("40000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE custody_owner_kind='TECHNICIAN' AND custody_owner_id='${receiver.second}' AND condition='SERVICEABLE'"))
                .isEqualTo("60000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='${setup.stock.bin}'"))
                .isEqualTo("900000")
        }
    }

    @Test
    fun `dispatch leaves all material in transit when receiver has not acknowledged`() {
        val setup = issuedSetup()
        val picked = action(setup.stock.token, setup.workOrder, "pick", pickBody(setup))

        val dispatched = issueRequest(setup, "dispatch", transitionBody(setup, picked))

        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(dispatched.contentAsString).path("state").asString()).isEqualTo("DISPATCHED")
        fixture(setup.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='IN_TRANSIT'"))
                .isEqualTo("100000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='${setup.stock.bin}'"))
                .isEqualTo("900000")
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE custody_owner_kind='TECHNICIAN'"))
                .isEqualTo("0")
            assertThat(scalar("SELECT sum(accepted_base) FROM inventory_document_line WHERE document_id='${picked.path("issueId").asString()}'"))
                .isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='ISSUE' AND state IN ('RECEIVED','PART_RECEIVED')"))
                .isEqualTo("0")
        }
    }
}
