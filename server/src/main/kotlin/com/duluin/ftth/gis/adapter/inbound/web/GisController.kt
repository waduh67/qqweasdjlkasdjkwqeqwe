package com.duluin.ftth.gis.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.gis.application.port.inbound.CustomerTrace
import com.duluin.ftth.gis.application.port.inbound.MapQuery
import com.duluin.ftth.gis.application.port.inbound.OdpInspection
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/gis")
@Tag(name = "GIS")
@SecurityRequirement(name = "bearer-jwt")
class GisController(
    private val mapQuery: MapQuery,
) {
    /**
     * Vector tile untuk MapLibre.
     *
     * Cache-nya `private`: isi tile bergantung pada tenant dan batasan area
     * pengguna, jadi tidak boleh disimpan cache bersama. Umurnya pendek karena
     * inventory jaringan berubah sepanjang hari kerja.
     */
    @GetMapping("/tiles/{z}/{x}/{y}.mvt", produces = [MVT_MEDIA_TYPE])
    @PreAuthorize("@authz.can('gis.map.view')")
    fun tile(
        @PathVariable z: Int,
        @PathVariable x: Int,
        @PathVariable y: Int,
    ): ResponseEntity<ByteArray> {
        assertValidTileCoordinates(z, x, y)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
            .contentType(MediaType.parseMediaType(MVT_MEDIA_TYPE))
            .body(mapQuery.renderTile(z, x, y))
    }

    @GetMapping("/odps/{id}")
    @PreAuthorize("@authz.can('gis.map.view') and @authz.can('network.odp.view')")
    fun inspectOdp(@PathVariable id: UUID): OdpInspection = mapQuery.inspectOdp(id)

    @GetMapping("/trace/customers/{id}")
    @PreAuthorize("@authz.can('gis.map.view') and @authz.can('customer.customer.view')")
    fun traceCustomer(@PathVariable id: UUID): CustomerTrace = mapQuery.traceCustomer(id)

    /**
     * Koordinat tile di luar rentang membuat `ST_TileEnvelope` melempar galat
     * dari dalam database. Ditolak lebih awal agar klien dapat 400 yang jelas,
     * bukan 500 yang membingungkan.
     */
    private fun assertValidTileCoordinates(z: Int, x: Int, y: Int) {
        if (z !in 0..MAX_ZOOM) throw ValidationException("Zoom harus 0-$MAX_ZOOM")
        val maxIndex = (1L shl z) - 1
        if (x !in 0..maxIndex || y !in 0..maxIndex) {
            throw ValidationException("Koordinat tile di luar rentang untuk zoom $z (0-$maxIndex)")
        }
    }

    private companion object {
        const val MVT_MEDIA_TYPE = "application/vnd.mapbox-vector-tile"
        const val MAX_ZOOM = 22
    }
}
