package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.adapter.outbound.persistence.*
import com.karuhun.ftth.inventory.application.port.inbound.*
import com.karuhun.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.karuhun.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class WarehouseWarrantyService(private val access: WarehouseClientAccess,private val requests: ReferenceRequestService,
    private val store: WarehouseClientStore,private val stock: WarehouseClientStockService,private val receipts: ReferenceWarehouseService,
    private val masters: WarehouseMasterStore) {
    private val kind="WARRANTY";private val manage="warehouse.warranty.manage";private val type=WarehouseWarrantyView::class.java
    fun list(page: Int,size: Int,search: String): WarehousePage<WarehouseWarrantyView> {
        access.pagination(page,size,search);val current=access.read("warehouse.warranty.view")
        return store.list(kind,requests.visibleWarehouses(current),page,size,search,type)
    }
    fun detail(id: UUID): WarehouseClientDetail<WarehouseWarrantyView> { val current=access.read("warehouse.warranty.view");return WarehouseClientDetail(access.authorize(store.get(kind,id,type),current),store.timeline(id)) }
    fun dispatch(id: UUID,input: WarehouseWarrantyDispatch,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(id,input)
        access.replay(kind,"DISPATCH",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state!="MASUK" || prior.returnedAt!=null) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptText(input.vendorReference,500);receiptText(input.evidence,1000)
        val supplier=masters.get(MasterKind.SUPPLIER,input.supplierId,true) as SupplierSnapshot
        if(supplier.state!=WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val warehouse=requests.warehouse(prior.warehouseId,current)
        val destination=store.location("WARRANTY-VENDOR-${warehouse.id}","Barang di vendor garansi","TRANSIT",warehouse.id,warehouse.areaId)
        val moved=stock.move(id,prior.revision+1,manage,"WARRANTY_SEND",prior.stockIdentityId,prior.locationId,prior.quantityBase,
            prior.locationId,OwnerKind.WAREHOUSE,InventoryStatus.QUARANTINE,destination,supplier.id,OwnerKind.REPAIR,InventoryStatus.IN_TRANSIT,WarehouseCondition.DAMAGED,input.evidence)
        val view=prior.copy(revision=prior.revision+1,state="DIKIRIM",locationId=destination,stockIdentityId=moved.stockIdentityId,
            supplierId=supplier.id,supplierName=supplier.name,vendorReference=input.vendorReference,evidence=input.evidence,sentAt=referenceTimestamp(),updatedAt=referenceTimestamp())
        store.save(kind,view);return store.record(kind,"DISPATCH",key,view,current,canonical,manage,input.evidence,listOf(moved.documentId))
    }
    fun receive(id: UUID,input: WarehouseWarrantyReceipt,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(id,input)
        access.replay(kind,"RECEIVE",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state!="DIKIRIM" || input.result !in setOf("REPAIRED","UNREPAIRED","REJECTED","REPLACED")) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptText(input.evidence,1000);val warehouse=requests.warehouse(prior.warehouseId,current)
        val destination=store.location("CLIENT-QUARANTINE-${warehouse.id}","Karantina retur client","QUARANTINE",warehouse.id,warehouse.areaId)
        val movements=mutableListOf<UUID>();var replacement: UUID?=null
        val moved=if(input.result=="REPLACED") {
            if(prior.tracking!=WarehouseTracking.SERIAL || input.replacementSerial.isNullOrBlank() || input.replacementSerial.trim().uppercase()==prior.serial?.trim()?.uppercase()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST,"Pengganti membutuhkan serial baru")
            val receipt=receipts.receiveForWarranty(ReferenceReceiptInput(warehouse.id,listOf(ReceiptLineInput(prior.skuId,"1",listOf(ReceiptSerialInput(input.replacementSerial,input.replacementMac)))),input.evidence,prior.supplierId,prior.vendorReference),"warranty:$id:${prior.revision+1}:replacement")
            movements+=receipt.documentId
            // Serialized receipt identity is its asset, obtained from the actual receipt line.
            replacement=store.receiptAsset(receipt.documentId)
            stock.move(id,prior.revision+1,manage,"WARRANTY_BACK",requireNotNull(replacement),warehouse.id,"1",warehouse.id,OwnerKind.WAREHOUSE,
                InventoryStatus.AVAILABLE,destination,destination,OwnerKind.WAREHOUSE,InventoryStatus.QUARANTINE,WarehouseCondition.QUARANTINE,input.evidence)
        } else {
            if(prior.tracking==WarehouseTracking.SERIAL && input.observedSerial?.trim()?.uppercase()!=prior.serial?.trim()?.uppercase()) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            stock.move(id,prior.revision+1,manage,"WARRANTY_BACK",prior.stockIdentityId,prior.locationId,prior.quantityBase,requireNotNull(prior.supplierId),OwnerKind.REPAIR,
                InventoryStatus.IN_TRANSIT,destination,destination,OwnerKind.WAREHOUSE,InventoryStatus.QUARANTINE,WarehouseCondition.QUARANTINE,input.evidence)
        }
        movements+=moved.documentId
        if(input.result=="REPLACED") store.bindMovement(movements.first(),id,prior.revision+1)
        val view=prior.copy(revision=prior.revision+1,state="MASUK",result=input.result,locationId=destination,stockIdentityId=moved.stockIdentityId,
            serial=if(replacement!=null) input.replacementSerial else prior.serial,replacementAssetId=replacement,evidence=input.evidence,returnedAt=referenceTimestamp(),updatedAt=referenceTimestamp())
        store.save(kind,view);return store.record(kind,"RECEIVE",key,view,current,canonical,manage,input.evidence,movements)
    }
    fun inspect(id: UUID,input: WarehouseWarrantyInspection,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(id,input)
        access.replay(kind,"INSPECT",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state!="MASUK" || prior.returnedAt==null || input.serviceable && prior.tracking==WarehouseTracking.SERIAL && !input.resetConfirmed) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST,"Periksa dan konfirmasi reset perangkat")
        receiptText(input.evidence,1000)
        val moved=if(input.serviceable) stock.move(id,prior.revision+1,manage,"WARRANTY_INSPECT",prior.stockIdentityId,prior.locationId,prior.quantityBase,
            prior.locationId,OwnerKind.WAREHOUSE,InventoryStatus.QUARANTINE,prior.warehouseId,prior.warehouseId,OwnerKind.WAREHOUSE,
            InventoryStatus.AVAILABLE,WarehouseCondition.SERVICEABLE,input.evidence) else null
        val view=prior.copy(revision=prior.revision+1,state="SELESAI",locationId=moved?.let { prior.warehouseId } ?: prior.locationId,
            stockIdentityId=moved?.stockIdentityId ?: prior.stockIdentityId,evidence=input.evidence,updatedAt=referenceTimestamp())
        store.save(kind,view);return store.record(kind,"INSPECT",key,view,current,canonical,manage,input.evidence,listOfNotNull(moved?.documentId))
    }
}
