package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehousePostingITFactBinding : WarehousePostingRegressionSupport() {
    @ParameterizedTest @ValueSource(strings=["CUSTOMER","WORK_ORDER","SINK_CUSTOMER","SINK_KIND","USAGE","SOURCE_ISSUE","SOURCE_LINE","ISSUE_IDENTITY","ISSUE_UNBOUND"])
    fun `AV5-04 facts and usage cannot contradict locked document sink or issue`(mode: String) {
        val fixture=fixture()
        val piece=fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) }
        val before=fixture.transaction { counts() }
        assertThatThrownBy { fixture.transaction {
            val foreignCustomer=UUID.randomUUID()
            val foreignWorkOrder=UUID.randomUUID()
            var fact=fact(piece)
            if(mode=="CUSTOMER") fact=fact.copy(customerId=foreignCustomer)
            if(mode in setOf("WORK_ORDER","USAGE")) fact=fact.copy(workOrderId=foreignWorkOrder)
            val target=piece.copy(locationId=consumed,custodianId=if(mode=="SINK_CUSTOMER") foreignCustomer else requireNotNull(fact.customerId),
                custodianKind=if(mode=="SINK_KIND") OwnerKind.TECHNICIAN else OwnerKind.CUSTOMER)
            var command=move(piece,target,StockQuantity.each("1"),MovementKind.CONSUME,facts=listOf(fact))
            if(mode=="USAGE") command=command.copy(usage=usage(piece,foreignWorkOrder))
            if(mode in setOf("SOURCE_ISSUE","SOURCE_LINE")) {
                val issue=scalar("SELECT id FROM inventory_document WHERE kind='ISSUE'")
                val sourceLine=scalar("SELECT id FROM inventory_document_line WHERE document_id='$issue'")
                val link=if(mode=="SOURCE_ISSUE") ",source_document_id='$issue',source_revision=3" else ""
                sql("UPDATE inventory_document SET customer_id='$foreignCustomer',revision=1 $link WHERE id='${command.documentId}'")
                if(mode=="SOURCE_LINE") sql("UPDATE inventory_document_line SET source_line_id='$sourceLine',document_revision=1,revision=1 WHERE document_id='${command.documentId}'")
                command=command.copy(expectedRevision=1,facts=listOf(fact.copy(customerId=foreignCustomer)),
                    legs=command.legs.map { if(it.direction==LegDirection.IN) it.copy(dimension=it.dimension.copy(custodianId=foreignCustomer)) else it })
            }
            if(mode=="ISSUE_IDENTITY") {
                val unrelated=acknowledge(receipt(StockQuantity.each("1")))
                val unrelatedLine=scalar("SELECT line.id FROM inventory_document_line line JOIN inventory_document document ON document.id=line.document_id WHERE document.kind='ISSUE' AND line.stock_identity_id='${unrelated.stockIdentityId}'")
                sql("UPDATE inventory_document_line SET source_line_id='$unrelatedLine',revision=1 WHERE document_id='${command.documentId}'")
            }
            if(mode=="ISSUE_UNBOUND") {
                val issue=scalar("SELECT id FROM inventory_document WHERE kind='ISSUE'")
                sql("UPDATE inventory_document SET source_document_id='$issue',source_revision=3,revision=1 WHERE id='${command.documentId}'")
                command=command.copy(expectedRevision=1)
            }
            post(command)
        } }.isInstanceOf(Exception::class.java)
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(total(technician,StockUnit.EA)).isEqualTo(1) }
    }

    @Test fun `AV5-04 standalone movement without facts does not fabricate a customer`() {
        val fixture=fixture()
        fixture.transaction {
            val piece=receipt(StockQuantity.each("1"))
            val command=move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1"))
            sql("UPDATE inventory_document SET customer_id=NULL,work_order_id=NULL,revision=1 WHERE id='${command.documentId}'")
            post(command.copy(expectedRevision=1))
            assertThat(total(technician,StockUnit.EA)).isEqualTo(1)
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
        }
    }
}
