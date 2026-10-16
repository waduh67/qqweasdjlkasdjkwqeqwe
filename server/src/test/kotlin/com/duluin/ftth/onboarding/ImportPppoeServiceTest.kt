package com.duluin.ftth.onboarding

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.PppSecretRef
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.ProvisionedAccessRef
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.RegisteredCustomer
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportRow
import com.duluin.ftth.onboarding.application.port.inbound.ImportRowStatus
import com.duluin.ftth.onboarding.application.port.inbound.ImportSource
import com.duluin.ftth.onboarding.application.port.inbound.ImportMode
import com.duluin.ftth.onboarding.MigrationFulfillmentPublisher
import com.duluin.ftth.onboarding.MigrationFulfillmentRequested
import com.duluin.ftth.onboarding.application.service.ImportPppoeService
import com.duluin.ftth.onboarding.application.service.PppoeRowImporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

import com.duluin.ftth.onboarding.application.service.*

class ImportPppoeServiceTest {
    private val nasId = UuidV7.generate()
    private val planId = UuidV7.generate()
    private val migration = RecordingMigration()
    private val customer = FakeCustomerApi()
    private val bng = FakeBngApi()
    private val service = ImportPppoeService(bng, PppoeRowImporter(customer, migration))
    private fun command(rows: List<ImportRow>) = ImportPppoeCommand(nasId, ImportSource.INLINE, rows, emptyMap(), planId, false, null, null, null, null)

    @Test fun `disabled source accounts remain suspended and passwords are retained for later preparation`() {
        val result = service.importPppoe(command(listOf(ImportRow("a", "old", null, "Ani", false), ImportRow("b", "old2", null, null, true))))
        assertThat(result.created).isEqualTo(2)
        assertThat(migration.commands.map { it.status }).containsExactly(MigrationStatus.ACTIVE, MigrationStatus.SUSPENDED)
        assertThat(migration.commands.map { it.password }).containsExactly("old", "old2")
        assertThat(migration.commands.first().customer.name).isEqualTo("Ani")
        assertThat(migration.commands.first().customer.location).isNull()
        assertThat(bng.provisioned).isEmpty()
        assertThat(customer.activatedCount).isZero()
    }
    @Test fun `only selected accounts are imported with profile package and billing defaults`() {
        val mapped = UuidV7.generate()
        val date = java.time.LocalDate.now().plusDays(20)
        val result = service.importPppoe(command(listOf(ImportRow("a", null, "premium", null, false), ImportRow("b", null, null, null, false))).copy(onlyNames = setOf("a"), profilePlanId = mapOf("premium" to mapped), firstInvoiceOn = date, billingDay = 31))
        assertThat(result.created).isEqualTo(1)
        assertThat(migration.commands.single().customer.planId).isEqualTo(mapped)
        assertThat(migration.commands.single().firstInvoiceOn).isEqualTo(date)
        assertThat(migration.commands.single().billingDay).isEqualTo(31)
    }
    @Test fun `existing identity and missing mappings are skipped`() {
        migration.existing += "a"
        val result = service.importPppoe(command(listOf(ImportRow("a", null, null, null, false), ImportRow("b", null, null, null, false))))
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.created).isEqualTo(1)
        val missing = service.importPppoe(command(listOf(ImportRow("c", null, null, null, false))).copy(defaultPlanId = null))
        assertThat(missing.skipped).isEqualTo(1)
    }
    @Test fun `validation and disabled exclusion never write service records`() {
        val rows = listOf(ImportRow("a", null, null, null, true))
        assertThat(service.importPppoe(command(rows).copy(skipDisabled = true)).created).isZero()
        assertThat(service.importPppoe(command(rows).copy(mode = ImportMode.VALIDATE_ONLY)).created).isZero()
        assertThat(migration.commands).isEmpty()
    }
    private class FakeBngApi(
        private val onNas: List<PppSecretRef> = emptyList(),
    ) : BngApi {
        override fun findPppoeByCustomerIds(customerIds: Set<UUID>): Map<UUID, com.duluin.ftth.bng.SubscriberPppoeRef> =
            throw UnsupportedOperationException()

        val provisioned = mutableListOf<ProvisionAccessSpec>()

        override fun fetchPppSecretsFromNas(nasId: UUID): List<PppSecretRef> = onNas

        override fun provisionAccess(command: ProvisionAccessSpec): ProvisionedAccessRef {
            provisioned += command
            return ProvisionedAccessRef(UuidV7.generate(), command.username ?: "gen", "ACTIVE")
        }

        override fun findSubscriberSession(customerId: UUID): SubscriberSessionRef? =
            throw UnsupportedOperationException()

        override fun resolveNasForArea(areaId: UUID): UUID? = throw UnsupportedOperationException()

        override fun activeSubscriberLiveness() = throw UnsupportedOperationException()

        override fun resolveNasByName(name: String) = throw UnsupportedOperationException()
        override fun findAccessByUsername(username: String) = throw UnsupportedOperationException()
        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) = throw UnsupportedOperationException()
        override fun exportAccesses(): List<com.duluin.ftth.bng.AccessExportRef> = throw UnsupportedOperationException()
    }

    private class FakeCustomerApi(
        private val existingCodes: Set<String> = emptySet(),
    ) : CustomerApi {
        val registered = mutableListOf<RegisterCustomerCommand>()
        var activatedCount = 0

        override fun registerCustomer(command: RegisterCustomerCommand): RegisteredCustomer {
            if (command.code in existingCodes) throw ConflictException("Pelanggan '${command.code}' sudah ada")
            registered += command
            return RegisteredCustomer(UuidV7.generate(), UuidV7.generate())
        }

        override fun activateForInstallation(subscriptionId: UUID) {
            activatedCount++
        }

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
        override fun updateCustomerBiodata(command: com.duluin.ftth.customer.UpdateCustomerBiodataCommand) = throw UnsupportedOperationException()
        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: java.time.Instant?, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
        override fun subscriptionDimensions(subscriptionIds: Set<java.util.UUID>) = throw UnsupportedOperationException()
        override fun churnReport(from: java.time.LocalDate, to: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun findExportRows(subscriptionIds: Set<java.util.UUID>): List<com.duluin.ftth.customer.CustomerExportRow> = throw UnsupportedOperationException()
    }
}
