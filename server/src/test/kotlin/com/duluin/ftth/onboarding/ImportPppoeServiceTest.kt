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
import com.duluin.ftth.onboarding.application.port.inbound.ImportPppoeCommand
import com.duluin.ftth.onboarding.application.port.inbound.ImportRow
import com.duluin.ftth.onboarding.application.port.inbound.ImportRowStatus
import com.duluin.ftth.onboarding.application.port.inbound.ImportSource
import com.duluin.ftth.onboarding.application.service.ImportPppoeService
import com.duluin.ftth.onboarding.application.service.PppoeRowImporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Menguji orkestrasi bulk-import PPPoE dengan fake murni (tanpa Spring/DB): tiap baris
 * `/ppp/secret` → daftar pelanggan + buka langganan + aktifkan + provisi akun PPPoE ke RADIUS,
 * dengan password ASLI router dan paket hasil pemetaan profil. Menegakkan: mapping profil→paket
 * + fallback default, lewati disabled/username-sudah-ada, penyaringan onlyNames, dan penarikan
 * sumber NAS lewat [BngApi.fetchPppSecretsFromNas].
 */
class ImportPppoeServiceTest {

    private val nasId: UUID = UuidV7.generate()
    private val vipPlan: UUID = UuidV7.generate()
    private val defaultPlan: UUID = UuidV7.generate()

    @Test
    fun `INLINE dua baris ter-map jadi CREATED dan memprovisi RADIUS dengan password asli`() {
        val bng = FakeBngApi()
        val customer = FakeCustomerApi()
        val result = ImportPppoeService(bng, PppoeRowImporter(customer, bng)).importPppoe(
            command(
                source = ImportSource.INLINE,
                rows = listOf(
                    ImportRow("budi", "pwd-budi", "vip", "Budi Santoso", disabled = false),
                    ImportRow("siti", "pwd-siti", "vip", null, disabled = false),
                ),
                profilePlanId = mapOf("vip" to vipPlan),
            ),
        )

        assertThat(result.created).isEqualTo(2)
        assertThat(result.skipped).isZero()
        assertThat(result.failed).isZero()
        assertThat(bng.provisioned.map { it.username }).containsExactly("budi", "siti")
        val budi = bng.provisioned.first { it.username == "budi" }
        assertThat(budi.secret).isEqualTo("pwd-budi")
        assertThat(budi.planId).isEqualTo(vipPlan)
        assertThat(budi.nasId).isEqualTo(nasId)
        assertThat(budi.authType).isEqualTo("PPPOE")
        // comment jadi nama pelanggan; tanpa comment jatuh ke username.
        assertThat(customer.registered.first { it.code == "budi" }.name).isEqualTo("Budi Santoso")
        assertThat(customer.registered.first { it.code == "siti" }.name).isEqualTo("siti")
        assertThat(customer.activatedCount).isEqualTo(2)
    }

