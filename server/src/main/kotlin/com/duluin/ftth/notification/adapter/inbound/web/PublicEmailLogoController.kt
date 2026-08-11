package com.duluin.ftth.notification.adapter.inbound.web

import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.ManagePlatformEmailSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.ManageTenantEmailSettingsUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

/**
 * Penyaji logo email TANPA auth — tercakup whitelist rute publik di `SecurityConfig`.
 *
 * Harus publik karena pembacanya bukan browser yang login melainkan klien email pelanggan,
 * yang memuat `<img src>` dari kotak masuk tanpa membawa token apa pun. Yang tersaji hanyalah
 * gambar merek yang memang dimaksudkan untuk dipandang setiap penerima surat — bukan data
 * bisnis — jadi tak ada yang bocor dari sini yang tidak sudah beredar di ribuan kotak masuk.
 *
 * Logo tenant sengaja jatuh ke logo platform bila timpaannya tak ada: surat yang terlanjur
 * terkirim menyimpan URL bertenant selamanya, dan tenant yang menekan "kembalikan ke bawaan"
 * tak seharusnya membuat email lamanya berlubang gambar.
 */
@RestController
@RequestMapping("/api/public/email-logo")
@Tag(name = "Publik — Logo email")
class PublicEmailLogoController(
    private val platform: ManagePlatformEmailSettingsUseCase,
    private val tenant: ManageTenantEmailSettingsUseCase,
) {
    @GetMapping
    @Operation(summary = "Logo email bawaan platform")
    fun platformLogo(): ResponseEntity<ByteArray> = serve(platform.getLogo())

    @GetMapping("/{tenantId}")
    @Operation(summary = "Logo email tenant; jatuh ke logo platform bila tenant tak menimpanya")
    fun tenantLogo(@PathVariable tenantId: UUID): ResponseEntity<ByteArray> {
        // Request datang tanpa auth, jadi tenant-nya dipasang dari path — RLS lalu menyaring
        // baris timpaannya seperti biasa.
        val image = TenantContext.runAs(tenantId) { tenant.getLogo() } ?: platform.getLogo()
        return serve(image)
    }

    /**
     * Cache sehari. Klien email dan proxy gambar (Gmail memuat ulang lewat proxy-nya sendiri)
     * akan menghajar endpoint ini sekali per pembacaan surat; tanpa cache, satu siaran ke
     * sepuluh ribu pelanggan berarti sepuluh ribu unduhan logo yang sama.
     */
    private fun serve(image: StoredObject?): ResponseEntity<ByteArray> {
        if (image == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
            .body(image.bytes)
    }
}
