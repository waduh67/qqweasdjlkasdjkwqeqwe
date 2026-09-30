package com.duluin.ftth.customer

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.inventory.AssetLegalOwner
import com.duluin.ftth.inventory.AssetTitleCorrectionRequest
import com.duluin.ftth.inventory.InventoryAssetTitleApi
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.util.UUID

abstract class CustomerAssetTitleScopeCases : CustomerAssetTitleRaceCases() {
    @ParameterizedTest
    @ValueSource(strings = ["actorId", "tenantId", "customerId", "legalOwner", "approvedBy"])
    fun `correction authority fields reject before owner replay`(field: String) {
        val case = correctionCase()
        assertThat(correction(case).status).isEqualTo(201)

        val rejected = request("POST", "/api/v1/warehouse/asset-title-corrections", case.ownership.installation.receipt.stock.token,
            """{"assignmentId":"${case.ownership.installation.operation}","sourceHandoverId":"${case.handover}","expectedAssignmentRevision":1,"expectedTitleRevision":1,"targetOwner":"ISP","reason":"Independent title correction","evidenceId":"${case.ownership.signature}","$field":"${UUID.randomUUID()}"}""", "correction")

        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(400)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NORMAL", "CLEARED", "MISMATCHED", "RESTORED", "SELECTIVE"])
    fun `correction request final state rejects cleared and mismatched tenant context`(scope: String) {
        val case = correctionCase()
        val token = case.ownership.installation.receipt.stock.token
        val stock = fixture(token)
        val previous = SecurityContextHolder.getContext()
        val security = SecurityContextHolder.createEmptyContext()
        security.authentication = JwtAuthenticationConverter().convert(context.getBean(JwtDecoder::class.java).decode(token))
        SecurityContextHolder.setContext(security)
        val submit = { stock.transaction {
            context.getBean(InventoryAssetTitleApi::class.java).requestCorrection(AssetTitleCorrectionRequest(
                case.ownership.installation.operation, UUID.fromString(case.handover), 1, 1, AssetLegalOwner.ISP,
                "Tenant boundary correction", case.ownership.signature), WarehouseMutationMetadata("scoped-request"))
            if (scope == "SELECTIVE") {
                val others = scalar("""SELECT string_agg(DISTINCT quote_ident(trigger.tgname),',') FROM pg_trigger trigger
                    JOIN pg_class relation ON relation.oid=trigger.tgrelid WHERE relation.relnamespace=current_schema()::regnamespace
                    AND trigger.tgdeferrable AND NOT trigger.tgisinternal AND trigger.tgname<>'warehouse_title_final'""")
                sql("SET CONSTRAINTS $others IMMEDIATE")
            }
            if (scope != "NORMAL") sql("SELECT set_config('app.tenant_id','${if (scope == "CLEARED") "" else UUID.randomUUID()}',true)")
            if (scope == "RESTORED") sql("SELECT set_config('app.tenant_id','$tenant',true)")
        } }

        try {
            if (scope in setOf("NORMAL", "RESTORED")) submit() else assertThrows<Exception> { submit() }
        } finally { SecurityContextHolder.setContext(previous) }

        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_request")).isEqualTo(if (scope in setOf("NORMAL", "RESTORED")) "1" else "0")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer")).isEqualTo("0")
        }
    }

    @Test
    fun `foreign tenant cannot read or reference another customers approved transfer`() {
        val pending = pendingCorrection()
        assertThat(decideCorrection(pending).status).isEqualTo(200)
        val origin = fixture(pending.case.ownership.installation.receipt.stock.token)
        val transfer = origin.transaction { scalar("SELECT id FROM inventory_asset_title_transfer WHERE request_id='${pending.document}'") }
        val foreign = setupReceipt()

        val response = request("GET", "/api/customers/${pending.case.ownership.installation.customer}/assets/ownership", foreign.token)

        assertThat(response.status).isEqualTo(404)
        val stock = fixture(foreign.token)
        stock.transaction { assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer WHERE id='$transfer'")).isEqualTo("0") }
        assertThrows<Exception> { stock.transaction {
            sql("""INSERT INTO inventory_asset_recovery_transition(tenant_id,transfer_id,assignment_id,asset_id,customer_id,required)
                VALUES ('$tenant','$transfer','${pending.case.ownership.installation.operation}',
                '${pending.case.ownership.installation.receipt.input.lines.single().stockIdentityId}','${pending.case.ownership.installation.customer}',true)""")
        } }
    }
}
