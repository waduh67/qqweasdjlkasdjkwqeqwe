package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReservationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReservationValidationMode
import com.duluin.ftth.inventory.application.port.outbound.ReservationState
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper

@Service
class WarehouseReservationExpiry(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val workOrders: InventoryReservationWorkOrderPort, private val masters: WarehouseMasterStore,
    private val store: WarehouseReservationStore, private val reservations: WarehouseReservationService) {
    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    fun expireOne(): Boolean {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val fence = authority.lockForChange()
        fence.assertHeld()
        val id = store.due() ?: return false
        val preview = store.demand(id)
        workOrders.lock(preview.workOrder, null, null)
        masters.lockTopology()
        val lines = store.lines(preview, ReservationValidationMode.BOUND_LIFECYCLE)
        val before = store.rows(id)
        store.lockDocuments(listOf(id) + store.originDocuments(before))
        val document = store.demand(id)
        val rows = store.rows(id)
        val now = store.now()
        val changes = rows.filter { it.state == ReservationState.OPEN && it.unpicked.quantityBase > 0 && it.expiresAt <= now }.map { row ->
            row.copy(unpicked = StockQuantity.of(0, row.unpicked.unit), state = if (row.picked.quantityBase == 0L) ReservationState.EXPIRED else ReservationState.OPEN)
        }
        if (changes.isEmpty()) return false
        store.lockStock(emptyList(), changes)
        val key = "$id:${document.revision}"
        val canonical = WarehouseCanonicalPayload.parse(jacksonObjectMapper().writeValueAsString(mapOf("documentId" to id,
            "revision" to document.revision, "expired" to changes.map { it.id })))
        reservations.persist(document, ReservationAction.RELEASE, changes, lines, rows, emptyList(), cutover, document.actor,
            fence.epoch, "warehouse.reservation.expire", key, canonical, "SYSTEM: unpicked reservation expiry", now, null, WarehouseEventKind.RESERVATION_EXPIRED)
        return true
    }
}

@Component
@ConditionalOnProperty(name = ["ftth.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class WarehouseReservationExpirySchedule(private val tenants: TenantApi, private val expiry: WarehouseReservationExpiry) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${ftth.warehouse.reservation-expiry-delay:PT1M}")
    fun expire() {
        tenants.findActiveTenantIds().forEach { tenant ->
            try { TenantContext.runAs(tenant) { repeat(25) { if (!expiry.expireOne()) return@runAs } } }
            catch (failure: Exception) {
                val code = if (failure is WarehouseContractException) failure.error.code.name else "EXPIRY_FAILURE"
                log.warn("warehouse_reservation_expiry_failed tenant={} code={}", tenant, code)
            }
        }
    }
}
