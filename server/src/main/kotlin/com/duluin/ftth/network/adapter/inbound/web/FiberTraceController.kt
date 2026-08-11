package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.network.application.port.inbound.ConnectionPointCommand
import com.duluin.ftth.network.application.port.inbound.FiberPathView
import com.duluin.ftth.network.application.port.inbound.PonPortLoadView
import com.duluin.ftth.network.application.port.inbound.TraceFiberPathUseCase
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.OdfPortSide
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Telusur jalur serat & anggaran redamannya.
 *
 * Semuanya GET dan semuanya berizin `network.splice.view`: menelusuri tidak
 * mengubah apa pun, dan orang yang boleh melihat isi kotak sambung sudah boleh
 * tahu ke mana isinya bermuara. Meminta izin terpisah cuma akan membuat teknisi
 * yang sedang mengejar gangguan berhenti untuk menelepon admin.
 */
@RestController
@RequestMapping("/api/fiber-trace")
@Tag(name = "Network — Telusur jalur serat")
@SecurityRequirement(name = "bearer-jwt")
class FiberTraceController(
    private val trace: TraceFiberPathUseCase,
) {
    /**
     * Telusur dari satu titik. Bentuk parameternya sengaja sama dengan
     * ConnectionPointRequest supaya layar splicing bisa menelusuri slot yang
     * barusan diklik tanpa menyusun bentuk baru.
     */
    @GetMapping("/point")
    @PreAuthorize("@authz.can('network.splice.view')")
    fun point(
        @RequestParam kind: ConnectionPointKind,
        @RequestParam(required = false) coreId: UUID? = null,
        @RequestParam(required = false) nodeId: UUID? = null,
        @RequestParam(required = false) portNumber: Int? = null,
        @RequestParam(required = false) portSide: OdfPortSide? = null,
    ): FiberPathView = trace.traceUpstream(ConnectionPointCommand(kind, coreId, nodeId, portNumber, portSide))

    /** Semua jalur yang bermuara di sebuah kotak — bentuk yang dipakai panel detail. */
    @GetMapping("/closure")
    @PreAuthorize("@authz.can('network.splice.view')")
    fun closure(
        @RequestParam closureKind: ClosureKind,
        @RequestParam closureId: UUID,
    ): List<FiberPathView> = trace.traceClosure(closureKind, closureId)

    /**
     * Muatan sebuah port PON. Dipisah dari dua endpoint di atas karena arahnya
     * memang lain — ke hilir, dan hasilnya rekap, bukan rantai hop.
     */
    @GetMapping("/pon-port/{id}")
    @PreAuthorize("@authz.can('network.splice.view')")
    fun ponPortLoad(@PathVariable id: UUID): PonPortLoadView = trace.tracePonPortLoad(id)
}