    @Test
    fun `profil tak dipetakan tanpa default dilewati`() {
        val bng = FakeBngApi()
        val result = ImportPppoeService(bng, PppoeRowImporter(FakeCustomerApi(), bng)).importPppoe(
            command(
                source = ImportSource.INLINE,
                rows = listOf(ImportRow("budi", "pwd", "misteri", null, disabled = false)),
                profilePlanId = mapOf("vip" to vipPlan),
            ),
        )

        assertThat(result.created).isZero()
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.rows.single().status).isEqualTo(ImportRowStatus.SKIPPED)
        assertThat(bng.provisioned).isEmpty()
    }

    @Test
    fun `defaultPlanId dipakai saat profil tak ada di peta`() {
        val bng = FakeBngApi()
        val result = ImportPppoeService(bng, PppoeRowImporter(FakeCustomerApi(), bng)).importPppoe(
            command(
                source = ImportSource.INLINE,
                rows = listOf(ImportRow("budi", "pwd", "misteri", null, disabled = false)),
                profilePlanId = mapOf("vip" to vipPlan),
                defaultPlanId = defaultPlan,
            ),
        )

        assertThat(result.created).isEqualTo(1)
        assertThat(bng.provisioned.single().planId).isEqualTo(defaultPlan)
    }

    @Test
    fun `akun disabled dilewati bila skipDisabled`() {
        val bng = FakeBngApi()
        val result = ImportPppoeService(bng, PppoeRowImporter(FakeCustomerApi(), bng)).importPppoe(
            command(
                source = ImportSource.INLINE,
                rows = listOf(
                    ImportRow("aktif", "p1", "vip", null, disabled = false),
                    ImportRow("mati", "p2", "vip", null, disabled = true),
                ),
                profilePlanId = mapOf("vip" to vipPlan),
                skipDisabled = true,
            ),
        )

        assertThat(result.created).isEqualTo(1)
        assertThat(result.rows.map { it.username }).containsExactly("aktif")
    }

    @Test
    fun `username sudah ada dianggap SKIPPED`() {
        val bng = FakeBngApi()
        val customer = FakeCustomerApi(existingCodes = setOf("budi"))
        val result = ImportPppoeService(bng, PppoeRowImporter(customer, bng)).importPppoe(
            command(
                source = ImportSource.INLINE,
                rows = listOf(ImportRow("budi", "pwd", "vip", null, disabled = false)),
                profilePlanId = mapOf("vip" to vipPlan),
            ),
        )

        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.created).isZero()
        assertThat(bng.provisioned).isEmpty()
    }

    @Test
    fun `sumber NAS menarik baris dari BngApi`() {
        val bng = FakeBngApi(
            onNas = listOf(
                PppSecretRef("budi", "pwd-budi", "vip", "pppoe", "Budi", disabled = false),
                PppSecretRef("mati", "pwd", "vip", "pppoe", null, disabled = true),
            ),
        )
        val result = ImportPppoeService(bng, PppoeRowImporter(FakeCustomerApi(), bng)).importPppoe(
            command(source = ImportSource.NAS, rows = emptyList(), profilePlanId = mapOf("vip" to vipPlan)),
        )

        // Server menarik dari NAS; yang disabled dilewati (skipDisabled default).
        assertThat(result.created).isEqualTo(1)
        assertThat(bng.provisioned.single().username).isEqualTo("budi")
    }

    @Test
    fun `onlyNames membatasi baris yang diproses`() {
        val bng = FakeBngApi()
        val result = ImportPppoeService(bng, PppoeRowImporter(FakeCustomerApi(), bng)).importPppoe(
            command(
                source = ImportSource.INLINE,
                rows = listOf(
                    ImportRow("budi", "p1", "vip", null, disabled = false),
                    ImportRow("siti", "p2", "vip", null, disabled = false),
                ),
                profilePlanId = mapOf("vip" to vipPlan),
                onlyNames = setOf("siti"),
            ),
        )

        assertThat(result.created).isEqualTo(1)
        assertThat(bng.provisioned.single().username).isEqualTo("siti")
    }

    // ---- Fixture & fake ----

    @Suppress("LongParameterList")
    private fun command(
        source: ImportSource,
        rows: List<ImportRow>,
        profilePlanId: Map<String, UUID>,
        defaultPlanId: UUID? = null,
        skipDisabled: Boolean = true,
        onlyNames: Set<String>? = null,
    ) = ImportPppoeCommand(
        nasId = nasId,
        source = source,
        rows = rows,
        profilePlanId = profilePlanId,
        defaultPlanId = defaultPlanId,
        skipDisabled = skipDisabled,
        onlyNames = onlyNames,
        areaId = null,
        defaultAddress = null,
        defaultLocation = null,
    )

    private class FakeBngApi(
        private val onNas: List<PppSecretRef> = emptyList(),
    ) : BngApi {
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
    }

    private class FakeCustomerApi(
        private val existingCodes: Set<String> = emptySet(),
    ) : CustomerApi {
        val registered = mutableListOf<RegisterCustomerCommand>()
        var activatedCount = 0

        override fun registerCustomer(command: RegisterCustomerCommand): UUID {
            if (command.code in existingCodes) throw ConflictException("Pelanggan '${command.code}' sudah ada")
            registered += command
            return UuidV7.generate()
        }

        override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: BigDecimal?): UUID =
            UuidV7.generate()

        override fun activateForInstallation(subscriptionId: UUID) {
            activatedCount++
        }

        override fun findCustomer(id: UUID) = throw UnsupportedOperationException()
        override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
        override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
        override fun findSubscriptionsByCustomer(customerId: UUID) = throw UnsupportedOperationException()
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
    }
}
