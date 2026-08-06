package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.inbound.SaveNasCommand
import com.duluin.ftth.bng.application.port.outbound.NasAreaCoverageRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.PppSecret
import com.duluin.ftth.bng.application.port.outbound.RadiusClientRegistryPort
import com.duluin.ftth.bng.application.port.outbound.RouterOsPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.NasService
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.NasVendor
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Menguji penyambungan [NasService] ke registri klien RADIUS (self-service dynamic
 * clients) dengan fake murni (tanpa Spring/DB): daftar router lewat CRUD BRAS → baris
 * `nas` platform terpasang/tercabut, dengan `nasname`=alamat, `shortname`=kode tenant
 * (slug), `secret`=secret CoA. Semua digerbangi [isConfigured] (dev/test tanpa radius-db
 * dilewati) dan menangani perubahan alamat serta penonaktifan sebagai pencabutan.
 */
class NasServiceRadiusClientTest {

    private val tenantId: UUID = UuidV7.generate()
    private val slug = "acme"

    @Test
    fun `create mendaftarkan klien RADIUS dengan nasname alamat shortname slug dan secret`() {
        val registry = FakeClientRegistry(configured = true)
        service(registry).create(command(address = "203.0.113.9", coaSecret = "s3cr3t"))

        assertThat(registry.registered).containsExactly(Registered("203.0.113.9", slug, "s3cr3t"))
        assertThat(registry.deregistered).isEmpty()
    }

    @Test
    fun `create tanpa registry terkonfigurasi tak menyentuh registri`() {
        val registry = FakeClientRegistry(configured = false)
        service(registry).create(command(address = "203.0.113.9", coaSecret = "s3cr3t"))

        assertThat(registry.registered).isEmpty()
        assertThat(registry.deregistered).isEmpty()
    }

    @Test
    fun `create tanpa alamat tak mendaftarkan klien`() {
        val registry = FakeClientRegistry(configured = true)
        service(registry).create(command(address = null, coaSecret = "s3cr3t"))

        assertThat(registry.registered).isEmpty()
    }

    @Test
    fun `create tanpa secret CoA tak mendaftarkan klien`() {
        val registry = FakeClientRegistry(configured = true)
        service(registry).create(command(address = "203.0.113.9", coaSecret = null))

        assertThat(registry.registered).isEmpty()
    }

    @Test
    fun `update yang mengubah alamat mencabut alamat lama lalu mendaftar yang baru`() {
        val registry = FakeClientRegistry(configured = true)
        val svc = service(registry)
        val created = svc.create(command(address = "203.0.113.9", coaSecret = "s3cr3t"))
        registry.reset()

        svc.update(created.id, command(address = "198.51.100.7", coaSecret = null))

        assertThat(registry.deregistered).containsExactly("203.0.113.9")
        assertThat(registry.registered).containsExactly(Registered("198.51.100.7", slug, "s3cr3t"))
    }

    @Test
    fun `update yang menonaktifkan BRAS mencabut klien`() {
        val registry = FakeClientRegistry(configured = true)
        val svc = service(registry)
        val created = svc.create(command(address = "203.0.113.9", coaSecret = "s3cr3t"))
        registry.reset()

        svc.update(created.id, command(address = "203.0.113.9", coaSecret = null, enabled = false))

        assertThat(registry.registered).isEmpty()
        assertThat(registry.deregistered).containsExactly("203.0.113.9")
    }

    @Test
    fun `delete mencabut klien RADIUS`() {
        val registry = FakeClientRegistry(configured = true)
        val svc = service(registry)
        val created = svc.create(command(address = "203.0.113.9", coaSecret = "s3cr3t"))
        registry.reset()

        svc.delete(created.id)

        assertThat(registry.deregistered).containsExactly("203.0.113.9")
    }

    // ---- Cakupan area (auto-pilih BRAS dari area) ----

    @Test
    fun `create dengan areaIds memasang cakupan area dan mengembalikannya di view`() {
        val coverage = FakeCoverageRepo()
        val a1 = UuidV7.generate()
        val a2 = UuidV7.generate()
        val view = service(FakeClientRegistry(configured = false), coverage)
            .create(command(address = null, coaSecret = null, areaIds = listOf(a1, a2)))

        assertThat(view.areaIds).containsExactlyInAnyOrder(a1, a2)
        assertThat(coverage.findNasIdByAreaId(a1)).isEqualTo(view.id)
        assertThat(coverage.findNasIdByAreaId(a2)).isEqualTo(view.id)
    }

    @Test
    fun `update mengganti TOTAL cakupan area`() {
        val coverage = FakeCoverageRepo()
        val svc = service(FakeClientRegistry(configured = false), coverage)
        val a1 = UuidV7.generate()
        val a2 = UuidV7.generate()
        val created = svc.create(command(address = null, coaSecret = null, areaIds = listOf(a1)))

        val updated = svc.update(created.id, command(address = null, coaSecret = null, areaIds = listOf(a2)))

        assertThat(updated.areaIds).containsExactly(a2)
        assertThat(coverage.findNasIdByAreaId(a1)).isNull()
        assertThat(coverage.findNasIdByAreaId(a2)).isEqualTo(created.id)
    }

