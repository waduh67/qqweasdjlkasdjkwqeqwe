package com.duluin.ftth.onboarding

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ImportedAccessRef
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.ProvisionedAccessRef
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.UpdateCustomerBiodataCommand
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportRow
import com.duluin.ftth.onboarding.application.port.inbound.CustomerImportStatus
import com.duluin.ftth.onboarding.application.port.inbound.ImportCustomersCommand
import com.duluin.ftth.onboarding.application.service.CustomerRowImporter
import com.duluin.ftth.onboarding.application.service.ImportCustomersService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Menguji orkestrasi impor CSV pelanggan dengan fake murni (tanpa Spring/DB): upsert menurut
 * `mikrotik_username`. Menegakkan: baris baru → buat pelanggan+langganan+akun aktif dengan paket
 * dari nama & BRAS dari nama; `connection_type` dipetakan ke authType (pppoe/hotspot/dhcp/static),
 * Framed-IP diteruskan; username sudah ada → update parsial (paket TAK berubah, kolom kosong
 * dipertahankan, password kosong diteruskan apa adanya untuk dipertahankan bng); tanggal pasang jadi
 * aktivasi; `next_billing` di-clamp ≤28; baris tak layak (username kosong → dilewati; tipe tak
 * dikenal / paket / router tak ditemukan → gagal) tanpa menyeret batch.
 */
class ImportCustomersServiceTest {

    private val homePlan = PlanCommercialRef(
        planId = UuidV7.generate(),
        packageName = "Home 20",
        monthlyFee = BigDecimal("150000.00"),
        bandwidthMbps = 20,
        active = true,
        prorateOnActivation = null,
        billingDayOfMonth = 1,
        dueDays = null,
        graceDays = null,
        autoIsolir = null,
    )
    private val brasId: UUID = UuidV7.generate()

    private fun newService(catalog: FakeCatalogApi, bng: FakeBngApi, customer: FakeCustomerApi) =
        ImportCustomersService(CustomerRowImporter(customer, catalog, bng))

