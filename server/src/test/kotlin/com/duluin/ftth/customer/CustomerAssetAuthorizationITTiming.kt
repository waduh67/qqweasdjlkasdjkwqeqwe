package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.Arguments
import java.util.UUID

class CustomerAssetAuthorizationITTiming : CustomerAssetAuthorizationFixture() {
    @Test
    fun `authorization source coverage includes INSERT UPDATE DELETE and history`() {
        val tables = context.getBean(javax.sql.DataSource::class.java).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT relation.relname FROM pg_trigger entry
                    JOIN pg_class relation ON relation.oid=entry.tgrelid
                    WHERE relation.relnamespace='public'::regnamespace AND entry.tgdeferrable
                        AND entry.tgname IN ('warehouse_zz_authorization_final','warehouse_zz_authorization_history_final','warehouse_authorization_source_final')
                        AND (entry.tgtype::integer & 28)=28""").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }
        assertThat(tables).containsExactlyInAnyOrder("inventory_deployment_authorization", "inventory_deployment_authorization_history",
            "inventory_serialized_asset", "inventory_identity_claim", "inventory_segment", "inventory_issue_line",
            "inventory_document_line", "inventory_document", "inventory_asset_assignment", "inventory_material_receipt_line", "inventory_material_receipt")
    }

    companion object {
        @JvmStatic fun cases() = listOf("warehouse_zz_authorization_final", "warehouse_zz_authorization_history_final", "warehouse_authorization_source_final")
            .flatMap { constraint -> listOf("NORMAL", "CLEARED", "MISMATCHED", "RESTORED").map { scope -> Arguments.of(constraint, scope) } }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `each final authorization validator owns its tenant assertion`(constraint: String, scope: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        if (constraint == "warehouse_authorization_source_final") fixture.stock.transaction { sql(fixture.authorization(id)) }
        val action = {
            fixture.stock.transaction {
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS $constraint DEFERRED")
                if (constraint == "warehouse_authorization_source_final")
                    sql("UPDATE inventory_serialized_asset SET revision=revision+1 WHERE id='${fixture.asset}'")
                else sql(fixture.authorization(id))
                when (scope) {
                    "NORMAL" -> Unit
                    "CLEARED" -> sql("SET LOCAL app.tenant_id=''")
                    "MISMATCHED" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "RESTORED" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    else -> error("Unknown scope")
                }
            }
        }
        if (scope in setOf("NORMAL", "RESTORED")) action()
        else assertThat(rejection(action).message).contains("row tenant scope")
    }

    @Test
    fun `source changes after early authorization validation recheck actual final truth`() {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val failure = rejection {
            fixture.stock.transaction {
                sql(fixture.authorization(id))
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS warehouse_authorization_source_final DEFERRED")
                sql("UPDATE inventory_serialized_asset SET status='DISPOSED',revision=revision+1 WHERE id='${fixture.asset}'")
            }
        }
        assertThat(failure.message).contains("retired physical asset")
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization_history WHERE authorization_id='$id'")).isEqualTo("0")
        }
    }
}
