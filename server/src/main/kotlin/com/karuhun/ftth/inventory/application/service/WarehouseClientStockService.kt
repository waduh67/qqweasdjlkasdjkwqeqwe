package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.application.port.inbound.masterFailure
import com.karuhun.ftth.inventory.adapter.outbound.persistence.*
import com.karuhun.ftth.inventory.application.port.outbound.*
import com.karuhun.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class ClientStockMove(val documentId: UUID,val stockIdentityId: UUID,val remainingIdentityId: UUID?,val quantityBase: String)

@Service
@Transactional
class WarehouseClientStockService(private val requests: ReferenceRequestService,private val stock: WarehouseTransferStock,
    private val reference: ReferenceWarehouseStore,private val clients: WarehouseClientStore,private val posting: WarehousePosting,
    private val operations: WarehouseOperationStore) {
    private val mapper=jacksonObjectMapper()
    internal fun move(resource: UUID,revision: Long,permission: String,action: String,identity: UUID,source: UUID,
        quantity: String,expectedCustodian: UUID,expectedKind: OwnerKind,expectedStatus: InventoryStatus,
        destination: UUID,custodian: UUID,kind: OwnerKind,status: InventoryStatus,condition: WarehouseCondition,evidence: String,expectedSkuId: UUID? = null): ClientStockMove {
        val key="client:$resource:$revision:$action:$identity"
        val access=requests.access(key);requests.permission(access.current,permission)
        val canonical=requests.canonical(resource,mapOf("revision" to revision,"action" to action,"identity" to identity,"source" to source,
            "quantity" to quantity,"destination" to destination,"custodian" to custodian,"kind" to kind,"status" to status,"condition" to condition,"evidence" to evidence))
        val namespace="warehouse.reference."+action.lowercase()
        operations.lockKey(namespace,key)?.let { previous ->
            if(previous.actorId!=access.current.fence.identity.userId || previous.hash!=canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            return mapper.readValue(previous.receipt.originalBody,ClientStockMove::class.java)
        }
        val piece=stock.get(identity,source);stock.assertUnallocated(identity)
        if(expectedSkuId!=null && piece.dimension.skuId!=expectedSkuId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val qty=positiveReceiptQuantity(quantity,piece.unit).quantityBase
        if(piece.dimension.custodianId!=expectedCustodian || piece.dimension.custodianKind!=expectedKind || piece.status!=expectedStatus ||
            piece.dimension.legalOwner!=AssetLegalOwner.ISP) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if(expectedStatus==InventoryStatus.AVAILABLE && piece.dimension.condition!=WarehouseCondition.SERVICEABLE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if(qty>piece.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        if(piece.tracking==WarehouseTracking.SERIAL && qty!=1L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val split=piece.tracking!=WarehouseTracking.SERIAL && qty<piece.quantity
        val target=if(split) UUID.randomUUID() else identity
        val remainder=if(split) UUID.randomUUID() else if(qty<piece.quantity) identity else null
        val unit=StockUnit.valueOf(piece.unit.name);val line=TransferLine(UUID.randomUUID(),piece,qty)
        val document=UUID.randomUUID();val operation=UUID.randomUUID()
        reference.transferDraft(document,source,destination,listOf(line),access.current.fence.identity.userId,access.current.fence.epoch,access.cutover.snapshot.epoch,evidence)
        val legs=mutableListOf(
            PostingLeg(LegDirection.OUT,piece.dimension,StockQuantity.of(if(split) piece.quantity else qty,unit),line.id,piece.status),
            PostingLeg(LegDirection.IN,piece.dimension.copy(stockIdentityId=target,locationId=destination,custodianId=custodian,custodianKind=kind,condition=condition),StockQuantity.of(qty,unit),line.id,status))
        val splits=if(split) {
            legs+=PostingLeg(LegDirection.IN,piece.dimension.copy(stockIdentityId=requireNotNull(remainder)),StockQuantity.of(piece.quantity-qty,unit),line.id,piece.status)
            listOf(PostingSplit(identity,piece.revision,listOf(SegmentChild(target,StockQuantity.of(qty,unit),if(unit==StockUnit.MM) SegmentKind.CUT else SegmentKind.BULK),
                SegmentChild(remainder,StockQuantity.of(piece.quantity-qty,unit),if(unit==StockUnit.MM) SegmentKind.REMNANT else SegmentKind.BULK))))
        } else emptyList()
        val result=ClientStockMove(document,target,remainder,quantity)
        val command=WarehousePost(document,0,"RECEIVED",PostingOperation(operation,namespace,key,access.current.fence.identity.userId,document,
            "reference:$document",canonical.hash,action,201,mapper.writeValueAsString(result),access.current.fence.epoch,referenceTimestamp()),MovementKind.TRANSFER,evidence,legs,splits=splits)
        reference.bind(command,source,destination,action);clients.bindMovement(document,resource,revision)
        posting.post(command,access.cutover);operations.storeIdentity(operation,canonical.json,access.current.fence.identity.sessionId)
        return result
    }
}
