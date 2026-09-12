package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.application.port.outbound.MovementFilter
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import com.duluin.ftth.inventory.domain.model.MovementState
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Permukaan BACA gudang: riwayat mutasi, saldo, van stock teknisi, dan laporan selisih.
 *
 * Semuanya di balik `inventory.movement.view`. ATURAN RBAC repo ini: hanya akhiran `.view`
 * yang berarti baca — menaruh laporan di bawah izin bernama lain akan membuat langganan
 * data di web (`useCan`) mengira halaman ini butuh hak tulis dan menyembunyikannya dari
 * orang yang justru bertugas mengawasi.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryLedgerController(
    private val queries: InventoryStockQueryService,
    private val currentUser: CurrentUserProvider,
) {
    /**
     * Riwayat mutasi ber-halaman. Ledger gudang tidak pernah dipangkas, jadi tidak ada versi
     * "ambil semua" di sini: satu tenant yang sudah berjalan setahun akan menjatuhkan server
     * hanya untuk menampilkan 20 baris pertama.
     */
    @GetMapping("/ledger")
    @PreAuthorize("@authz.can('inventory.movement.view')")
    @Suppress("LongParameterList")
    fun ledger(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) kind: MovementKind?,
        @RequestParam(required = false) state: MovementState?,
        @RequestParam(required = false) itemId: UUID?,
        @RequestParam(required = false) locationId: UUID?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) until: Instant?,
    ): PageResponse<MovementEntryView> = PageResponse.from(
        queries.ledgerPage(
            currentUser.current().tenantId,
            MovementFilter(kind, state, itemId, locationId, from, until),
            PageRequest(page, size),
        ),
    )

    @GetMapping("/balances")
    @PreAuthorize("@authz.can('inventory.movement.view')")
    fun balances(
        @RequestParam(required = false) itemId: UUID?,
        @RequestParam(required = false) locationId: UUID?,
    ): List<StockBalanceView> = queries.balances(currentUser.current().tenantId, itemId, locationId)

    @GetMapping("/van-stock")
    @PreAuthorize("@authz.can('inventory.movement.view')")
    fun vanStock(@RequestParam(required = false) technicianId: UUID?): List<VanStockView> =
        queries.vanStock(currentUser.current().tenantId, technicianId)

    @GetMapping("/variance-report")
    @PreAuthorize("@authz.can('inventory.movement.view')")
    fun variance(): VarianceReportView = queries.varianceReport(currentUser.current().tenantId)
}
