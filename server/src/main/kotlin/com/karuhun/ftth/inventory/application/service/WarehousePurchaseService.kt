package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.adapter.outbound.persistence.*
import com.karuhun.ftth.inventory.application.port.inbound.*
import com.karuhun.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class WarehousePurchaseService(private val access: WarehouseClientAccess,private val requests: ReferenceRequestService,
    private val store: WarehouseClientStore,private val requestStore: ReferenceRequestStore,private val masters: WarehouseMasterStore,
    private val stock: ReferenceWarehouseService,private val receipts: ReferenceRequestMovementService) {
    private val kind="PURCHASE";private val manage="warehouse.request.receive";private val type=WarehousePurchaseView::class.java
    fun list(page: Int,size: Int,search: String): WarehousePage<WarehousePurchaseView> {
        access.pagination(page,size,search);val current=access.read("warehouse.request.view")
        return store.list(kind,requests.visibleWarehouses(current),page,size,search,type)
    }
    fun detail(id: UUID): WarehouseClientDetail<WarehousePurchaseView> { val current=access.read("warehouse.request.view");return WarehouseClientDetail(access.authorize(store.get(kind,id,type),current),store.timeline(id)) }
    fun create(input: WarehousePurchaseInput,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(null,input)
        access.replay(kind,"CREATE",key,canonical,current,type)?.let { return it }
        val warehouse=requests.warehouse(input.warehouseId,current)
        val supplier=masters.get(MasterKind.SUPPLIER,input.supplierId,true) as SupplierSnapshot
        if(supplier.state!=WarehouseMasterState.ACTIVE || input.lines.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptText(input.reference,500);requests.notes(input.notes)
        val request=input.requestId?.let { requests.authorized(requestStore.get(it,true),current) }
        if(request!=null && (request.kind!=ReferenceRequestKind.PROCUREMENT || request.state !in setOf(ReferenceRequestState.APPROVED,
            ReferenceRequestState.PARTIALLY_RECEIVED,ReferenceRequestState.RECEIVED,ReferenceRequestState.PARTIALLY_FULFILLED) || request.warehouseId!=null && request.warehouseId!=warehouse.id))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST,"Pilih request pengadaan yang disetujui")
        if(input.lines.map { it.requestLineId ?: it.skuId }.distinct().size!=input.lines.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val lines=input.lines.map { line ->
            val sku=requests.sku(line.skuId,line.baseUnit);val qty=positiveReceiptQuantity(line.quantityBase,line.baseUnit).quantityBase
            line.unitPriceMinor?.let { if(!it.matches(Regex("0|[1-9][0-9]{0,18}")) || it.toLongOrNull()==null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
            if(request!=null) {
                val original=request.lines.singleOrNull { it.id==line.requestLineId && it.skuId==sku.id } ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                val remaining=original.approvedBase.toLong()-original.receivedBase.toLong()-store.reservedPurchaseBase(request.id,original.id)
                if(qty>remaining) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST,"Pesanan melebihi sisa request yang belum dipesan")
            } else if(line.requestLineId!=null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            WarehousePurchaseLine(UUID.randomUUID(),sku.id,sku.name,sku.baseUnit,line.quantityBase,requestLineId=line.requestLineId,unitPriceMinor=line.unitPriceMinor)
        }
        val now=referenceTimestamp();val view=WarehousePurchaseView(UUID.randomUUID(),0,warehouse.id,warehouse.name ?: warehouse.code,
            supplier.id,supplier.name,input.purchaseDate,input.reference,input.requestId,"ORDERED",input.notes,lines,now,now)
        store.save(kind,view);return store.record(kind,"CREATE",key,view,current,canonical,manage,input.notes)
    }
    fun receive(id: UUID,input: WarehousePurchaseReceipt,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(id,input)
        access.replay(kind,"RECEIVE",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state !in setOf("ORDERED","PARTIALLY_RECEIVED")) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        requests.notes(input.notes);val line=prior.lines.singleOrNull { it.id==input.lineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val qty=positiveReceiptQuantity(input.quantityBase,line.baseUnit).quantityBase
        if(qty>line.quantityBase.toLong()-line.receivedBase.toLong()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST,"Penerimaan melebihi sisa pesanan")
        val movement=if(prior.requestId!=null) {
            val req=requestStore.get(prior.requestId,true)
            receipts.receive(prior.requestId,ReferenceRequestReceipt(req.revision,requireNotNull(line.requestLineId),prior.warehouseId,
                input.quantityBase,input.serials,prior.supplierId,prior.reference,input.notes,input.lotCode,cost=input.cost),"purchase:$id:${prior.revision+1}",id)
            requireNotNull(requestStore.timeline(prior.requestId).last().movementId)
        } else stock.receiveForRequest(ReferenceReceiptInput(prior.warehouseId,listOf(ReceiptLineInput(line.skuId,input.quantityBase,input.serials,input.lotCode,cost=input.cost)),input.notes,prior.supplierId,prior.reference),"purchase:$id:${prior.revision+1}").documentId
        val lines=prior.lines.map { if(it.id==line.id) it.copy(receivedBase=Math.addExact(it.receivedBase.toLong(),qty).toString()) else it }
        val view=prior.copy(revision=prior.revision+1,lines=lines,state=if(lines.all { it.receivedBase==it.quantityBase }) "RECEIVED" else "PARTIALLY_RECEIVED",updatedAt=referenceTimestamp())
        store.bindMovement(movement,id,view.revision);store.save(kind,view)
        return store.record(kind,"RECEIVE",key,view,current,canonical,manage,input.notes,listOf(movement))
    }
    fun cancel(id: UUID,input: WarehouseClientRevision,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(id,input)
        access.replay(kind,"CANCEL",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state !in setOf("ORDERED","PARTIALLY_RECEIVED")) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptText(input.notes,1000)
        val view=prior.copy(revision=prior.revision+1,state="CANCELLED",updatedAt=referenceTimestamp());store.save(kind,view)
        return store.record(kind,"CANCEL",key,view,current,canonical,manage,input.notes)
    }
}