    @Test
    fun `baris baru dibuat lengkap dan memprovisi RADIUS dengan paket & BRAS dari nama`() {
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val bng = FakeBngApi(routers = mapOf("bras-01" to brasId))
        val customer = FakeCustomerApi()
        val result = newService(catalog, bng, customer).importCustomers(
            ImportCustomersCommand(
                listOf(
                    row(
                        name = "Joko Susilo",
                        address = "Jl. Melati 3",
                        packageName = "Home 20",
                        username = "joko",
                        password = "rahasia",
                        routerName = "BRAS-01",
                        installationDate = LocalDate.of(2024, 1, 15),
                        nextBillingDay = 10,
                        latitude = -6.2,
                        longitude = 106.8,
                    ),
                ),
            ),
        )

        assertThat(result.created).isEqualTo(1)
        assertThat(result.updated).isZero()
        assertThat(result.failed).isZero()

        val registered = customer.registered.single()
        assertThat(registered.name).isEqualTo("Joko Susilo")
        assertThat(registered.code).isNull() // kode auto-generate
        assertThat(registered.location.longitude).isEqualTo(106.8)
        assertThat(registered.location.latitude).isEqualTo(-6.2)

        val provisioned = bng.provisioned.single()
        assertThat(provisioned.username).isEqualTo("joko")
        assertThat(provisioned.secret).isEqualTo("rahasia")
        assertThat(provisioned.planId).isEqualTo(homePlan.planId)
        assertThat(provisioned.nasId).isEqualTo(brasId)
        assertThat(provisioned.authType).isEqualTo("PPPOE")

        val activation = customer.activatedImported.single()
        assertThat(activation.activatedAt)
            .isEqualTo(LocalDate.of(2024, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant())
        assertThat(activation.billingDay).isEqualTo(10)
    }

    @Test
    fun `create tanpa password menyerahkan generate ke server`() {
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val bng = FakeBngApi(routers = mapOf("bras-01" to brasId))
        val customer = FakeCustomerApi()
        newService(catalog, bng, customer).importCustomers(
            ImportCustomersCommand(
                listOf(row(name = "Ani", address = "Jl. A", packageName = "Home 20", username = "ani", password = "  ", routerName = "BRAS-01")),
            ),
        )

        // Password kosong pada CREATE → null (server generate), bukan string kosong.
        assertThat(bng.provisioned.single().secret).isNull()
    }

    @Test
    fun `username sudah ada memicu update parsial tanpa mengganti paket`() {
        val existingPlan = UuidV7.generate()
        val existingNas = UuidV7.generate()
        val existing = ImportedAccessRef(
            accessId = UuidV7.generate(),
            subscriptionId = UuidV7.generate(),
            customerId = UuidV7.generate(),
            planId = existingPlan,
            nasId = existingNas,
            macBased = false,
        )
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val bng = FakeBngApi(accounts = mapOf("joko" to existing))
        val customer = FakeCustomerApi()
        val result = newService(catalog, bng, customer).importCustomers(
            ImportCustomersCommand(
                listOf(
                    row(
                        name = "Joko Baru",
                        address = "  ", // kosong → dipertahankan
                        packageName = "Home 20", // diabaikan pada update
                        username = "joko",
                        password = "", // kosong → dipertahankan (diteruskan apa adanya)
                        routerName = "", // kosong → BRAS lama dipertahankan
                        nextBillingDay = null,
                    ),
                ),
            ),
        )

        assertThat(result.updated).isEqualTo(1)
        assertThat(result.created).isZero()
        assertThat(bng.provisioned).isEmpty() // tak ada create

        val biodata = customer.biodataUpdates.single()
        assertThat(biodata.customerId).isEqualTo(existing.customerId)
        assertThat(biodata.name).isEqualTo("Joko Baru")
        assertThat(biodata.address).isNull() // kolom kosong dipertahankan
        assertThat(biodata.location).isNull()

        val updated = bng.accessUpdates.single()
        assertThat(updated.accessId).isEqualTo(existing.accessId)
        assertThat(updated.planId).isEqualTo(existingPlan) // paket TAK berubah
        assertThat(updated.nasId).isEqualTo(existingNas) // BRAS lama (router_name kosong)
        assertThat(updated.secret).isEqualTo("") // password kosong diteruskan; bng yang pertahankan

        assertThat(customer.billingOverrides).isEmpty() // next_billing kosong → tak disetel
    }

    @Test
    fun `update router_name mengganti BRAS lewat nama`() {
        val existing = ImportedAccessRef(
            accessId = UuidV7.generate(),
            subscriptionId = UuidV7.generate(),
            customerId = UuidV7.generate(),
            planId = UuidV7.generate(),
            nasId = UuidV7.generate(),
            macBased = false,
        )
        val newBras = UuidV7.generate()
        val bng = FakeBngApi(accounts = mapOf("joko" to existing), routers = mapOf("bras-02" to newBras))
        val customer = FakeCustomerApi()
        newService(FakeCatalogApi(), bng, customer).importCustomers(
            ImportCustomersCommand(listOf(row(username = "joko", routerName = "BRAS-02"))),
        )

        assertThat(bng.accessUpdates.single().nasId).isEqualTo(newBras)
    }

    @Test
    fun `next_billing di atas 28 di-clamp ke 28`() {
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val bng = FakeBngApi(routers = mapOf("bras-01" to brasId))
        val customer = FakeCustomerApi()
        newService(catalog, bng, customer).importCustomers(
            ImportCustomersCommand(
                listOf(row(name = "Budi", address = "Jl. B", packageName = "Home 20", username = "budi", routerName = "BRAS-01", nextBillingDay = 31)),
            ),
        )

        assertThat(customer.activatedImported.single().billingDay).isEqualTo(28)
    }

    @Test
    fun `update next_billing di atas 28 juga di-clamp`() {
        val existing = ImportedAccessRef(
            accessId = UuidV7.generate(),
            subscriptionId = UuidV7.generate(),
            customerId = UuidV7.generate(),
            planId = UuidV7.generate(),
            nasId = null,
            macBased = false,
        )
        val bng = FakeBngApi(accounts = mapOf("joko" to existing))
        val customer = FakeCustomerApi()
        newService(FakeCatalogApi(), bng, customer).importCustomers(
            ImportCustomersCommand(listOf(row(username = "joko", nextBillingDay = 30))),
        )

        assertThat(customer.billingOverrides.single()).isEqualTo(existing.subscriptionId to 28)
    }

    @Test
    fun `mikrotik_username kosong dilewati`() {
        val result = newService(FakeCatalogApi(), FakeBngApi(), FakeCustomerApi()).importCustomers(
            ImportCustomersCommand(listOf(row(name = "Tanpa Username", username = "  "))),
        )

        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.rows.single().status).isEqualTo(CustomerImportStatus.SKIPPED)
    }

