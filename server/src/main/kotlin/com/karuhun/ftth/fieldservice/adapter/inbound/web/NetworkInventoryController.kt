package com.karuhun.ftth.fieldservice.adapter.inbound.web

import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.fieldservice.application.service.*
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/network-inventory")
class NetworkInventoryController(private val service: NetworkInventoryService) {
    @GetMapping("/vendors") @PreAuthorize("@authz.can('network.ip.view')")
    fun vendors(@RequestParam(defaultValue="") q: String, @RequestParam(required=false) active: Boolean?,
        @RequestParam(defaultValue="0") page: Int, @RequestParam(defaultValue="20") size: Int) = service.vendors(q, active, PageRequest(page,size))
    @PostMapping("/vendors") @PreAuthorize("@authz.can('network.ip.manage')")
    fun createVendor(@RequestBody input: NetworkVendorInput, @RequestHeader("Idempotency-Key") key: UUID) =
        ResponseEntity.status(201).body(service.saveVendor(null,input,key))
    @GetMapping("/vendors/{id}") @PreAuthorize("@authz.can('network.ip.view')")
    fun vendor(@PathVariable id: UUID) = service.vendor(id)
    @PutMapping("/vendors/{id}") @PreAuthorize("@authz.can('network.ip.manage')")
    fun updateVendor(@PathVariable id: UUID, @RequestBody input: NetworkVendorInput, @RequestHeader("Idempotency-Key") key: UUID) = service.saveVendor(id,input,key)
    @DeleteMapping("/vendors/{id}") @PreAuthorize("@authz.can('network.ip.manage')")
    fun deleteVendor(@PathVariable id: UUID, @RequestParam expectedRevision: Long): ResponseEntity<Void> {
        service.deleteVendor(id,expectedRevision); return ResponseEntity.noContent().build()
    }
    @GetMapping("/ips") @PreAuthorize("@authz.can('network.ip.view')")
    fun ips(@RequestParam(defaultValue="") q: String, @RequestParam(required=false) status: String?, @RequestParam(required=false) vendorId: UUID?,
        @RequestParam(defaultValue="0") page: Int, @RequestParam(defaultValue="20") size: Int) = service.ips(q,status,vendorId,PageRequest(page,size))
    @PostMapping("/ips") @PreAuthorize("@authz.can('network.ip.manage')")
    fun createIp(@RequestBody input: PublicIpInput, @RequestHeader("Idempotency-Key") key: UUID) = ResponseEntity.status(201).body(service.saveIp(null,input,key))
    @PutMapping("/ips/{id}") @PreAuthorize("@authz.can('network.ip.manage')")
    fun updateIp(@PathVariable id: UUID, @RequestBody input: PublicIpInput, @RequestHeader("Idempotency-Key") key: UUID) = service.saveIp(id,input,key)
    @DeleteMapping("/ips/{id}") @PreAuthorize("@authz.can('network.ip.manage')")
    fun deleteIp(@PathVariable id: UUID, @RequestParam expectedRevision: Long): ResponseEntity<Void> {
        service.deleteIp(id,expectedRevision); return ResponseEntity.noContent().build()
    }
    @GetMapping("/clients/{id}") @PreAuthorize("@authz.can('network.ip.view')")
    fun client(@PathVariable id: UUID) = ResponseEntity.ok().header("Cache-Control", "private, no-store").body(service.clientNetwork(id))
}
