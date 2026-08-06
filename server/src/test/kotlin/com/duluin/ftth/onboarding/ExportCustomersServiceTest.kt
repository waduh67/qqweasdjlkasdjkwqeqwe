package com.duluin.ftth.onboarding

import com.duluin.ftth.bng.AccessExportRef
import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerExportRow
import com.duluin.ftth.onboarding.application.service.ExportCustomersService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Menguji orkestrasi ekspor CSV pelanggan dengan fake murni: memadukan akun jaringan (anchor
 * username) dengan snapshot langganan + biodata pemiliknya menurut subscriptionId. Menegakkan:
 * tipe koneksi jadi huruf kecil, tanggal aktivasi jadi tanggal pasang (UTC), nama BRAS jadi
 * router_name, Framed-IP akun Static/DHCP diteruskan, koordinat terurai lat/long; akun yang
 * langganannya tak ter-resolusi dilewati; tanpa akun → keluaran kosong. Password TAK pernah muncul
 * (tak ada di model baris ekspor).
 */
class ExportCustomersServiceTest {

    @Test
    fun `merakit baris ekspor dari akun + langganan + biodata`() {
        val subId = UuidV7.generate()
        val custId = UuidV7.generate()
        val bng = FakeBngApi(
            listOf(AccessExportRef("joko", "PPPOE", subId, custId, nasName = "BRAS-01")),
        )
        val customer = FakeCustomerApi(
            listOf(
                CustomerExportRow(
                    subscriptionId = subId,
                    customerId = custId,
                    name = "Joko Susilo",
                    phone = "0812",
                    email = "joko@mail.test",
                    address = "Jl. Melati 3",
                    idCardNumber = "3201xxxx",
                    location = Coordinate(longitude = 106.8, latitude = -6.2),
                    packageName = "Home 20",
                    activatedAt = LocalDate.of(2024, 3, 10).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    billingDayOfMonth = 10,
                ),
            ),
        )

        val lines = ExportCustomersService(customer, bng).exportCustomers()

        val line = lines.single()
        assertThat(line.mikrotikUsername).isEqualTo("joko")
        assertThat(line.connectionType).isEqualTo("pppoe") // huruf kecil untuk round-trip
        assertThat(line.name).isEqualTo("Joko Susilo")
        assertThat(line.email).isEqualTo("joko@mail.test")
        assertThat(line.address).isEqualTo("Jl. Melati 3")
        assertThat(line.idCardNumber).isEqualTo("3201xxxx")
        assertThat(line.packageName).isEqualTo("Home 20")
        assertThat(line.routerName).isEqualTo("BRAS-01")
        assertThat(line.installationDate).isEqualTo(LocalDate.of(2024, 3, 10))
        assertThat(line.nextBillingDay).isEqualTo(10)
        assertThat(line.latitude).isEqualTo(-6.2)
        assertThat(line.longitude).isEqualTo(106.8)
    }

    @Test
    fun `akun static mengekspor tipe dan framed_ip`() {
        val subId = UuidV7.generate()
        val custId = UuidV7.generate()
        val bng = FakeBngApi(
            listOf(AccessExportRef("AA:BB:CC:DD:EE:FF", "STATIC", subId, custId, nasName = "BRAS-01", framedIp = "100.64.0.10")),
        )
        val customer = FakeCustomerApi(
            listOf(
                CustomerExportRow(
                    subscriptionId = subId,
                    customerId = custId,
                    name = "Pelanggan Static",
                    phone = null,
                    email = null,
                    address = "Jl. Tetap",
                    idCardNumber = null,
                    location = Coordinate(0.0, 0.0),
                    packageName = "Home 20",
                    activatedAt = null,
                    billingDayOfMonth = null,
                ),
            ),
        )

        val line = ExportCustomersService(customer, bng).exportCustomers().single()
        assertThat(line.connectionType).isEqualTo("static")
        assertThat(line.mikrotikUsername).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(line.framedIp).isEqualTo("100.64.0.10")
    }

    @Test
    fun `akun tanpa langganan ter-resolusi dilewati`() {
        val bng = FakeBngApi(
            listOf(AccessExportRef("hantu", "PPPOE", UuidV7.generate(), UuidV7.generate(), nasName = null)),
        )
        // findExportRows mengembalikan kosong → tak ada langganan cocok.
        val lines = ExportCustomersService(FakeCustomerApi(emptyList()), bng).exportCustomers()

        assertThat(lines).isEmpty()
    }

    @Test
    fun `tanpa akun menghasilkan keluaran kosong`() {
        val lines = ExportCustomersService(FakeCustomerApi(emptyList()), FakeBngApi(emptyList())).exportCustomers()

        assertThat(lines).isEmpty()
    }

    @Test
    fun `langganan tanpa tanggal aktivasi memberi installation_date null`() {
        val subId = UuidV7.generate()
        val custId = UuidV7.generate()
        val bng = FakeBngApi(listOf(AccessExportRef("ani", "PPPOE", subId, custId, nasName = null)))
        val customer = FakeCustomerApi(
            listOf(
                CustomerExportRow(
                    subscriptionId = subId,
                    customerId = custId,
                    name = "Ani",
                    phone = null,
                    email = null,
                    address = "Jl. A",
                    idCardNumber = null,
                    location = Coordinate(0.0, 0.0),
                    packageName = "Home 20",
                    activatedAt = null, // belum aktif
                    billingDayOfMonth = null,
                ),
            ),
        )

        val line = ExportCustomersService(customer, bng).exportCustomers().single()
        assertThat(line.installationDate).isNull()
        assertThat(line.nextBillingDay).isNull()
        assertThat(line.routerName).isNull()
    }

    // ---- Fake ----

    private class FakeBngApi(private val accesses: List<AccessExportRef>) : BngApi {
        override fun exportAccesses(): List<AccessExportRef> = accesses

        override fun findSubscriberSession(customerId: UUID) = throw UnsupportedOperationException()
        override fun provisionAccess(command: com.duluin.ftth.bng.ProvisionAccessSpec) = throw UnsupportedOperationException()
        override fun resolveNasForArea(areaId: UUID) = throw UnsupportedOperationException()
        override fun resolveNasByName(name: String) = throw UnsupportedOperationException()
        override fun findAccessByUsername(username: String) = throw UnsupportedOperationException()
        override fun updateAccessFromImport(accessId: UUID, planId: UUID, nasId: UUID?, secret: String?) =
            throw UnsupportedOperationException()
        override fun fetchPppSecretsFromNas(nasId: UUID) = throw UnsupportedOperationException()
        override fun activeSubscriberLiveness() = throw UnsupportedOperationException()
    }

    private class FakeCustomerApi(private val rows: List<CustomerExportRow>) : CustomerApi {
        override fun findExportRows(subscriptionIds: Set<UUID>): List<CustomerExportRow> =
            rows.filter { it.subscriptionId in subscriptionIds }

        override fun registerCustomer(command: com.duluin.ftth.customer.RegisterCustomerCommand) =
            throw UnsupportedOperationException()
        override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: java.math.BigDecimal?) =
            throw UnsupportedOperationException()
        override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: Instant?, billingDayOfMonth: Int?) =
            throw UnsupportedOperationException()
        override fun updateCustomerBiodata(command: com.duluin.ftth.customer.UpdateCustomerBiodataCommand) =
            throw UnsupportedOperationException()
        override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) =
            throw UnsupportedOperationException()
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
    }
}
