package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CustomerAssetReplacementIT : CustomerAssetReplacementRaceCases() {
    @Test
    fun `acknowledged replacement swaps one active assignment and preserves the removed loan`() {
        val case = replacementCase()
        val authorized = authorizeReplacement(case)
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()

        val swapped = request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", case.replacement.receiver.first,
            """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,
                "expectedTitleRevision":0,"evidenceId":"${case.evidence}","topology":null}""", "replacement-swap")

        assertThat(swapped.status).withFailMessage(swapped.contentAsString).isEqualTo(201)
        fixture(case.replacement.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='${case.old.installation.customer}' AND ended_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='${case.old.installation.customer}'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM onu WHERE customer_id='${case.old.installation.customer}' AND retired_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE customer_id='${case.old.installation.customer}'")).isEqualTo("2")
            assertThat(scalar("SELECT concat_ws('|',status,condition,custody_owner_kind) FROM inventory_serialized_asset WHERE id='${case.old.installation.receipt.input.lines.single().stockIdentityId}'")).isEqualTo("QUARANTINE|QUARANTINE|TRANSIT")
            assertThat(scalar("SELECT legal_owner FROM inventory_serialized_asset WHERE id='${case.old.installation.receipt.input.lines.single().stockIdentityId}'")).isEqualTo("ISP")
            assertThat(scalar("SELECT status FROM inventory_serialized_asset WHERE id='${case.replacement.input.lines.single().stockIdentityId}'")).isEqualTo("CUSTOMER_INSTALLED")
        }
    }

    @Test
    fun `replacement authorization rejects delivery that the technician has not acknowledged`() {
        val case = replacementCase(acknowledged = false)
        val before = physicalFingerprint(case.old)

        val rejected = authorizeReplacement(case)

        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(409)
        assertThat(physicalFingerprint(case.old)).isEqualTo(before)
    }

    @Test
    fun `replacement authorization rejects an old assignment belonging to another tenant`() {
        val case = replacementCase()
        val other = ownershipCase()
        val before = physicalFingerprint(case.old)

        val rejected = authorizeReplacement(case, other.installation.operation)

        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(409)
        assertThat(physicalFingerprint(case.old)).isEqualTo(before)
    }

    @ParameterizedTest
    @ValueSource(strings = ["LOAN", "SALE"])
    fun `actual dismantle preserves title and retires history without making recovered stock available`(mode: String) {
        val old = ownershipCase(mode)
        assertThat(accept(old).status).isEqualTo(200)
        val receipt = old.installation.receipt
        val order = workOrder(receipt.stock.token, "DISMANTLE", old.installation.customer.toString())
        assign(receipt.stock.token, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)

        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":${if (mode == "SALE") 1 else 0},
                "workOrderId":"$order","evidenceId":"$evidence"}""", "dismantle")

        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner) FROM inventory_serialized_asset WHERE id='${receipt.input.lines.single().stockIdentityId}'"))
                .isEqualTo("QUARANTINE|${if (mode == "SALE") "CUSTOMER" else "ISP"}")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${old.installation.operation}' AND ended_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE assignment_id='${old.installation.operation}' AND retired_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${receipt.input.lines.single().stockIdentityId}' AND status='AVAILABLE' AND quantity_base>0")).isEqualTo("0")
        }
    }

    @Test
    fun `topology detach leaves installed assignment title and all physical documents unchanged`() {
        val old = ownershipCase()
        assertThat(accept(old).status).isEqualTo(200)
        val odp = topology(old)
        val attached = request("POST", "/api/customers/onus/${old.installation.operation}/attach", old.installation.receipt.stock.token,
            """{"odpId":"$odp","portNumber":1}""")
        assertThat(attached.status).withFailMessage(attached.contentAsString).isEqualTo(200)
        val before = physicalFingerprint(old)

        val detached = request("POST", "/api/customers/onus/${old.installation.operation}/detach", old.installation.receipt.receiver.first)

        assertThat(detached.status).withFailMessage(detached.contentAsString).isEqualTo(200)
        assertThat(physicalFingerprint(old)).isEqualTo(before)
        assertThat(title(old)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `ODP relocation appends topology history without changing physical or service assignment identity`() {
        val old = ownershipCase()
        val source = topology(old)
        val destination = topology(old)
        val token = old.installation.receipt.stock.token
        val path = "/api/customers/onus/${old.installation.operation}/attach"
        assertThat(request("POST", path, token, """{"odpId":"$source","portNumber":1}""").status).isEqualTo(200)
        val before = physicalFingerprint(old)

        val relocated = request("POST", path, token, """{"odpId":"$destination","portNumber":2}""")

        assertThat(relocated.status).withFailMessage(relocated.contentAsString).isEqualTo(200)
        assertThat(physicalFingerprint(old)).isEqualTo(before)
        fixture(token).transaction {
            assertThat(scalar("SELECT odp_id::text FROM onu WHERE assignment_id='${old.installation.operation}'")).isEqualTo(destination.toString())
            assertThat(scalar("SELECT count(*) FROM onu_topology_history WHERE assignment_id='${old.installation.operation}'")).isEqualTo("3")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${old.installation.operation}' AND ended_at IS NULL")).isEqualTo("1")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CUSTOMER", "ONU"])
    fun `destructive delete returns a typed conflict for an active detached physical assignment`(target: String) {
        val old = ownershipCase()
        val path = when (target) {
            "CUSTOMER" -> "/api/customers/${old.installation.customer}"
            "ONU" -> "/api/customers/onus/${old.installation.operation}"
            else -> error("Unknown target")
        }
        val before = physicalFingerprint(old)

        val rejected = request("DELETE", path, old.installation.receipt.stock.token)

        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(409)
        assertThat(physicalFingerprint(old)).isEqualTo(before)
    }
}
