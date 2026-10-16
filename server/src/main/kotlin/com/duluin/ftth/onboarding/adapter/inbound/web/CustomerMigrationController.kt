package com.duluin.ftth.onboarding.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.onboarding.application.service.*
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@Component
class MigrationAreaAccess(private val authority: com.duluin.ftth.iam.CurrentAuthorityApi, private val areas: com.duluin.ftth.iam.IamApi) {
    @org.springframework.transaction.annotation.Transactional
    fun requireArea(areaId: UUID?) {
        val scope = authority.lockCurrent().areaScope
        if (scope is com.duluin.ftth.common.security.AuthorityScope.Restricted && areaId !in scope.ids) throw ValidationException("Pilih area yang dapat Anda kelola")
        if (areaId != null && areas.areasByIds(setOf(areaId)).isEmpty()) throw ValidationException("Area tidak ditemukan")
    }
}

@RestController
@RequestMapping("/api/onboarding/migrations")
class CustomerMigrationController(private val service: CustomerMigrationService, private val access: MigrationAreaAccess) {
    @PostMapping
    @PreAuthorize("@authz.canAll('customer.customer.create','customer.subscription.update')")
    fun register(@RequestBody command: CustomerMigrationCommand): CustomerMigrationView {
        access.requireArea(command.customer.areaId)
        return service.register(command.copy(source = MigrationSource.MANUAL))
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("@authz.can('customer.customer.view')")
    fun get(@PathVariable customerId: UUID) = service.get(customerId)

    @PostMapping("/{customerId}/billing")
    @PreAuthorize("@authz.can('customer.subscription.update')")
    fun billing(@PathVariable customerId: UUID, @RequestBody command: StartMigrationBillingRequest) =
        service.startBilling(customerId, command.firstInvoiceOn, command.billingDay)

    @PostMapping("/{customerId}/radius")
    @PreAuthorize("@authz.can('bng.access.manage')")
    fun radius(@PathVariable customerId: UUID, @RequestBody command: MigrationRadiusCommand) =
        service.prepareRadius(customerId, command)
}

data class StartMigrationBillingRequest(val firstInvoiceOn: LocalDate, val billingDay: Int? = null)
