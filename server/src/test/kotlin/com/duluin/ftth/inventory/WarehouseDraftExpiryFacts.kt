package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseDraftExpiryService
import org.assertj.core.api.Assertions.assertThat
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Physical facts and original document bytes must survive expiry without a compensating effect. */
internal object WarehouseDraftExpiryFacts {
    fun capture(database: WarehousePostingFixture, document: String): String = database.transaction {
        scalar("""SELECT jsonb_build_array(
            (SELECT to_jsonb(d) FROM inventory_document d WHERE id='$document'),
            (SELECT jsonb_agg(to_jsonb(l) ORDER BY l.id) FROM inventory_document_line l WHERE document_id='$document'),
            ${(listOf("inventory_balance_projection", "inventory_serialized_asset", "inventory_asset_assignment",
                "inventory_movement", "inventory_asset_title_transfer", "inventory_return_case", "inventory_asset_recovery_obligation",
                "inventory_repair_replacement_receipt", "inventory_repair_case", "inventory_asset_recovery_transition")
                .joinToString(",") { "(SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text) FROM $it t)" })})::text""")
    }

    fun expire(database: WarehousePostingFixture, document: String) {
        TenantContext.runAs(database.tenant) {
            assertThat(database.context.getBean(WarehouseDraftExpiryService::class.java).expireOne()).isTrue()
        }
        database.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_document_draft_expiry WHERE document_id='$document'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_effect WHERE source_document_id='$document'")).isEqualTo("0")
        }
    }

    fun rejected(response: MockHttpServletResponse) {
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(jacksonObjectMapper().readTree(response.contentAsString).path("code").asString()).isEqualTo("DRAFT_EXPIRED")
    }
}
