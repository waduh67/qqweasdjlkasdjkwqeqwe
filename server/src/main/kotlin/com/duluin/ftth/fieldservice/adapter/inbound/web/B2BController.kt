package com.duluin.ftth.fieldservice.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.fieldservice.application.service.*
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/b2b")
class B2BController(private val service: B2BService) {
    @GetMapping("/technicians")
    @PreAuthorize("@authz.can('b2b.client.view')")
    fun technicians(@RequestParam(defaultValue="") q: String,@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int)=
        service.technicians(q,PageRequest(page,size))

    @GetMapping("/clients")
    @PreAuthorize("@authz.can('b2b.client.view')")
    fun clients(@RequestParam(defaultValue="") q: String,@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int)=
        service.clients(q,PageRequest(page,size))

    @PostMapping("/clients")
    @PreAuthorize("@authz.can('b2b.client.manage')")
    fun create(@RequestBody input: B2BClientInput,@RequestHeader("Idempotency-Key") key: UUID)=
        ResponseEntity.status(201).body(service.save(null,input,key))

    @PutMapping("/clients/{id}")
    @PreAuthorize("@authz.can('b2b.client.manage')")
    fun update(@PathVariable id: UUID,@RequestBody input: B2BClientInput,@RequestHeader("Idempotency-Key") key: UUID)=service.save(id,input,key)

    @DeleteMapping("/clients/{id}")
    @PreAuthorize("@authz.can('b2b.client.manage')")
    fun delete(@PathVariable id: UUID,@RequestParam expectedRevision: Long): ResponseEntity<Void> {
        service.delete(id,expectedRevision); return ResponseEntity.noContent().build()
    }

    @GetMapping("/reports")
    @PreAuthorize("@authz.canAny('b2b.client.view','b2b.visit.view')")
    fun reports(@RequestParam month: YearMonth,@RequestParam(defaultValue="") q: String,
        @RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int)=service.reports(month,q,PageRequest(page,size))

    @GetMapping("/visits")
    @PreAuthorize("@authz.canAny('b2b.client.view','b2b.visit.view')")
    fun visits(@RequestParam month: YearMonth,@RequestParam(required=false) clientId: UUID?,
        @RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="20") size: Int)=service.visits(month,clientId,PageRequest(page,size))

    @PostMapping("/clients/{id}/visits",consumes=[MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("@authz.can('b2b.visit.report')")
    fun report(@PathVariable id: UUID,@RequestParam notes: String,@RequestParam photos: List<MultipartFile>,
        @RequestHeader("Idempotency-Key") key: UUID)=ResponseEntity.status(201).body(service.report(id,notes,
            photos.map { B2BPhotoInput(it.contentType.orEmpty(),it.bytes) },key))

    @GetMapping("/visits/{visit}/photos/{id}")
    @PreAuthorize("@authz.canAny('b2b.client.view','b2b.visit.view')")
    fun photo(@PathVariable visit: UUID,@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val photo=service.photo(visit,id)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType))
            .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,"inline").body(photo.bytes)
    }
}