    @Test
    fun `area yang sudah dinaungi BRAS lain ditolak`() {
        val coverage = FakeCoverageRepo()
        val a1 = UuidV7.generate()
        // Area a1 sudah dimiliki BRAS lain.
        coverage.replaceCoverage(UuidV7.generate(), listOf(a1))

        assertThatThrownBy {
            service(FakeClientRegistry(configured = false), coverage)
                .create(command(address = null, coaSecret = null, areaIds = listOf(a1)))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `delete melepas seluruh cakupan area`() {
        val coverage = FakeCoverageRepo()
        val svc = service(FakeClientRegistry(configured = false), coverage)
        val a1 = UuidV7.generate()
        val created = svc.create(command(address = null, coaSecret = null, areaIds = listOf(a1)))

        svc.delete(created.id)

        assertThat(coverage.findNasIdByAreaId(a1)).isNull()
        assertThat(coverage.findAreaIdsByNasId(created.id)).isEmpty()
    }

    // ---- Fixture & fake ----

    private fun service(
        registry: FakeClientRegistry,
        coverage: FakeCoverageRepo = FakeCoverageRepo(),
    ): NasService {
        val currentUser = object : CurrentUserProvider {
            override fun currentOrNull() = AuthenticatedUser(
                userId = UuidV7.generate(), tenantId = tenantId, email = "op@acme.id",
                name = "Operator", platformAdmin = false, permissions = emptySet(), areaIds = emptySet(),
            )
        }
        val auditor = AuditRecorder(ApplicationEventPublisher { }, currentUser)
        return NasService(
            FakeNasRepo(), FakeNoSubscriberRepo(), currentUser, auditor, registry, FakeTenantApi(),
            RadiusProperties(), coverage, FakeRouterOs(),
        )
    }

    private fun command(
        address: String?,
        coaSecret: String?,
        enabled: Boolean = true,
        areaIds: List<UUID> = emptyList(),
    ) = SaveNasCommand(
        name = "BRAS Utama",
        vendor = NasVendor.MIKROTIK,
        address = address,
        nasIdentifier = null,
        coaSecret = coaSecret,
        collectorId = null,
        enabled = enabled,
        areaIds = areaIds,
    )

    private class FakeRouterOs : RouterOsPort {
        override fun fetchPppSecrets(nas: Nas): List<PppSecret> = emptyList()
    }

    private data class Registered(val nasname: String, val shortname: String, val secret: String)

    private class FakeClientRegistry(private val configured: Boolean) : RadiusClientRegistryPort {
        val registered = mutableListOf<Registered>()
        val deregistered = mutableListOf<String>()
        override fun isConfigured(): Boolean = configured
        override fun register(tenantId: UUID, nasname: String, shortname: String, secret: String) {
            registered += Registered(nasname, shortname, secret)
        }

        override fun deregister(tenantId: UUID, nasname: String) {
            deregistered += nasname
        }

        fun reset() {
            registered.clear()
            deregistered.clear()
        }
    }

    private inner class FakeTenantApi : TenantApi {
        override fun findById(id: UUID): TenantRef? = TenantRef(id, slug, "Acme", TenantStatus.ACTIVE)
        override fun findBySlug(slug: String): TenantRef? = throw UnsupportedOperationException()
        override fun requireById(id: UUID): TenantRef = throw UnsupportedOperationException()
        override fun platformTenantId(): UUID = throw UnsupportedOperationException()
        override fun findActiveTenantIds(): List<UUID> = throw UnsupportedOperationException()
        override fun ensureTenant(slug: String, name: String): TenantRef = throw UnsupportedOperationException()
        override fun suspend(id: UUID): TenantRef = throw UnsupportedOperationException()
        override fun activate(id: UUID): TenantRef = throw UnsupportedOperationException()
    }

    private class FakeNasRepo : NasRepository {
        private val store = mutableMapOf<UUID, Nas>()
        override fun save(nas: Nas): Nas = nas.also { store[it.id] = it }
        override fun findById(id: UUID): Nas? = store[id]
        override fun findAll(): List<Nas> = store.values.toList()
        override fun existsByName(name: String): Boolean = false
        override fun findByNameIgnoreCase(name: String) = throw UnsupportedOperationException()
        override fun deleteById(id: UUID) {
            store.remove(id)
        }
    }

    private class FakeCoverageRepo : NasAreaCoverageRepository {
        private val byNas = mutableMapOf<UUID, MutableList<UUID>>()
        override fun findAreaIdsByNasId(nasId: UUID): List<UUID> = byNas[nasId].orEmpty()
        override fun findAreaIdsByNasIds(nasIds: Collection<UUID>): Map<UUID, List<UUID>> =
            nasIds.associateWith { byNas[it].orEmpty() }.filterValues { it.isNotEmpty() }
        override fun findNasIdByAreaId(areaId: UUID): UUID? =
            byNas.entries.firstOrNull { areaId in it.value }?.key
        override fun replaceCoverage(nasId: UUID, areaIds: Collection<UUID>) {
            byNas[nasId] = areaIds.toMutableList()
        }
        override fun deleteByNasId(nasId: UUID) {
            byNas.remove(nasId)
        }
    }

    private class FakeNoSubscriberRepo : SubscriberAccessRepository {
        override fun countByNasId(nasId: UUID): Long = 0
        override fun save(access: SubscriberAccess): SubscriberAccess = throw UnsupportedOperationException()
        override fun findActiveOnNas(): List<SubscriberAccess> = throw UnsupportedOperationException()
        override fun findById(id: UUID): SubscriberAccess? = throw UnsupportedOperationException()
        override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = throw UnsupportedOperationException()
        override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> =
            throw UnsupportedOperationException()

        override fun findByUsername(username: String): SubscriberAccess? = throw UnsupportedOperationException()
        override fun findByNasId(nasId: UUID): List<SubscriberAccess> = throw UnsupportedOperationException()
        override fun findByPlanId(planId: UUID): List<SubscriberAccess> = throw UnsupportedOperationException()
        override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = throw UnsupportedOperationException()
        override fun deleteById(id: UUID): Unit = throw UnsupportedOperationException()
    }
}
