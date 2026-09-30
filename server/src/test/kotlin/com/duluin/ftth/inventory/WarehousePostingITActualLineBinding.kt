package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehousePostingITActualLineBinding : WarehousePostingRegressionSupport() {
    @Test fun `movement without facts still binds its actual identity to the declared source issue`() {
        val fixture=fixture()
        val pieces=fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) to acknowledge(receipt(StockQuantity.each("1"))) }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val actual=pieces.second
            val source=scalar("SELECT line.id FROM inventory_document_line line JOIN inventory_document document ON document.id=line.document_id WHERE document.kind='ISSUE' AND line.stock_identity_id='${pieces.first.stockIdentityId}'")
            val command=move(actual,actual.copy(locationId=warehouse,custodianId=warehouse,custodianKind=OwnerKind.WAREHOUSE,condition=WarehouseCondition.QUARANTINE),StockQuantity.each("1"),MovementKind.RETURN)
            bindLine(command,actual.stockIdentityId,UUID.fromString(source))
            post(command)
        } }
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(total(technician,StockUnit.EA)).isEqualTo(2) }
        fixture.transaction {
            val actual=pieces.first
            val source=scalar("SELECT line.id FROM inventory_document_line line JOIN inventory_document document ON document.id=line.document_id WHERE document.kind='ISSUE' AND line.stock_identity_id='${actual.stockIdentityId}'")
            val command=move(actual,actual.copy(locationId=warehouse,custodianId=warehouse,custodianKind=OwnerKind.WAREHOUSE,condition=WarehouseCondition.QUARANTINE),StockQuantity.each("1"),MovementKind.RETURN)
            bindLine(command,actual.stockIdentityId,UUID.fromString(source))
            post(command)
            assertThat(total(technician,StockUnit.EA)).isEqualTo(1)
            assertThat(total(warehouse,StockUnit.EA)).isEqualTo(1)
        }
    }

    @Test fun `pure remnant split binds the full parent quantity without inventing a transfer`() {
        val fixture=fixture()
        fixture.transaction {
            val parent=receipt(StockQuantity.metres("100"))
            val first=parent.copy(stockIdentityId=UUID.randomUUID())
            val second=parent.copy(stockIdentityId=UUID.randomUUID())
            val command=move(parent,first,StockQuantity.metres("40"),splits=listOf(PostingSplit(parent.stockIdentityId,0,listOf(
                SegmentChild(first.stockIdentityId,StockQuantity.metres("40"),SegmentKind.REMNANT),SegmentChild(second.stockIdentityId,StockQuantity.metres("60"),SegmentKind.REMNANT)))),extra=listOf(second to StockQuantity.metres("60")))
            bindLine(command,parent.stockIdentityId,quantity=StockQuantity.metres("100"))
            post(command)
            assertThat(total(warehouse)).isEqualTo(100000)
        }
    }

    @Test fun `actual quantities across two lines cannot exceed one acknowledged source`() {
        val fixture=fixture()
        val source=fixture.transaction {
            val received=receipt(StockQuantity.metres("100"))
            val parent=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(received,parent,StockQuantity.metres("100")))
            acknowledgedLine(parent,StockQuantity.metres("100"),StockQuantity.metres("80")) to splitPiece(parent,StockQuantity.metres("60"),StockQuantity.metres("40"))
        }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val commands=listOf(source.second.first to StockQuantity.metres("60"),source.second.second to StockQuantity.metres("40")).map { (piece,quantity) ->
                move(piece,piece.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER),quantity,MovementKind.CONSUME,facts=listOf(fact(piece,quantity)))
                    .also { bindLine(it,piece.stockIdentityId,source.first) }
            }
            val first=commands.first(); val second=commands.last(); val secondLine=UUID.randomUUID()
            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,source_line_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) SELECT '$secondLine',tenant_id,'${first.documentId}',2,0,sku_id,stock_identity_id,lot_id,source_line_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner FROM inventory_document_line WHERE document_id='${second.documentId}'")
            post(first.copy(legs=first.legs+second.legs.map { it.copy(documentLineId=secondLine) },facts=first.facts+second.facts))
        } }
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(total(technician)).isEqualTo(100000) }
    }

    @Test fun `acknowledged quantity cannot be consumed again through another posting line`() {
        val fixture=fixture()
        val source=fixture.transaction {
            val received=receipt(StockQuantity.each("100"),true)
            val issued=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(received,issued,StockQuantity.each("100")))
            issued to acknowledgedLine(issued,StockQuantity.each("100"),StockQuantity.each("60"))
        }
        fun consume(amount: String = "40") = fixture.transaction {
            val piece=source.first
            val command=move(piece,piece.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER),StockQuantity.each(amount),MovementKind.CONSUME,facts=listOf(fact(piece,StockQuantity.each(amount))))
            bindLine(command,piece.stockIdentityId,source.second)
            post(command)
        }
        consume()
        val before=fixture.transaction { counts() }
        assertThat(runCatching { consume() }.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(total(technician,StockUnit.EA)).isEqualTo(60); assertThat(total(consumed,StockUnit.EA)).isEqualTo(40) }
        consume("20")
        assertThat(runCatching { consume("20") }.isFailure).isTrue()
        fixture.transaction { assertThat(total(technician,StockUnit.EA)).isEqualTo(40); assertThat(total(consumed,StockUnit.EA)).isEqualTo(60) }
    }

    @Test fun `concurrent descendants serialize on the acknowledged line quantity`() {
        val fixture=fixture()
        val commands=fixture.transaction {
            val received=receipt(StockQuantity.metres("100"))
            val parent=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(received,parent,StockQuantity.metres("100")))
            val issue=acknowledgedLine(parent,StockQuantity.metres("100"),StockQuantity.metres("80"))
            val children=splitPiece(parent,StockQuantity.metres("60"),StockQuantity.metres("40"))
            listOf(children.first to StockQuantity.metres("60"),children.second to StockQuantity.metres("40")).map { (piece,quantity) ->
                move(piece,piece.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER),quantity,MovementKind.CONSUME,facts=listOf(fact(piece,quantity)))
                    .also { bindLine(it,piece.stockIdentityId,issue) }
            }
        }
        val reached=CountDownLatch(1); val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        PostingJdbcProbe(context,TestPostingPhase.HEADER) { reached.countDown(); check(release.await(15,TimeUnit.SECONDS)) }.use {
            try {
                val first=pool.submit<Boolean> { fixture.transaction { post(commands.first()) }; true }
                check(reached.await(10,TimeUnit.SECONDS))
                val second=pool.submit<Boolean> { runCatching { fixture.transaction { post(commands.last()) } }.isSuccess }
                var blocked=false; val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                while(!blocked && System.nanoTime()<deadline) {
                    blocked=fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%inventory_document_line%'").toInt()>0 }
                    Thread.yield()
                }
                assertThat(blocked).isTrue(); release.countDown()
                assertThat(first.get(15,TimeUnit.SECONDS)).isTrue(); assertThat(second.get(15,TimeUnit.SECONDS)).isFalse()
            } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
        }
        fixture.transaction { assertThat(total(consumed)).isEqualTo(60000); assertThat(total(technician)).isEqualTo(40000) }
    }

    @Test fun `actual serial B cannot consume against declared serial and issue A`() {
        val fixture=fixture()
        val pieces=fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) to acknowledge(receipt(StockQuantity.each("1"))) }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val declared=pieces.first
            val actual=pieces.second
            val source=scalar("SELECT line.id FROM inventory_document_line line JOIN inventory_document document ON document.id=line.document_id WHERE document.kind='ISSUE' AND line.stock_identity_id='${declared.stockIdentityId}'")
            val command=move(actual,actual.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER),StockQuantity.each("1"),MovementKind.CONSUME,facts=listOf(fact(actual)))
            bindLine(command,declared.stockIdentityId,UUID.fromString(source))
            post(command)
        } }
        val mismatches=fixture.transaction { scalar("""SELECT count(*) FROM inventory_customer_material_fact fact
            JOIN inventory_movement_leg leg ON leg.movement_id=fact.posting_id AND leg.stock_identity_id=fact.stock_identity_id AND leg.direction='IN'
            JOIN inventory_document_line line ON line.id=leg.document_line_id WHERE line.stock_identity_id<>fact.stock_identity_id""") }
        println("ACTUAL_IDENTITY_PROBE committed=${result.isSuccess} mismatches=$mismatches")
        assertThat(result.isFailure).isTrue()
        assertThat(mismatches).isEqualTo("0")
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(total(technician,StockUnit.EA)).isEqualTo(2); assertThat(total(consumed,StockUnit.EA)).isZero() }
    }

    @ParameterizedTest @ValueSource(strings=["SIBLING","FOREIGN_LOT","OVER_LINE","SPLIT_OVER_LINE"])
    fun `actual lineage lot and aggregate quantity must fit the locked line`(mode: String) {
        val fixture=fixture()
        val pair=fixture.transaction {
            if(mode=="SIBLING") splitPiece(receipt(StockQuantity.metres("100")),StockQuantity.metres("60"),StockQuantity.metres("40"))
            else receipt(StockQuantity.metres("100")) to receipt(StockQuantity.metres("100"))
        }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val actual=if(mode in setOf("SIBLING","FOREIGN_LOT")) pair.second else pair.first
            val quantity=if(mode=="SIBLING") StockQuantity.metres("40") else StockQuantity.metres("100")
            val command=if(mode=="SPLIT_OVER_LINE") {
                val cut=actual.copy(stockIdentityId=UUID.randomUUID(),locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
                val remnant=actual.copy(stockIdentityId=UUID.randomUUID())
                move(actual,cut,StockQuantity.metres("60"),splits=listOf(PostingSplit(actual.stockIdentityId,0,listOf(
                    SegmentChild(cut.stockIdentityId,StockQuantity.metres("60"),SegmentKind.CUT),SegmentChild(remnant.stockIdentityId,StockQuantity.metres("40"),SegmentKind.REMNANT)))),extra=listOf(remnant to StockQuantity.metres("40")))
            } else move(actual,actual.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),quantity)
            if(mode=="FOREIGN_LOT") {
                sql("UPDATE inventory_document_line SET stock_identity_id='${pair.first.stockIdentityId}',lot_id='${pair.first.lotId}',revision=1 WHERE document_id='${command.documentId}'")
            } else bindLine(command,if(mode=="SIBLING") pair.first.stockIdentityId else actual.stockIdentityId,quantity=if(mode.contains("OVER_LINE")) StockQuantity.metres("20") else null)
            post(command)
        } }
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `acknowledged parent permits actual committed or posting-created child consumption`(pending: Boolean) {
        val fixture=fixture()
        fixture.transaction {
            val received=receipt(StockQuantity.metres("100"))
            val parent=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(received,parent,StockQuantity.metres("100")))
            val issue=acknowledgedLine(parent,StockQuantity.metres("100"))
            val cut=if(pending) parent.copy(stockIdentityId=UUID.randomUUID()) else splitPiece(parent,StockQuantity.metres("60"),StockQuantity.metres("40")).first
            val target=cut.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER)
            val command=if(pending) {
                val rest=parent.copy(stockIdentityId=UUID.randomUUID())
                move(parent,target,StockQuantity.metres("60"),MovementKind.CONSUME,
                    splits=listOf(PostingSplit(parent.stockIdentityId,0,listOf(SegmentChild(cut.stockIdentityId,StockQuantity.metres("60"),SegmentKind.CUT),SegmentChild(rest.stockIdentityId,StockQuantity.metres("40"),SegmentKind.REMNANT)))),
                    extra=listOf(rest to StockQuantity.metres("40")),facts=listOf(fact(cut,StockQuantity.metres("60"))))
            } else move(cut,target,StockQuantity.metres("60"),MovementKind.CONSUME,facts=listOf(fact(cut,StockQuantity.metres("60"))))
            bindLine(command,parent.stockIdentityId,issue)
            post(command)
            assertThat(total(consumed)).isEqualTo(60000); assertThat(total(technician)).isEqualTo(40000)
        }
    }

    @Test fun `a sibling is not authorized by another child line even under the same issue ancestor`() {
        val fixture=fixture()
        val context=fixture.transaction {
            val received=receipt(StockQuantity.metres("100"))
            val parent=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
            post(move(received,parent,StockQuantity.metres("100")))
            val issue=acknowledgedLine(parent,StockQuantity.metres("100"))
            splitPiece(parent,StockQuantity.metres("60"),StockQuantity.metres("40")) to issue
        }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val actual=context.first.second
            val command=move(actual,actual.copy(locationId=consumed,custodianId=customer,custodianKind=OwnerKind.CUSTOMER),StockQuantity.metres("40"),MovementKind.CONSUME,facts=listOf(fact(actual,StockQuantity.metres("40"))))
            bindLine(command,context.first.first.stockIdentityId,context.second)
            post(command)
        } }
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `pending inbound children cannot be swapped between two correctly bound debit lines`() {
        val fixture=fixture()
        val pieces=fixture.transaction { splitPiece(receipt(StockQuantity.metres("100")),StockQuantity.metres("50"),StockQuantity.metres("50")) }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val commands=listOf(pieces.first,pieces.second).map { parent ->
                val cut=parent.copy(stockIdentityId=UUID.randomUUID())
                val rest=parent.copy(stockIdentityId=UUID.randomUUID())
                move(parent,cut,StockQuantity.metres("20"),splits=listOf(PostingSplit(parent.stockIdentityId,0,listOf(
                    SegmentChild(cut.stockIdentityId,StockQuantity.metres("20"),SegmentKind.CUT),SegmentChild(rest.stockIdentityId,StockQuantity.metres("30"),SegmentKind.REMNANT)))),extra=listOf(rest to StockQuantity.metres("30")))
            }
            val first=commands.first(); val second=commands.last(); val secondLine=UUID.randomUUID()
            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) SELECT '$secondLine',tenant_id,'${first.documentId}',2,0,sku_id,stock_identity_id,lot_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner FROM inventory_document_line WHERE document_id='${second.documentId}'")
            val firstLine=first.legs.first().documentLineId
            val firstCut=first.splits.single().children.first().id; val secondCut=second.splits.single().children.first().id
            val legs=(first.legs+second.legs.map { it.copy(documentLineId=secondLine) }).map { leg ->
                when(leg.dimension.stockIdentityId) { firstCut -> leg.copy(documentLineId=secondLine); secondCut -> leg.copy(documentLineId=firstLine); else -> leg }
            }
            post(first.copy(legs=legs,splits=first.splits+second.splits))
        } }
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }
}
