package com.duluin.ftth.onboarding

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ImportedAccessRef
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.ProvisionedAccessRef
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.RegisteredCustomer
import com.duluin.ftth.customer.UpdateCustomerBiodataCommand
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportRow
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportStatus
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportMode
import com.duluin.ftth.onboarding.MigrationFulfillmentPublisher
import com.duluin.ftth.onboarding.MigrationFulfillmentRequested
import com.duluin.ftth.onboarding.application.service.CustomerRowImporter
import com.duluin.ftth.onboarding.application.service.ImportCustomersService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

import com.duluin.ftth.onboarding.application.service.*

class ImportCustomersServiceTest {
    private val homePlan = PlanCommercialRef(UuidV7.generate(), "Home 20", BigDecimal("150000"), 20, true, null, 1, null, null, null)
    private val migration = RecordingMigration()
    private val customer = FakeCustomerApi()
    private val bng = FakeBngApi()
    private val service = ImportCustomersService(CustomerRowImporter(customer, FakeCatalogApi(mapOf("home 20" to homePlan)), bng, migration))
    private fun valid(username: String = "joko") = row(name = "Joko", address = "Jl. Melati", packageName = "Home 20", username = username)

    @Test fun `migration preserves dates price status and credentials without provisioning`() {
        val date = LocalDate.now().plusDays(30)
        val result = service.importCustomers(ImportCustomersCommand(listOf(valid().copy(serviceStatus = "SUSPENDED", installationDate = LocalDate.of(2020,1,2), firstInvoiceOn = date, nextBillingDay = 31, monthlyFee = BigDecimal("123000"), mikrotikPassword = "legacy"))))
        assertThat(result.created).isEqualTo(1)
        val command = migration.commands.single()
        assertThat(command.status).isEqualTo(MigrationStatus.SUSPENDED)
        assertThat(command.installationDate).isEqualTo(LocalDate.of(2020,1,2))
        assertThat(command.firstInvoiceOn).isEqualTo(date)
        assertThat(command.billingDay).isEqualTo(31)
        assertThat(command.customer.monthlyFeeOverride).isEqualTo(BigDecimal("123000"))
        assertThat(command.password).isEqualTo("legacy")
        assertThat(command.customer.location).isNull()
        assertThat(customer.registered).isEmpty()
        assertThat(bng.provisioned).isEmpty()
    }
    @Test fun `customer code supports migration without network credentials`() {
        val result = service.importCustomers(ImportCustomersCommand(listOf(valid().copy(mikrotikUsername = null, customerCode = "OLD-123"))))
        assertThat(result.created).isEqualTo(1)
        assertThat(migration.commands.single().customer.code).isEqualTo("OLD-123")
        assertThat(migration.commands.single().username).isNull()
    }
    @Test fun `bad status coordinate or package fails independently`() {
        val result = service.importCustomers(ImportCustomersCommand(listOf(valid("a").copy(serviceStatus = "STOPPED"), valid("b").copy(latitude = -6.0), valid("c").copy(packageName = "missing"), valid("d"))))
        assertThat(result.failed).isEqualTo(3)
        assertThat(result.created).isEqualTo(1)
        assertThat(migration.commands).hasSize(1)
    }
    @Test fun `existing source identity is skipped and later rows continue`() {
        migration.existing += "joko"
        val result = service.importCustomers(ImportCustomersCommand(listOf(valid(), valid("new"))))
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.created).isEqualTo(1)
    }
    @Test fun `validate only does not register or provision`() {
        val result = service.importCustomers(ImportCustomersCommand(listOf(valid()), mode = ImportMode.VALIDATE_ONLY))
        assertThat(result.skipped).isEqualTo(1)
        assertThat(migration.commands).isEmpty()
        assertThat(customer.registered).isEmpty()
        assertThat(bng.provisioned).isEmpty()
    }
    @Test fun `pending installation remains a prospect instead of migration`() {
        val result = service.importCustomers(ImportCustomersCommand(listOf(valid()), mode = ImportMode.PENDING_INSTALLATION))
        assertThat(result.created).isEqualTo(1)
        assertThat(customer.registered).hasSize(1)
        assertThat(migration.commands).isEmpty()
    }
    @Test fun `missing identity and unknown connection do not create customers`() {
        val result = service.importCustomers(ImportCustomersCommand(listOf(valid().copy(mikrotikUsername = null), valid().copy(connectionType = "invalid"))))
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.failed).isEqualTo(1)
        assertThat(migration.commands).isEmpty()
    }
    private fun row(
        name: String? = null,
        phone: String? = null,
        address: String? = null,
        packageName: String? = null,
        connectionType: String? = null,
        installationDate: LocalDate? = null,
        username: String? = null,
        password: String? = null,
        email: String? = null,
        routerName: String? = null,
        idCardNumber: String? = null,
        nextBillingDay: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        framedIp: String? = null,
    ) = CustomerImportRow(
        name = name,
        phone = phone,
        address = address,
        packageName = packageName,
        connectionType = connectionType,
        installationDate = installationDate,
        mikrotikUsername = username,
        mikrotikPassword = password,
        email = email,
        routerName = routerName,
        idCardNumber = idCardNumber,
        nextBillingDay = nextBillingDay,
        latitude = latitude,
        longitude = longitude,
        framedIp = framedIp,
    )

    private class FakeCatalogApi(
        private val plansByName: Map<String, PlanCommercialRef> = emptyMap(),
    ) : CatalogApi {
        override fun findPlanByName(name: String): PlanCommercialRef? = plansByName[name.trim().lowercase()]

        override fun findPlanCommercial(planId: UUID) = throw UnsupportedOperationException()
        override fun findPlanNetwork(planId: UUID) = throw UnsupportedOperationException()
        override fun findActivePlans() = throw UnsupportedOperationException()
    }

    private class FakeBngApi(
        private val accounts: Map<String, ImportedAccessRef> = emptyMap(),
        private val routers: Map<String, UUID> = emptyMap(),
    ) : BngApi {
        override fun findPppoeByCustomerIds(customerIds: Set<UUID>): Map<UUID, com.duluin.ftth.bng.SubscriberPppoeRef> =
            throw UnsupportedOperationException()

        val provisioned = mutableListOf<ProvisionAccessSpec>()
        val accessUpdates = mutableListOf<AccessUpdate>()

        override fun findAccessByUsername(username: String): ImportedAccessRef? = accounts[username.trim()]

        override fun resolveNasByName(name: String): UUID? = routers[name.trim().lowercase()]

        override fun provisionAccess(command: ProvisionAccessSpec): ProvisionedAccessRef {
            provisioned += command
            return ProvisionedAccessRef(UuidV7.generate(), command.username ?: "gen", "ACTIVE")
        }

        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) {
            accessUpdates += AccessUpdate(accessId, planId, nasId, secret)
        }

        override fun findSubscriberSession(customerId: UUID) = throw UnsupportedOperationException()
        override fun resolveNasForArea(areaId: UUID) = throw UnsupportedOperationException()
        override fun fetchPppSecretsFromNas(nasId: UUID) = throw UnsupportedOperationException()
        override fun activeSubscriberLiveness() = throw UnsupportedOperationException()
        override fun exportAccesses() = throw UnsupportedOperationException()
    }

    data class AccessUpdate(val accessId: UUID, val planId: UUID, val nasId: UUID?, val secret: String?)

    data class ActivatedImported(val subscriptionId: UUID, val activatedAt: Instant?, val billingDay: Int?)

    private class FakeCustomerApi : com.duluin.ftth.customer.CustomerApi {
        val registered = mutableListOf<RegisterCustomerCommand>()
        val biodataUpdates = mutableListOf<UpdateCustomerBiodataCommand>()
        val activatedImported = mutableListOf<ActivatedImported>()
        val billingOverrides = mutableListOf<Pair<UUID, Int?>>()

        override fun registerCustomer(command: RegisterCustomerCommand): RegisteredCustomer {
            registered += command
            return RegisteredCustomer(UuidV7.generate(), UuidV7.generate())
        }

        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: Instant?, billingDayOfMonth: Int?) {
            activatedImported += ActivatedImported(subscriptionId, activatedAt, billingDayOfMonth)
        }

        override fun updateCustomerBiodata(command: UpdateCustomerBiodataCommand) {
            biodataUpdates += command
        }

        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) {
            billingOverrides += subscriptionId to billingDayOfMonth
        }

        override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun subscriberStats() = throw UnsupportedOperationException()
        override fun findCustomer(id: UUID) = throw UnsupportedOperationException()
        override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
        override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
        override fun findSubscriptionByCustomer(customerId: UUID) = throw UnsupportedOperationException()
        override fun findOccupantsOfOdp(odpId: UUID) = throw UnsupportedOperationException()
        override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findPlacementOf(customerId: UUID) = throw UnsupportedOperationException()
        override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
        override fun countOccupantsByOdp(odpIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findOnusBySerialNumbers(serialNumbers: Set<String>) = throw UnsupportedOperationException()
        override fun placementsForOnus(onuIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun recordObservedOnuStatuses(statuses: Map<UUID, String>) = throw UnsupportedOperationException()
        override fun provisionOnu(command: com.duluin.ftth.customer.ProvisionOnuCommand) =
            throw UnsupportedOperationException()
        override fun findBillableSubscriptions() = throw UnsupportedOperationException()
        override fun findBillableSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun isolateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun subscriptionDimensions(subscriptionIds: Set<java.util.UUID>) = throw UnsupportedOperationException()
        override fun churnReport(from: java.time.LocalDate, to: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun findExportRows(subscriptionIds: Set<UUID>) = throw UnsupportedOperationException()
    }
}
