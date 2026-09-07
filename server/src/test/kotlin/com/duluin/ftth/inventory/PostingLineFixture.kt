package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.util.UUID

internal fun WarehousePostingFixture.acknowledgedLine(piece: PostingDimension,quantity: StockQuantity,accepted: StockQuantity=quantity): UUID {
    val document=UUID.randomUUID()
    val line=UUID.randomUUID()
    val tracking=if(piece.skuId==serialSku) "SERIAL" else if(piece.skuId==bulkSku) "BULK" else "LOT"
    sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','ISSUE','$actor','$workOrder','$customer',0,0)")
    sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,base_unit,tracking,quantity_base,accepted_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'${piece.skuId}','${piece.stockIdentityId}',${piece.lotId?.let { "'$it'" } ?: "NULL"},'${quantity.unit}','$tracking',${quantity.quantityBase},${accepted.quantityBase},'${piece.locationId}','${piece.custodianId}','${piece.custodianKind}','${piece.condition}','${piece.legalOwner}')")
    listOf("PICKED","DISPATCHED",if(accepted==quantity) "RECEIVED" else "PART_RECEIVED").forEachIndexed { index,state ->
        sql("UPDATE inventory_document SET state='$state',revision=${index+1} WHERE id='$document'")
    }
    return line
}

internal fun WarehousePostingFixture.bindLine(command: WarehousePost,identity: UUID,sourceLine: UUID? = null,quantity: StockQuantity? = null) {
    val source=sourceLine?.let { ",source_line_id='$it'" }.orEmpty()
    val amount=quantity?.let { ",quantity_base=${it.quantityBase}" }.orEmpty()
    sql("UPDATE inventory_document_line SET stock_identity_id='$identity',revision=revision+1 $source $amount WHERE document_id='${command.documentId}'")
}

internal fun WarehousePostingFixture.splitPiece(piece: PostingDimension,cut: StockQuantity,remaining: StockQuantity): Pair<PostingDimension,PostingDimension> {
    val child=piece.copy(stockIdentityId=UUID.randomUUID())
    val remnant=piece.copy(stockIdentityId=UUID.randomUUID())
    post(move(piece,child,cut,splits=listOf(PostingSplit(piece.stockIdentityId,0,listOf(
        SegmentChild(child.stockIdentityId,cut,SegmentKind.CUT),SegmentChild(remnant.stockIdentityId,remaining,SegmentKind.REMNANT)))),extra=listOf(remnant to remaining)))
    return child to remnant
}
