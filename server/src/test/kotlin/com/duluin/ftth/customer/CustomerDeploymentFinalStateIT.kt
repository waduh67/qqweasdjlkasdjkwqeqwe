package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID
import java.util.stream.Stream

class CustomerDeploymentFinalStateIT : CustomerDeploymentGraphFixture() {
    @Test
    fun `deployment validators cover insert update delete without elevated execution`() {
        val install = installation()

        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("""SELECT count(*) FROM pg_trigger tg JOIN pg_class table_info ON table_info.oid=tg.tgrelid
                WHERE table_info.relnamespace=current_schema()::regnamespace AND tg.tgname='warehouse_deployment_fact_final'
                    AND (tg.tgtype::int & 28)=28 AND tg.tgdeferrable AND tg.tginitdeferred""")).isEqualTo("1")
            assertThat(scalar("""SELECT count(*) FROM pg_trigger tg JOIN pg_class table_info ON table_info.oid=tg.tgrelid
                WHERE table_info.relnamespace=current_schema()::regnamespace AND tg.tgname='warehouse_deployment_document_final'
                    AND (tg.tgtype::int & 28)=28 AND tg.tgdeferrable AND tg.tginitdeferred""")).isEqualTo("6")
            assertThat(scalar("""SELECT count(*) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND NOT prosecdef
                AND proname IN ('warehouse_assert_deployment_facts','warehouse_deployment_fact_final_guard',
                    'warehouse_assert_deployment_document','warehouse_deployment_document_final_guard')""")).isEqualTo("4")
        }
    }

    companion object {
        @JvmStatic fun mutations(): Stream<Arguments> = buildList {
            for (mutation in listOf("EXTRA_FACT", "SUBSTITUTED_FACT", "ORPHAN_DOCUMENT", "EXTRA_LINE", "MISSING_LINE", "SUBSTITUTED_LINE",
                "MISSING_DOCUMENT", "MISSING_RESULT", "MISSING_OPERATION", "MISSING_POSTING")) {
                for (scope in listOf("NORMAL", "CLEARED", "MISMATCHED", "RESTORED", "SELECTIVE")) add(Arguments.of(mutation, scope))
            }
        }.stream()
    }

    @ParameterizedTest
    @MethodSource("mutations")
    fun `invalid final deployment graph rejects under every tenant timing`(mutation: String, scope: String) {
        val install = installation()
        val original = consume(install)
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val orphan = UUID.randomUUID()
        val constraint = if (mutation.endsWith("FACT")) "warehouse_deployment_fact_final" else "warehouse_deployment_document_final"

        val failure = assertThrows<Exception> { fixture(install.receipt.stock.token).transaction {
            if (scope == "SELECTIVE") {
                sql("SET CONSTRAINTS ALL IMMEDIATE")
                sql("SET CONSTRAINTS $constraint DEFERRED")
            }
            when (mutation) {
                "EXTRA_FACT" -> sql(extraFact(install))
                "SUBSTITUTED_FACT" -> sql(extraFact(install).replace("'ONU',1", "'ROUTER',1"))
                "ORPHAN_DOCUMENT" -> { clonedDocument(install, orphan).forEach(::sql); sql("UPDATE inventory_document SET state='POSTED',revision=1 WHERE id='$orphan'") }
                "EXTRA_LINE" -> sql("""INSERT INTO inventory_document_line SELECT (jsonb_populate_record(NULL::inventory_document_line,
                    to_jsonb(original)||jsonb_build_object('id','${UUID.randomUUID()}','line_number',2))).*
                    FROM inventory_document_line original WHERE document_id='${install.operation}'""")
                "MISSING_LINE" -> sql("DELETE FROM inventory_document_line WHERE document_id='${install.operation}'")
                "SUBSTITUTED_LINE" -> sql("UPDATE inventory_document_line SET custodian_id='${install.customer}',revision=revision+1 WHERE document_id='${install.operation}'")
                "MISSING_DOCUMENT" -> sql("DELETE FROM inventory_document WHERE id='${install.operation}'")
                "MISSING_RESULT" -> sql("DELETE FROM inventory_deployment_result WHERE operation_id='${install.operation}'")
                "MISSING_OPERATION" -> sql("DELETE FROM inventory_operation WHERE id='${install.operation}'")
                "MISSING_POSTING" -> sql("DELETE FROM inventory_movement WHERE operation_id='${install.operation}'")
                else -> error("Unknown mutation")
            }
            when (scope) {
                "CLEARED" -> sql("SET LOCAL app.tenant_id=''")
                "MISMATCHED" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                "RESTORED" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                "NORMAL", "SELECTIVE" -> Unit
                else -> error("Unknown scope")
            }
            sql("SET CONSTRAINTS $constraint IMMEDIATE")
        } }

        assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isIn("23514", "23503")
        if (mutation in setOf("EXTRA_FACT", "SUBSTITUTED_FACT", "ORPHAN_DOCUMENT") && scope in setOf("CLEARED", "MISMATCHED"))
            assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().message).contains("row tenant scope")
        assertThat(consume(install).contentAsString).isEqualTo(original.contentAsString)
        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='DEPLOYMENT' AND state='POSTED'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_document_line WHERE document_id='${install.operation}'")).isEqualTo("1")
        }
    }

    @Test
    fun `draft preparation and zero material facts remain valid without pretending to install`() {
        val install = installation(generic = true)
        assertThat(consume(install).status).isEqualTo(201)
        val draft = UUID.randomUUID()

        fixture(install.receipt.stock.token).transaction { clonedDocument(install, draft).forEach(::sql) }

        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$draft'")).isEqualTo("DRAFT")
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
            sql("DELETE FROM inventory_document_line WHERE document_id='$draft'")
            sql("DELETE FROM inventory_document WHERE id='$draft'")
        }
        assertThat(consume(install).status).isEqualTo(201)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NORMAL", "CLEARED", "MISMATCHED", "RESTORED", "SELECTIVE"])
    fun `draft deletion retains captured tenant scope even when its parent is gone`(scope: String) {
        val install = installation()
        assertThat(consume(install).status).isEqualTo(201)
        val draft = UUID.randomUUID()
        fixture(install.receipt.stock.token).transaction { clonedDocument(install, draft).forEach(::sql) }
        val delete = {
            fixture(install.receipt.stock.token).transaction {
                if (scope == "SELECTIVE") {
                    sql("SET CONSTRAINTS ALL IMMEDIATE")
                    sql("SET CONSTRAINTS warehouse_deployment_document_final DEFERRED")
                }
                sql("DELETE FROM inventory_document_line WHERE document_id='$draft'")
                sql("DELETE FROM inventory_document WHERE id='$draft'")
                when (scope) {
                    "CLEARED", "SELECTIVE" -> sql("SET LOCAL app.tenant_id=''")
                    "MISMATCHED" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "RESTORED" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    "NORMAL" -> Unit
                    else -> error("Unknown scope")
                }
                sql("SET CONSTRAINTS warehouse_deployment_document_final IMMEDIATE")
            }
        }

        if (scope in setOf("NORMAL", "RESTORED")) delete() else {
            val failure = assertThrows<Exception> { delete() }
            assertThat(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().message).contains("row tenant scope")
        }

        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE id='$draft'"))
                .isEqualTo(if (scope in setOf("NORMAL", "RESTORED")) "0" else "1")
        }
        assertThat(consume(install).status).isEqualTo(201)
    }
}
