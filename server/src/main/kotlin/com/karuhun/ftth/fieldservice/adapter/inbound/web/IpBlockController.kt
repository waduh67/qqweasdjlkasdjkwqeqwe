package com.karuhun.ftth.fieldservice.adapter.inbound.web

import com.karuhun.ftth.common.domain.PageRequest
import com.karuhun.ftth.fieldservice.application.service.*
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/network-inventory")
class IpBlockController(private val blocks: IpBlockService,private val probes: IpProbeService) {
    @GetMapping("/blocks") @PreAuthorize("@authz.can('network.ip.view')")
    fun list(@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int) = blocks.list(PageRequest(page,size))
    @PostMapping("/blocks/preview") @PreAuthorize("@authz.can('network.ip.view')")
    fun preview(@RequestBody input: IpBlockInput) = blocks.preview(input)
    @PostMapping("/blocks") @PreAuthorize("@authz.can('network.ip.manage')")
    fun create(@RequestBody input: IpBlockInput,@RequestHeader("Idempotency-Key") key: UUID) = ResponseEntity.status(201).body(blocks.create(input,key))
    @PutMapping("/blocks/{id}/monitoring") @PreAuthorize("@authz.can('network.ip.manage')")
    fun monitoring(@PathVariable id: UUID,@RequestBody input: IpBlockMonitoringInput,@RequestHeader("Idempotency-Key") key: UUID) = blocks.monitoring(id,input,key)
    @PostMapping("/ips/{id}/ping") @PreAuthorize("@authz.can('network.ip.check')")
    fun ping(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: UUID) = ResponseEntity.accepted().body(probes.start(id,null,key))
    @PostMapping("/blocks/{id}/ping") @PreAuthorize("@authz.can('network.ip.check')")
    fun pingBlock(@PathVariable id: UUID,@RequestHeader("Idempotency-Key") key: UUID) = ResponseEntity.accepted().body(probes.start(null,id,key))
    @GetMapping("/probe-jobs/{id}") @PreAuthorize("@authz.can('network.ip.view')")
    fun job(@PathVariable id: UUID) = probes.job(id)
}
