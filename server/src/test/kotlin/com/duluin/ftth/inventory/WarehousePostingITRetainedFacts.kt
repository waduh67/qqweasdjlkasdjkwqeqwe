package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehousePostingITRetainedFacts : WarehousePostingRegressionSupport() {
    @ParameterizedTest @ValueSource(strings=["BOTH","RETAINED_ONLY","SWAPPED_EQUAL"])
    fun `retained material cannot produce return facts`(mode: String) {
        val fixture=fixture()
        val source=fixture.transaction { retainedFactSource() }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val split=if(mode=="SWAPPED_EQUAL") returnSplit(source,"50","50") else returnSplit(source)
            val facts=if(mode=="BOTH") listOf(split.movedFact,split.retainedFact) else listOf(split.retainedFact.copy(itemCategory="Moved cut"))
            post(split.command.copy(facts=facts))
        } }
        val observed=fixture.transaction { "factsMM=${materialFactTotal()} acceptedMM=${scalar("SELECT accepted_base FROM inventory_document_line WHERE id='${source.issueLine}'")} warehouseMM=${total(warehouse)} technicianMM=${total(technician)}" }
        println("RETAINED_FACT_PROBE mode=$mode committed=${result.isSuccess} $observed")
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(materialFactTotal()).isZero(); assertThat(total(warehouse)).isZero(); assertThat(total(technician)).isEqualTo(100000) }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `moved-only fact or no fact preserves sixty returned and forty retained`(withFact: Boolean) {
        val fixture=fixture()
        val source=fixture.transaction { retainedFactSource() }
        fixture.transaction {
            val split=returnSplit(source)
            post(if(withFact) split.command else split.command.copy(facts=emptyList()))
        }
        fixture.transaction {
            assertThat(materialFactTotal()).isEqualTo(if(withFact) 60000L else 0L)
            assertThat(total(warehouse)).isEqualTo(60000); assertThat(total(technician)).isEqualTo(40000)
        }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `multiple lines cannot lend factual capacity to a retained leg`(invalid: Boolean) {
        val fixture=fixture()
        val sources=fixture.transaction { listOf(retainedFactSource(),retainedFactSource()) }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val first=returnSplit(sources.first()); val second=returnSplit(sources.last())
            val firstCommand=if(invalid) first.command.copy(facts=emptyList()) else first.command
            val secondCommand=if(invalid) second.command.copy(facts=listOf(second.movedFact,second.retainedFact)) else second.command
            post(combineReturnLines(firstCommand,secondCommand))
        } }
        assertThat(result.isFailure).isEqualTo(invalid)
        fixture.transaction {
            if(invalid) { assertThat(counts()).isEqualTo(before); assertThat(materialFactTotal()).isZero(); assertThat(total(technician)).isEqualTo(200000) }
            else { assertThat(materialFactTotal()).isEqualTo(120000); assertThat(total(warehouse)).isEqualTo(120000); assertThat(total(technician)).isEqualTo(80000) }
        }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `pure retained split remains valid only without factual movement`(withFact: Boolean) {
        val fixture=fixture()
        val source=fixture.transaction { retainedFactSource(accepted="100") }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val first=source.parent.copy(stockIdentityId=UUID.randomUUID()); val second=source.parent.copy(stockIdentityId=UUID.randomUUID())
            val command=move(source.parent,first,StockQuantity.metres("40"),MovementKind.RETURN,splits=listOf(PostingSplit(source.parent.stockIdentityId,0,listOf(
                SegmentChild(first.stockIdentityId,StockQuantity.metres("40"),SegmentKind.REMNANT),SegmentChild(second.stockIdentityId,StockQuantity.metres("60"),SegmentKind.REMNANT)))),
                extra=listOf(second to StockQuantity.metres("60")),facts=if(withFact) listOf(fact(first,StockQuantity.metres("40")).copy(installed=false,returned=true)) else emptyList())
            bindLine(command,source.parent.stockIdentityId,source.issueLine,StockQuantity.metres("100"))
            post(command)
        } }
        assertThat(result.isFailure).isEqualTo(withFact)
        fixture.transaction {
            if(withFact) assertThat(counts()).isEqualTo(before)
            assertThat(materialFactTotal()).isZero(); assertThat(total(warehouse)).isZero(); assertThat(total(technician)).isEqualTo(100000)
        }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `local cutting cannot be presented as physical return`(withFact: Boolean) {
        val fixture=fixture()
        val source=fixture.transaction { retainedFactSource(accepted="100") }
        val result=runCatching { fixture.transaction {
            val split=returnSplit(source,localCut=true)
            post(if(withFact) split.command else split.command.copy(facts=emptyList()))
        } }
        assertThat(result.isFailure).isEqualTo(withFact)
        fixture.transaction { assertThat(materialFactTotal()).isZero(); assertThat(total(technician)).isEqualTo(100000) }
    }

    @Test fun `duplicate inbound mapping cannot redirect a fact to another line`() {
        val fixture=fixture()
        val source=fixture.transaction { retainedFactSource() }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val split=returnSplit(source)
            val combined=combineReturnLines(split.command,split.command)
            post(combined.copy(splits=split.command.splits,facts=split.command.facts))
        } }
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `swapping unique inbound line mappings cannot manufacture returned facts for stationary stock`() {
        val fixture=fixture()
        val source=fixture.transaction { retainedFactSource("80","80") }
        val children=fixture.transaction { splitPiece(source.parent,StockQuantity.metres("40"),StockQuantity.metres("40")) }
        val before=fixture.transaction { counts() }
        val result=runCatching { fixture.transaction {
            val commands=listOf(children.first,children.second).map { child ->
                move(child,child,StockQuantity.metres("40"),MovementKind.RETURN,facts=listOf(fact(child,StockQuantity.metres("40")).copy(installed=false,returned=true)))
                    .also { bindLine(it,source.parent.stockIdentityId,source.issueLine) }
            }
            val combined=combineReturnLines(commands.first(),commands.last())
            val firstLine=combined.legs.first().documentLineId; val secondLine=combined.legs.last().documentLineId
            post(combined.copy(legs=combined.legs.map { leg ->
                if(leg.direction==LegDirection.OUT) leg else leg.copy(documentLineId=if(leg.documentLineId==firstLine) secondLine else firstLine)
            }))
        } }
        assertThat(result.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before); assertThat(materialFactTotal()).isZero(); assertThat(total(technician)).isEqualTo(80000) }
    }

    @Test fun `valid descendant returns consume only the remaining acknowledged factual budget`() {
        val fixture=fixture()
        val source=fixture.transaction { retainedFactSource() }
        val retained=fixture.transaction {
            val split=returnSplit(source); post(split.command)
            source.copy(parent=source.parent.copy(stockIdentityId=split.retainedFact.stockIdentityId))
        }
        val before=fixture.transaction { counts() }
        val excess=runCatching { fixture.transaction {
            val piece=retained.parent
            val target=piece.copy(locationId=warehouse,custodianId=warehouse,custodianKind=OwnerKind.WAREHOUSE,condition=WarehouseCondition.QUARANTINE)
            val command=move(piece,target,StockQuantity.metres("40"),MovementKind.RETURN,facts=listOf(fact(piece,StockQuantity.metres("40")).copy(installed=false,returned=true)))
            bindLine(command,piece.stockIdentityId,source.issueLine); post(command)
        } }
        assertThat(excess.isFailure).isTrue()
        fixture.transaction { assertThat(counts()).isEqualTo(before); post(returnSplit(retained,"20","20").command) }
        fixture.transaction { assertThat(materialFactTotal()).isEqualTo(80000); assertThat(total(warehouse)).isEqualTo(80000); assertThat(total(technician)).isEqualTo(20000) }
    }
}
