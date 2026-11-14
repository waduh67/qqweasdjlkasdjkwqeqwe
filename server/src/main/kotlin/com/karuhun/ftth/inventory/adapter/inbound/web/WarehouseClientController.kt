package com.karuhun.ftth.inventory.adapter.inbound.web

import com.karuhun.ftth.inventory.WarehouseOperationReceipt
import com.karuhun.ftth.inventory.application.port.inbound.*
import com.karuhun.ftth.inventory.application.service.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/warehouse")
class WarehouseClientController(private val purchases: WarehousePurchaseService,private val loans: WarehouseLoanService,private val warranties: WarehouseWarrantyService,private val overview: WarehouseClientOverviewService) {
    @GetMapping("/client-operations/overview") fun overview() = fresh(overview.overview())
    @GetMapping("/purchases") fun purchases(@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="25") size: Int,@RequestParam(defaultValue="") search: String) = fresh(purchases.list(page,size,search))
    @GetMapping("/purchases/{id}") fun purchase(@PathVariable id: UUID) = fresh(purchases.detail(id))
    @PostMapping("/purchases") fun purchase(@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(purchases.create(decode(body,WarehousePurchaseInput::class.java),key))
    @PostMapping("/purchases/{id}/receipts") fun receipt(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(purchases.receive(id,decode(body,WarehousePurchaseReceipt::class.java),key))
    @PostMapping("/purchases/{id}/cancel") fun cancel(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(purchases.cancel(id,decode(body,WarehouseClientRevision::class.java),key))
    @GetMapping("/loans") fun loans(@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="25") size: Int,@RequestParam(defaultValue="") search: String) = fresh(loans.list(page,size,search))
    @GetMapping("/loans/{id}") fun loan(@PathVariable id: UUID) = fresh(loans.detail(id))
    @GetMapping("/loans/{id}/issue-snapshot") fun issueSnapshot(@PathVariable id: UUID) = fresh(loans.issueSnapshot(id))
    @PostMapping("/loans") fun loan(@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(loans.create(decode(body,WarehouseLoanInput::class.java),key))
    @PostMapping("/loans/{id}/review") fun review(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(loans.review(id,decode(body,WarehouseLoanReview::class.java),key))
    @PostMapping("/loans/{id}/decision") fun decision(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(loans.decide(id,decode(body,ReferenceRequestDecision::class.java),key))
    @PostMapping("/loans/{id}/issue") fun issue(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(loans.issue(id,decode(body,WarehouseLoanIssue::class.java),key))
    @PostMapping("/loans/{id}/returns") fun returns(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(loans.receiveReturn(id,decode(body,WarehouseLoanReturn::class.java),key))
    @GetMapping("/warranties") fun warranties(@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="25") size: Int,@RequestParam(defaultValue="") search: String) = fresh(warranties.list(page,size,search))
    @GetMapping("/warranties/{id}") fun warranty(@PathVariable id: UUID) = fresh(warranties.detail(id))
    @PostMapping("/warranties/{id}/dispatch") fun dispatch(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(warranties.dispatch(id,decode(body,WarehouseWarrantyDispatch::class.java),key))
    @PostMapping("/warranties/{id}/receive") fun receive(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(warranties.receive(id,decode(body,WarehouseWarrantyReceipt::class.java),key))
    @PostMapping("/warranties/{id}/inspect") fun inspect(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: String,@RequestBody body: String) = result(warranties.inspect(id,decode(body,WarehouseWarrantyInspection::class.java),key))
    private fun <T> decode(body: String,type: Class<T>): T = WarehouseReceiptJson.decode(body,type)
    private fun <T> fresh(body: T) = ResponseEntity.ok().header("Cache-Control","no-store").body(body)
    private fun result(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus).header("Cache-Control","no-store").contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
