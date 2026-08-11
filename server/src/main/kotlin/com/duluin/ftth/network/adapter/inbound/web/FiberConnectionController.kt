package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.network.application.port.inbound.ClosureSpliceView
import com.duluin.ftth.network.application.port.inbound.ConnectFiberCommand
import com.duluin.ftth.network.application.port.inbound.ConnectionPointCommand
import com.duluin.ftth.network.application.port.inbound.FiberConnectionView
import com.duluin.ftth.network.application.port.inbound.ManageFiberConnectionUseCase
import com.duluin.ftth.network.application.port.inbound.SpliceWorkbenchView
import com.duluin.ftth.network.application.port.inbound.UpdateFiberConnectionCommand
import com.duluin.ftth.network.domain.model.ClosureKind
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Sambungan serat di dalam closure — bahan layar Splicing & Patching.
 *
 * Dikelompokkan per closure, bukan per kabel, karena begitulah pekerjaannya
 * dilakukan: satu kotak dibuka, semua sambungan di dalamnya terlihat sekaligus.
 */
@RestController
@RequestMapping("/api/fiber-connections")
@Tag(name = "Network — Sambungan serat")
@SecurityRequirement(name = "bearer-jwt")
class FiberConnectionController(
    private val manageConnection: ManageFiberConnectionUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('network.splice.view')")
    fun list(
        @RequestParam closureKind: ClosureKind,
        @RequestParam closureId: UUID,
    ): ClosureSpliceView = manageConnection.list(closureKind, closureId)

    /**
     * Seisi meja kerja: kabel yang lewat kotak ini beserta core-nya, titik simpul
     * yang tersedia, dan sambungan yang sudah ada — satu panggilan untuk satu
     * layar. Izinnya sama dengan [list]; yang dikembalikan cuma lebih lengkap,
     * bukan lebih rahasia.
     */
    @GetMapping("/workbench")
    @PreAuthorize("@authz.can('network.splice.view')")
    fun workbench(
        @RequestParam closureKind: ClosureKind,
        @RequestParam closureId: UUID,
    ): SpliceWorkbenchView = manageConnection.workbench(closureKind, closureId)

    /**
     * Pekerjaan serat yang dibukukan ke sebuah work order — bahan seksi "Pekerjaan
     * serat" di halaman WO. Berdiri di module network, bukan workorder, supaya
     * tiket tak perlu tahu apa-apa soal core dan closure.
     */
    @GetMapping("/by-work-order")
    @PreAuthorize("@authz.can('network.splice.view')")
    fun byWorkOrder(@RequestParam workOrderId: UUID): List<ClosureSpliceView> =
        manageConnection.byWorkOrder(workOrderId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.splice.manage')")
    fun connect(@Valid @RequestBody request: FiberConnectionRequest): FiberConnectionView =
        manageConnection.connect(
            ConnectFiberCommand(
                closureKind = request.closureKind,
                closureId = request.closureId,
                a = request.a.toCommand(),
                b = request.b.toCommand(),
                method = request.method,
                lossDb = request.lossDb,
                note = request.note,
                workOrderId = request.workOrderId,
            ),
        )

    /**
     * "Sambung 1:1 otomatis" — pasangannya dihitung di layar, di sini cuma
     * diterapkan. Semua atau tak sama sekali: satu pasangan ditolak berarti tak
     * ada yang tersimpan.
     */
    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('network.splice.manage')")
    fun connectAll(@Valid @RequestBody request: FiberBulkConnectRequest): List<FiberConnectionView> =
        manageConnection.connectAll(
            request.pairs.map { pair ->
                ConnectFiberCommand(
                    closureKind = request.closureKind,
                    closureId = request.closureId,
                    a = pair.a.toCommand(),
                    b = pair.b.toCommand(),
                    method = pair.method,
                    lossDb = pair.lossDb,
                    note = pair.note,
                    workOrderId = request.workOrderId,
                )
            },
        )

    /** Hasil ukur redaman & catatan; tak mengubah apa tersambung ke apa. */
    @PutMapping("/{id}")
    @PreAuthorize("@authz.can('network.splice.manage')")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: FiberSpliceDetailRequest,
    ): FiberConnectionView = manageConnection.update(
        id,
        UpdateFiberConnectionCommand(request.method, request.lossDb, request.note, request.workOrderId),
    )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('network.splice.manage')")
    fun disconnect(@PathVariable id: UUID) = manageConnection.disconnect(id)
}

private fun ConnectionPointRequest.toCommand() =
    ConnectionPointCommand(kind, coreId, nodeId, portNumber, portSide)
