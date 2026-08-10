package com.duluin.ftth.monitoring.adapter.inbound.web

import com.duluin.ftth.monitoring.application.port.inbound.OltSnmpCheck
import com.duluin.ftth.monitoring.application.port.inbound.OltSnmpWalk
import com.duluin.ftth.monitoring.application.port.inbound.SnmpDiagnosticUseCase
import io.swagger.v3.oas.annotations.Operation
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
 * Alat validasi OID di lapangan: server yang bertanya, teknisi yang membaca.
 *
 * Sasarannya selalu OLT yang sudah ada di inventory tenant — bukan host bebas dari
 * pemanggil — supaya endpoint ini tak bisa dipelintir jadi pemindai jaringan dari dalam
 * server. Kredensial perangkat pun tetap di server; yang kembali ke browser cuma
 * pembacaannya.
 *
 * Izinnya menumpang `monitoring.collector.manage` ("Kelola collector & polling") karena
 * inilah kerja yang sama: menyetel dan membuktikan jalur polling.
 */
@RestController
@RequestMapping("/api/monitoring/olts")
@Tag(name = "Monitoring — Diagnostik SNMP")
@SecurityRequirement(name = "bearer-jwt")
class SnmpDiagnosticController(
    private val useCase: SnmpDiagnosticUseCase,
) {
    @GetMapping("/{id}/snmp-check")
    @PreAuthorize("@authz.can('monitoring.collector.manage')")
    @Operation(summary = "Uji seluruh OID profil vendor OLT ini terhadap perangkat sungguhan")
    fun check(@PathVariable id: UUID): OltSnmpCheck = useCase.checkOidPlan(id)

    @GetMapping("/{id}/snmp-walk")
    @PreAuthorize("@authz.can('monitoring.collector.manage')")
    @Operation(summary = "Walk sub-tree OID bebas pada OLT ini — untuk berburu OID yang benar saat profil meleset")
    fun walk(
        @PathVariable id: UUID,
        // Bentuk & kedalaman OID divalidasi di service (bukan di sini) supaya aturannya
        // ikut berlaku bagi pemanggil lain dan bisa diuji tanpa menyalakan MVC.
        @RequestParam oid: String,
        @RequestParam(defaultValue = "50") limit: Int,
    ): OltSnmpWalk = useCase.walk(id, oid, limit)
}