    @Test
    fun `tipe hotspot memprovisi akun login HOTSPOT dengan password`() {
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val bng = FakeBngApi(routers = mapOf("bras-01" to brasId))
        val customer = FakeCustomerApi()
        newService(catalog, bng, customer).importCustomers(
            ImportCustomersCommand(
                listOf(
                    row(
                        name = "Wifi Warkop", address = "Jl. Kopi", packageName = "Home 20",
                        username = "warkop", password = "kopi123", routerName = "BRAS-01",
                        connectionType = "hotspot",
                    ),
                ),
            ),
        )

        val provisioned = bng.provisioned.single()
        assertThat(provisioned.authType).isEqualTo("HOTSPOT")
        assertThat(provisioned.secret).isEqualTo("kopi123")
        assertThat(provisioned.framedIp).isNull()
    }

    @Test
    fun `tipe dhcp memprovisi akun berbasis MAC`() {
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val bng = FakeBngApi(routers = mapOf("bras-01" to brasId))
        val customer = FakeCustomerApi()
        newService(catalog, bng, customer).importCustomers(
            ImportCustomersCommand(
                listOf(
                    row(
                        name = "Pelanggan DHCP", address = "Jl. Dinamis", packageName = "Home 20",
                        username = "AA:BB:CC:DD:EE:FF", routerName = "BRAS-01",
                        connectionType = "dhcp",
                    ),
                ),
            ),
        )

        val provisioned = bng.provisioned.single()
        assertThat(provisioned.authType).isEqualTo("DHCP")
        assertThat(provisioned.username).isEqualTo("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `tipe static meneruskan reservasi framed_ip`() {
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val bng = FakeBngApi(routers = mapOf("bras-01" to brasId))
        val customer = FakeCustomerApi()
        newService(catalog, bng, customer).importCustomers(
            ImportCustomersCommand(
                listOf(
                    row(
                        name = "Pelanggan Static", address = "Jl. Tetap", packageName = "Home 20",
                        username = "11:22:33:44:55:66", routerName = "BRAS-01",
                        connectionType = "static", framedIp = "100.64.0.10",
                    ),
                ),
            ),
        )

        val provisioned = bng.provisioned.single()
        assertThat(provisioned.authType).isEqualTo("STATIC")
        assertThat(provisioned.framedIp).isEqualTo("100.64.0.10")
    }

    @Test
    fun `tipe koneksi tak dikenal menggagalkan baris`() {
        val result = newService(FakeCatalogApi(), FakeBngApi(), FakeCustomerApi()).importCustomers(
            ImportCustomersCommand(listOf(row(username = "user-x", connectionType = "wireless"))),
        )

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.rows.single().status).isEqualTo(CustomerImportStatus.FAILED)
        assertThat(result.rows.single().message).contains("tak dikenal")
    }

    @Test
    fun `paket tak dikenal pada create menggagalkan baris`() {
        val result = newService(FakeCatalogApi(), FakeBngApi(), FakeCustomerApi()).importCustomers(
            ImportCustomersCommand(
                listOf(row(name = "Cici", address = "Jl. C", packageName = "Paket Hantu", username = "cici")),
            ),
        )

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.rows.single().status).isEqualTo(CustomerImportStatus.FAILED)
        assertThat(result.rows.single().message).contains("Paket 'Paket Hantu'")
    }

    @Test
    fun `router tak dikenal menggagalkan baris`() {
        val catalog = FakeCatalogApi(mapOf("home 20" to homePlan))
        val result = newService(catalog, FakeBngApi(), FakeCustomerApi()).importCustomers(
            ImportCustomersCommand(
                listOf(row(name = "Dedi", address = "Jl. D", packageName = "Home 20", username = "dedi", routerName = "BRAS-HANTU")),
            ),
        )

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.rows.single().message).contains("Router 'BRAS-HANTU'")
    }

    // ---- Fixture & fake ----

    @Suppress("LongParameterList")
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
    }

    private class FakeBngApi(
        private val accounts: Map<String, ImportedAccessRef> = emptyMap(),
        private val routers: Map<String, UUID> = emptyMap(),
    ) : BngApi {
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

        override fun registerCustomer(command: RegisterCustomerCommand): UUID {
            registered += command
            return UuidV7.generate()
        }

        override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: BigDecimal?): UUID =
            UuidV7.generate()

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
        override fun subscriptionDimensions(subscriptionIds: Set<java.util.UUID>) = throw UnsupportedOperationException()
        override fun churnReport(from: java.time.LocalDate, to: java.time.LocalDate) = throw UnsupportedOperationException()
        override fun findExportRows(subscriptionIds: Set<UUID>) = throw UnsupportedOperationException()
    }
}
