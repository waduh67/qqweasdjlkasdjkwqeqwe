package com.duluin.ftth.tenancy.adapter.inbound.web

import com.duluin.ftth.tenancy.application.port.inbound.ExportTenantDataUseCase
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Ekspor data tenant SENDIRI (offboarding/portabilitas data) — beda controller dari
 * [TenantController] yang dipakai admin platform mengurus tenant orang lain: ini milik tenant,
 * digating izin tenant, dan tak pernah menerima id tenant dari luar.
 */
@RestController
@RequestMapping("/api/tenant")
@Tag(name = "Tenant")
@SecurityRequirement(name = "bearer-jwt")
class TenantDataExportController(
    private val export: ExportTenantDataUseCase,
) {
    /**
     * Arsip ditulis LANGSUNG ke aliran respons di thread request — bukan `StreamingResponseBody`
     * yang dieksekusi belakangan di thread lain, karena tenant aktif (dan karenanya baris yang
     * lolos RLS) hidup di ThreadLocal request ini. Konsekuensinya isi tak bisa disusun lebih dulu
     * untuk mengisi `Content-Length`; unduhan tanpa panjang yang diketahui adalah harga yang jauh
     * lebih murah daripada menaruh seluruh basis data tenant di heap.
     */
    @GetMapping("/export", produces = ["application/zip"])
    @PreAuthorize("@authz.can('tenancy.data.export')")
    fun export(response: HttpServletResponse) {
        response.contentType = "application/zip"
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${export.archiveName()}\"")
        export.exportCurrentTenant(response.outputStream)
        response.flushBuffer()
    }
}
