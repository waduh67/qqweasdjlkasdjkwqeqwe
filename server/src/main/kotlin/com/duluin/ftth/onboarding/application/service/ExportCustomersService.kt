package com.duluin.ftth.onboarding.application.service

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.onboarding.adapter.outbound.persistence.CustomerMigrationJpaRepository
import com.duluin.ftth.onboarding.application.port.inbound.CustomerExportLine
import com.duluin.ftth.onboarding.application.port.inbound.ExportCustomersUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset

/** Export recorded subscriptions even when their network account has not been migrated yet. */
@Service
@Transactional
class ExportCustomersService(
    private val customerApi: CustomerApi,
    private val bngApi: BngApi,
    private val migrations: CustomerMigrationJpaRepository,
    private val authority: CurrentAuthorityApi,
) : ExportCustomersUseCase {
    override fun exportCustomers(): List<CustomerExportLine> {
        val scope = authority.lockCurrent().areaScope
        val accesses = bngApi.exportAccesses().associateBy { it.subscriptionId }
        val imported = migrations.findAll().associateBy { it.subscriptionId }
        val ids = accesses.keys + imported.keys
        if (ids.isEmpty()) return emptyList()
        return customerApi.findExportRows(ids).filter { scope !is AuthorityScope.Restricted || it.areaId in scope.ids }.map { row ->
            val access = accesses[row.subscriptionId]
            val migration = imported[row.subscriptionId]
            CustomerExportLine(
                name = row.name, phone = row.phone, address = row.address, packageName = row.packageName,
                connectionType = (access?.authType ?: migration?.authType ?: "PPPOE").lowercase(),
                installationDate = row.activatedAt?.atZone(ZoneOffset.UTC)?.toLocalDate(),
                mikrotikUsername = access?.username ?: migration?.username.orEmpty(),
                email = row.email, routerName = access?.nasName, idCardNumber = row.idCardNumber,
                nextBillingDay = row.billingDayOfMonth, latitude = row.location?.latitude, longitude = row.location?.longitude,
                framedIp = access?.framedIp ?: migration?.framedIp, customerCode = row.customerCode,
                serviceStatus = row.serviceStatus, firstInvoiceOn = row.firstInvoiceOn, monthlyFee = row.monthlyFee,
            )
        }
    }
}
