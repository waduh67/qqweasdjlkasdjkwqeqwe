package com.duluin.ftth

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.RadiusGroups
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Menegaskan PENJAGA URUTAN antar dua worker antrean terhadap DB nyata
 * ([BngActionRepository.findAccessIdsWithPendingProvisioning]).
 *
 * Isolir dan pemulihan sama-sama mengantre sepasang perintah — ganti grup dulu, baru putus —
 * tetapi yang mengerjakannya dua worker berbeda pada selang yang sama. Tanpa penjaga ini
 * pemutusan bisa mendahului pergantian grup: pelanggan terputus, CPE dial ulang dalam hitungan
 * detik, dan RADIUS masih menyambutnya dengan grup LAMA — pelanggan yang baru diisolir kembali
 * online penuh, atau yang baru membayar tetap terkurung di halaman tagihan.
 *
 * Diuji di sini (bukan dengan fake) karena isinya query JPQL: proyeksi kolom id + `DISTINCT` +
 * saringan `IN` tiga kolom sekaligus, yang hanya benar-benar terbukti lawan Postgres.
 *
 * Seed + baca dibungkus satu transaksi di dalam [TenantContext.runAs] agar GUC `app.tenant_id`
 * terpasang (RLS aktif) dan tulisan langsung terbaca — pola sama [BngProvisioningClaimSplitIT].
 */
@SpringBootTest
@ActiveProfiles("test")
class BngProvisioningOrderGuardIT {

    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var actions: BngActionRepository
    @Autowired private lateinit var accesses: SubscriberAccessRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `akun dengan PROVISION isolir tertunda tertahan, akun tanpanya lolos`() {
        val tenantId = tenantApi.ensureTenant("order-guard-${uniq()}", "Order Guard Co").id
        val nasId = UuidV7.generate()

        TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                val terisolir = seedAccess(tenantId)
                val biasa = seedAccess(tenantId)

                // Persis yang diantre isolir: pindah ke grup isolir, lalu putus sesinya.
                actions.save(
                    BngAction.provision(
                        tenantId, terisolir.id, nasId, terisolir.username, RadiusGroups.ISOLIR, null, null,
                    ),
                )
                actions.save(BngAction.disconnect(tenantId, terisolir.id, nasId, terisolir.username, null, null))
                // Reset Login biasa: putus tanpa pergantian grup — tak ada alasan menahannya.
                actions.save(BngAction.disconnect(tenantId, biasa.id, nasId, biasa.username, null, null))

                val menunggu = actions.findAccessIdsWithPendingProvisioning(listOf(terisolir.id, biasa.id))
                assertThat(menunggu).containsExactly(terisolir.id)
            }
        }
    }

    @Test
    fun `PROVISION yang sudah tuntas tak lagi menahan pemutusan`() {
        val tenantId = tenantApi.ensureTenant("order-guard-done-${uniq()}", "Order Guard Done Co").id
        val nasId = UuidV7.generate()

        TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                val access = seedAccess(tenantId)
                val provision = BngAction.provision(
                    tenantId, access.id, nasId, access.username, RadiusGroups.ISOLIR, null, null,
                )
                // Worker provisioning selesai menulis grup isolir ke radius-db → penahan lepas,
                // pemutusan boleh jalan pada putaran yang sama.
                provision.complete()
                actions.save(provision)

                assertThat(actions.findAccessIdsWithPendingProvisioning(listOf(access.id))).isEmpty()
            }
        }
    }

    @Test
    fun `daftar akun kosong tak menyentuh DB dan mengembalikan himpunan kosong`() {
        assertThat(actions.findAccessIdsWithPendingProvisioning(emptyList())).isEmpty()
    }

    private fun seedAccess(tenantId: UUID): SubscriberAccess = accesses.save(
        // nasId akun = null agar tak perlu baris `nas` sungguhan; perintahnya sendiri
        // membawa nasId lepas (tanpa FK), sama seperti BngProvisioningClaimSplitIT.
        SubscriberAccess.create(
            tenantId = tenantId,
            subscriptionId = UuidV7.generate(),
            customerId = UuidV7.generate(),
            username = "u${uniq()}",
            secret = "rahasia123",
            planId = UuidV7.generate(),
            nasId = null,
            status = AccessStatus.ACTIVE,
        ),
    )
}
