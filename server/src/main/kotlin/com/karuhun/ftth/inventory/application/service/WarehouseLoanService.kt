package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.adapter.outbound.persistence.*
import com.karuhun.ftth.inventory.application.port.inbound.*
import com.karuhun.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class WarehouseLoanService(private val access: WarehouseClientAccess,private val requests: ReferenceRequestService,
    private val store: WarehouseClientStore,private val policies: ReferenceRequestStore,private val stock: WarehouseClientStockService) {
    private val kind="LOAN";private val manage="warehouse.loan.manage";private val type=WarehouseLoanView::class.java
    fun list(page: Int,size: Int,search: String): WarehousePage<WarehouseLoanView> {
        access.pagination(page,size,search);val current=access.read("warehouse.loan.view")
        return store.list(kind,requests.visibleWarehouses(current),page,size,search,type)
    }
    fun detail(id: UUID): WarehouseClientDetail<WarehouseLoanView> { val current=access.read("warehouse.loan.view");return WarehouseClientDetail(access.authorize(store.get(kind,id,type),current),store.timeline(id)) }
    fun issueSnapshot(id: UUID): WarehouseLoanView { val current=access.read("warehouse.loan.view");access.authorize(store.get(kind,id,type),current);return store.actionSnapshot(id,"ISSUE",type) }
    fun create(input: WarehouseLoanInput,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(null,input)
        access.replay(kind,"CREATE",key,canonical,current,type)?.let { return it }
        val warehouse=requests.warehouse(input.warehouseId,current)
        receiptText(input.borrowerName,200);requests.notes(input.notes)
        if(input.contact.length>300 || input.lines.size !in 1..100 || input.lines.map { it.skuId }.distinct().size!=input.lines.size ||
            input.dueDate!=null && input.dueDate<input.loanDate) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val lines=input.lines.map { line -> val sku=requests.sku(line.skuId,line.baseUnit);positiveReceiptQuantity(line.quantityBase,line.baseUnit)
            WarehouseLoanLine(UUID.randomUUID(),sku.id,sku.name,sku.baseUnit,sku.tracking,line.quantityBase) }
        policies.lockSettings();val now=referenceTimestamp()
        val view=WarehouseLoanView(UUID.randomUUID(),0,warehouse.id,warehouse.name ?: warehouse.code,input.borrowerName.trim(),input.contact.trim(),
            input.loanDate,input.dueDate,input.notes,"SUBMITTED",policies.settings().requireManagerApproval,lines,createdAt=now,updatedAt=now,policyRevision=policies.settings().revision)
        store.save(kind,view);return store.record(kind,"CREATE",key,view,current,canonical,manage,input.notes)
    }
    fun review(id: UUID,input: WarehouseLoanReview,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);requests.permission(current.current,"warehouse.request.review");val canonical=requests.canonical(id,input)
        access.replay(kind,"REVIEW",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state!="SUBMITTED" || input.lines.size!=prior.lines.size || input.lines.map { it.lineId }.toSet()!=prior.lines.map { it.id }.toSet()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        requests.notes(input.notes);val choices=input.lines.associateBy { it.lineId }
        val lines=prior.lines.map { line -> val choice=choices.getValue(line.id);val qty=requests.quantity(choice.approvedBase,line.baseUnit)
            if(qty>line.quantityBase.toLong() || choice.skuId!=null && choice.skuId!=line.skuId) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            line.copy(approvedBase=qty.toString()) }
        if(lines.none { it.approvedBase.toLong()>0 }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val view=prior.copy(revision=prior.revision+1,lines=lines,state=if(prior.requiresManagerApproval) "MANAGER_REVIEW" else "APPROVED",updatedAt=referenceTimestamp())
        store.save(kind,view);return store.record(kind,"REVIEW",key,view,current,canonical,manage,input.notes)
    }
    fun decide(id: UUID,input: ReferenceRequestDecision,key: String): WarehouseOperationReceipt {
        val current=requests.access(key);val canonical=requests.canonical(id,input)
        access.replay(kind,"DECIDE",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        val permission=if(prior.state=="MANAGER_REVIEW") "warehouse.request.approve" else manage
        requests.permission(current.current,permission)
        if(prior.state !in setOf("SUBMITTED","MANAGER_REVIEW") || input.approved && prior.state!="MANAGER_REVIEW") masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if(!input.approved) receiptText(input.reason,1000) else requests.notes(input.reason)
        val view=prior.copy(revision=prior.revision+1,state=if(input.approved) "APPROVED" else "REJECTED",updatedAt=referenceTimestamp())
        store.save(kind,view);return store.record(kind,"DECIDE",key,view,current,canonical,permission,input.reason)
    }
    fun issue(id: UUID,input: WarehouseLoanIssue,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(id,input)
        access.replay(kind,"ISSUE",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state!="APPROVED") masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptText(input.evidence,1000)
        val expected=prior.lines.filter { it.approvedBase.toLong()>0 }
        if(input.lines.size!=expected.size || input.lines.map { it.lineId }.toSet()!=expected.map { it.id }.toSet()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val identities=input.lines.flatMap { it.stocks }.map { it.stockIdentityId }
        if(identities.isEmpty() || identities.size>100 || identities.distinct().size!=identities.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val warehouse=requests.warehouse(prior.warehouseId,current)
        val destination=store.location("LOAN-$id",prior.borrowerName,"CUSTOMER_SITE",warehouse.id,warehouse.areaId)
        val movements=mutableListOf<UUID>();val choices=input.lines.associateBy { it.lineId }
        val lines=prior.lines.map { line ->
            val chosen=choices[line.id] ?: return@map line
            val sum=chosen.stocks.fold(0L) { total,item -> Math.addExact(total,positiveReceiptQuantity(item.quantityBase,line.baseUnit).quantityBase) }
            if(sum!=line.approvedBase.toLong()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST,"Jumlah pilihan harus sama dengan jumlah disetujui")
            val positions=chosen.stocks.map { item ->
                val moved=stock.move(id,prior.revision+1,manage,"CLIENT_ISSUE",item.stockIdentityId,prior.warehouseId,item.quantityBase,
                    prior.warehouseId,OwnerKind.WAREHOUSE,InventoryStatus.AVAILABLE,destination,id,OwnerKind.EXTERNAL,InventoryStatus.ISSUED,WarehouseCondition.SERVICEABLE,input.evidence,line.skuId)
                movements+=moved.documentId
                WarehouseLoanStock(moved.stockIdentityId,item.quantityBase,serial=store.serial(moved.stockIdentityId))
            }
            line.copy(stocks=positions)
        }
        val view=prior.copy(revision=prior.revision+1,state="ACTIVE",lines=lines,externalLocationId=destination,updatedAt=referenceTimestamp(),issueEvidence=input.evidence,issuedBy=store.actorName(current.current.fence.identity.userId))
        store.save(kind,view);return store.record(kind,"ISSUE",key,view,current,canonical,manage,input.evidence,movements)
    }
    fun receiveReturn(id: UUID,input: WarehouseLoanReturn,key: String): WarehouseOperationReceipt {
        val current=access.write(manage,key);val canonical=requests.canonical(id,input)
        access.replay(kind,"RETURN",key,canonical,current,type)?.let { return it }
        val prior=access.authorize(store.get(kind,id,type,true),current);access.revision(prior,input.expectedRevision)
        if(prior.state !in setOf("ACTIVE","PARTIALLY_RETURNED") || input.lines.size !in 1..100 ||
            input.lines.map { it.stockIdentityId }.distinct().size!=input.lines.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptText(input.evidence,1000);val warehouse=requests.warehouse(prior.warehouseId,current)
        val quarantine=store.location("CLIENT-QUARANTINE-${warehouse.id}","Karantina retur client","QUARANTINE",warehouse.id,warehouse.areaId)
        val movements=mutableListOf<UUID>();var lines=prior.lines
        input.lines.forEach { item ->
            val line=lines.singleOrNull { it.id==item.lineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            val position=line.stocks.singleOrNull { it.stockIdentityId==item.stockIdentityId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            val qty=positiveReceiptQuantity(item.quantityBase,line.baseUnit).quantityBase
            if(qty>position.quantityBase.toLong()-position.returnedBase.toLong() || item.condition !in setOf(WarehouseCondition.SERVICEABLE,WarehouseCondition.DAMAGED)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            if(line.tracking==WarehouseTracking.SERIAL && item.observedSerial?.trim()?.uppercase()!=position.serial?.trim()?.uppercase()) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED,"Serial fisik harus sesuai pinjaman")
            val good=item.condition==WarehouseCondition.SERVICEABLE;val destination=if(good) warehouse.id else quarantine
            val moved=stock.move(id,prior.revision+1,manage,"CLIENT_RETURN",item.stockIdentityId,requireNotNull(prior.externalLocationId),item.quantityBase,
                id,OwnerKind.EXTERNAL,InventoryStatus.ISSUED,destination,destination,OwnerKind.WAREHOUSE,
                if(good) InventoryStatus.AVAILABLE else InventoryStatus.QUARANTINE,item.condition,input.evidence,line.skuId)
            movements+=moved.documentId
            lines=lines.map { if(it.id==line.id) it.copy(stocks=it.stocks.map { held -> if(held.stockIdentityId==position.stockIdentityId)
                held.copy(stockIdentityId=moved.remainingIdentityId ?: held.stockIdentityId,returnedBase=Math.addExact(held.returnedBase.toLong(),qty).toString()) else held }) else it }
            if(!good) {
                val now=referenceTimestamp();val warranty=WarehouseWarrantyView(UUID.randomUUID(),0,warehouse.id,warehouse.name ?: warehouse.code,id,prior.borrowerName,
                    line.skuId,line.skuName,line.baseUnit,line.tracking,moved.stockIdentityId,item.quantityBase,position.serial,quarantine,"MASUK",evidence=input.evidence,createdAt=now,updatedAt=now)
                store.save("WARRANTY",warranty)
                store.record("WARRANTY","CREATE","loan-return:$id:${prior.revision+1}:${item.stockIdentityId}",warranty,current,
                    requests.canonical(id,mapOf("returnMovementId" to moved.documentId,"stockIdentityId" to moved.stockIdentityId)),manage,input.evidence)
            }
        }
        val complete=lines.flatMap { it.stocks }.all { it.returnedBase==it.quantityBase }
        val view=prior.copy(revision=prior.revision+1,state=if(complete) "RETURNED" else "PARTIALLY_RETURNED",lines=lines,updatedAt=referenceTimestamp())
        store.save(kind,view);return store.record(kind,"RETURN",key,view,current,canonical,manage,input.evidence,movements)
    }
}
