package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

class CustomerAssetAuthorizationITPurposes : CustomerAssetAuthorizationFixture() {
    @ParameterizedTest
    @ValueSource(strings=["REMOVE", "REPLACE"])
    fun `purpose-specific verified source bindings have valid controls`(purpose: String) {
        val fixture = episodeCase()
        val prior = UUID.randomUUID()
        val permit = UUID.randomUUID()
        fixture.stock.transaction {
            val priorSql = when (purpose) {
                "REPLACE" -> {
                    val other = scalar("SELECT id FROM inventory_serialized_asset WHERE id<>'${fixture.asset}' LIMIT 1")
                    val line = scalar("SELECT id FROM inventory_issue_line WHERE stock_identity_id='$other'")
                    fixture.assignment(prior).replace(fixture.asset.toString(), other).replace(fixture.issueLine.toString(), line)
                }
                "REMOVE" -> fixture.assignment(prior)
                else -> error("Unknown purpose")
            }
            sql(priorSql)
        }
        val command = fixture.authorization(permit, purpose).replace(",NULL,NULL\n", ",'$prior',0\n")
        fixture.stock.transaction { sql(command) }
        fixture.stock.transaction {
            assertThat(scalar("SELECT (warehouse_read_deployment_authorization('$tenant','$permit')).purpose")).isEqualTo(purpose)
        }
    }

    @Test
    fun `changing title alone cannot turn an original issue into an inspected customer RMA source`() {
        val fixture = episodeCase()
        val prior = UUID.randomUUID()
        val permit = UUID.randomUUID()
        fixture.stock.transaction {
            sql("UPDATE inventory_serialized_asset SET legal_owner='CUSTOMER',revision=revision+1 WHERE id='${fixture.asset}'")
            sql(fixture.assignment(prior).replace("'LOAN','ISP'", "'SALE','CUSTOMER'"))
        }
        val command = fixture.authorization(permit, "RETURN_CUSTOMER_RMA")
            .replace(",NULL,NULL\n", ",'$prior',0\n").replace("'LOAN'", "'SALE'")
        rejection { fixture.stock.transaction { sql(command) } }
        fixture.stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization WHERE id='$permit'")).isEqualTo("0")
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["REMOVE", "REPLACE", "RETURN_CUSTOMER_RMA"])
    fun `purpose cannot omit its prior assignment source`(purpose: String) {
        val fixture = episodeCase()
        rejection { fixture.stock.transaction { sql(fixture.authorization(UUID.randomUUID(), purpose)) } }
    }

    @ParameterizedTest
    @ValueSource(strings=["ISSUE_NULL", "ISSUE_REVISION_NULL", "ISSUE_REVISION_UNKNOWN", "WRONG_WO", "INVALID_PURPOSE"])
    fun `new authorization rejects malformed or mismatched source bindings`(scenario: String) {
        val fixture = episodeCase()
        val command = fixture.authorization(UUID.randomUUID())
        val query = when (scenario) {
            "ISSUE_NULL" -> command.replace("asset.id,'${fixture.issueLine}'", "asset.id,NULL")
            "ISSUE_REVISION_NULL" -> command.replace("SELECT document.revision", "SELECT NULL::bigint")
            "ISSUE_REVISION_UNKNOWN" -> command.replace("SELECT document.revision", "SELECT document.revision+100")
            "WRONG_WO" -> command.replace("asset.id,'${fixture.issueLine}','${fixture.workOrder}'", "asset.id,'${fixture.issueLine}','${fixture.stock.workOrder}'")
            "INVALID_PURPOSE" -> command.replace("'INSTALL'", "'RECOVERY'")
            else -> error("Unknown source scenario")
        }
        val failure = assertThrows<Exception> { fixture.stock.transaction { sql(query) } }
        val state = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState
        assertThat(state).isIn("23514", "23503")
    }

    @ParameterizedTest
    @ValueSource(strings=["ASSET", "CUSTOMER", "WO", "ACTOR", "ISSUE", "REVISION", "TENANT", "AUTHORITY_EPOCH", "CUTOVER_EPOCH", "DELETE", "HISTORY_DELETE", "HISTORY_UPDATE"])
    fun `authorization bindings and history cannot be mutated`(field: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.authorization(id)) }
        val action = when (field) {
            "ASSET" -> "UPDATE inventory_deployment_authorization SET asset_id='${UUID.randomUUID()}' WHERE id='$id'"
            "CUSTOMER" -> "UPDATE inventory_deployment_authorization SET customer_id='${fixture.customerB}' WHERE id='$id'"
            "WO" -> "UPDATE inventory_deployment_authorization SET work_order_id='${UUID.randomUUID()}' WHERE id='$id'"
            "ACTOR" -> "UPDATE inventory_deployment_authorization SET actor_id='${UUID.randomUUID()}' WHERE id='$id'"
            "ISSUE" -> "UPDATE inventory_deployment_authorization SET issue_line_id=NULL WHERE id='$id'"
            "REVISION" -> "UPDATE inventory_deployment_authorization SET expected_asset_revision=expected_asset_revision+1 WHERE id='$id'"
            "TENANT" -> "UPDATE inventory_deployment_authorization SET tenant_id='${UUID.randomUUID()}' WHERE id='$id'"
            "AUTHORITY_EPOCH" -> "UPDATE inventory_deployment_authorization SET authority_epoch=authority_epoch+1 WHERE id='$id'"
            "CUTOVER_EPOCH" -> "UPDATE inventory_deployment_authorization SET cutover_epoch=cutover_epoch+1 WHERE id='$id'"
            "DELETE" -> "DELETE FROM inventory_deployment_authorization WHERE id='$id'"
            "HISTORY_DELETE" -> "DELETE FROM inventory_deployment_authorization_history WHERE authorization_id='$id'"
            "HISTORY_UPDATE" -> "UPDATE inventory_deployment_authorization_history SET snapshot='{}' WHERE authorization_id='$id'"
            else -> error("Unknown binding field")
        }
        rejection { fixture.stock.transaction { sql(action) } }
    }

    @Test
    fun `foreign tenant actor cannot be used as immutable authorization authority`() {
        val fixture = episodeCase()
        val foreign = fixture(tenant())
        val actor = foreign.transaction { scalar("SELECT id FROM app_user LIMIT 1") }
        val command = fixture.authorization(UUID.randomUUID()).replace("'${fixture.actor}'", "'$actor'")
        val failure = assertThrows<Exception> { fixture.stock.transaction { sql(command) } }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23503")
    }

    @Test
    fun `ordinary RMA return cannot claim ISP title as customer-owned recovery`() {
        val fixture = episodeCase()
        val previous = UUID.randomUUID()
        fixture.stock.transaction { sql(fixture.assignment(previous)) }
        val command = fixture.authorization(UUID.randomUUID(), "RETURN_CUSTOMER_RMA").replace(",NULL,NULL\n", ",'$previous',0\n")
        assertThat(rejection { fixture.stock.transaction { sql(command) } }.message).contains("customer-owned")
    }

    @Test
    fun `retired identity cannot authorize a new deployment`() {
        val fixture = episodeCase()
        fixture.stock.transaction {
            sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE stock_identity_id='${fixture.asset}'")
            sql("UPDATE inventory_segment SET state='RETIRED',revision=revision+1 WHERE id='${fixture.asset}'")
            sql("UPDATE inventory_identity_claim SET state='RETIRED',revision=revision+1 WHERE admitted_asset_id='${fixture.asset}'")
        }
        rejection { fixture.stock.transaction { sql(fixture.authorization(UUID.randomUUID())) } }
    }
}
