package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.util.UUID

internal data class RetainedFactSource(val parent: PostingDimension,val issueLine: UUID)
internal data class ReturnSplitCase(val command: WarehousePost,val movedFact: PostingMaterialFact,val retainedFact: PostingMaterialFact)

internal fun WarehousePostingFixture.retainedFactSource(total: String="100",accepted: String="80"): RetainedFactSource {
    val received=receipt(StockQuantity.metres(total))
    val parent=received.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
    post(move(received,parent,StockQuantity.metres(total)))
    return RetainedFactSource(parent,acknowledgedLine(parent,StockQuantity.metres(total),StockQuantity.metres(accepted)))
}

internal fun WarehousePostingFixture.returnSplit(source: RetainedFactSource,moved: String="60",retained: String="40",localCut: Boolean=false): ReturnSplitCase {
    val cut=source.parent.copy(stockIdentityId=UUID.randomUUID()).let {
        if(localCut) it else it.copy(locationId=warehouse,custodianId=warehouse,custodianKind=OwnerKind.WAREHOUSE,condition=WarehouseCondition.QUARANTINE)
    }
    val remnant=source.parent.copy(stockIdentityId=UUID.randomUUID())
    val movedQuantity=StockQuantity.metres(moved); val retainedQuantity=StockQuantity.metres(retained)
    val movedFact=fact(cut,movedQuantity).copy(installed=false,returned=true)
    val retainedFact=fact(remnant,retainedQuantity).copy(installed=false,returned=true)
    val command=move(source.parent,cut,movedQuantity,MovementKind.RETURN,splits=listOf(PostingSplit(source.parent.stockIdentityId,0,listOf(
        SegmentChild(cut.stockIdentityId,movedQuantity,SegmentKind.CUT),SegmentChild(remnant.stockIdentityId,retainedQuantity,SegmentKind.REMNANT)))),
        extra=listOf(remnant to retainedQuantity),facts=listOf(movedFact))
    bindLine(command,source.parent.stockIdentityId,source.issueLine)
    return ReturnSplitCase(command,movedFact,retainedFact)
}

internal fun WarehousePostingFixture.combineReturnLines(first: WarehousePost,second: WarehousePost): WarehousePost {
    val secondLine=UUID.randomUUID()
    sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,source_line_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) SELECT '$secondLine',tenant_id,'${first.documentId}',2,0,sku_id,stock_identity_id,lot_id,source_line_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner FROM inventory_document_line WHERE document_id='${second.documentId}'")
    return first.copy(legs=first.legs+second.legs.map { it.copy(documentLineId=secondLine) },splits=first.splits+second.splits,facts=first.facts+second.facts)
}

internal fun WarehousePostingFixture.materialFactTotal(): Long = scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_customer_material_fact").toLong()
