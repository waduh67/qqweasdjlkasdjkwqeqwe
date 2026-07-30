package com.duluin.ftth

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
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
 * Menegaskan PEMBELAHAN-KLAIM antara dua worker antrean [BngAction] terhadap DB nyata
 * (RADIUS-as-a-service): perintah KONTROL SESI ([BngActionType.SESSION_CONTROL]:
 * DISCONNECT/COA) hanya diklaim jalur dispatch collector ([BngActionRepository.findDispatchableByNasIds]),
 * sementara perintah jalur-DATA ([BngActionType.PROVISIONING]: PROVISION/DEPROVISION/
 * SYNC_GROUP) hanya diklaim worker server-side ([BngActionRepository.findServerProvisioningPending]).
 * Kedua himpunan DISJOIN → tak ada aksi dieksekusi dua kali. Kelima aksi sengaja berbagi
 * satu [nasId] agar pengecualian benar-benar berdasar TIPE, bukan rute.
 *
 * Seed + baca dibungkus satu transaksi di dalam [TenantContext.runAs] agar GUC
 * `app.tenant_id` terpasang (RLS aktif) dan tulisan langsung terbaca — pola sama [BngFupUsageIT].
 */
@SpringBootTest
@ActiveProfiles("test")
class BngProvisioningClaimSplitIT {

    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var actions: BngActionRepository
    @Autowired private lateinit var accesses: SubscriberAccessRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `dispatch collector mengklaim hanya kontrol sesi, worker server hanya provisioning`() {
        val tenantId = tenantApi.ensureTenant("claim-split-${uniq()}", "Claim Split Co").id
        val nasId = UuidV7.generate()

        TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                // Akun nyata dibutuhkan FK subscriber_access_id untuk perintah per-akun
                // (DISCONNECT/COA/PROVISION). nasId akun = null → lewati FK ke tabel nas.
                val access = accesses.save(
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

                // Kontrol sesi (collector).
                actions.save(BngAction.disconnect(tenantId, access.id, nasId, access.username, null, null))
                actions.save(BngAction.coa(tenantId, access.id, nasId, access.username, 50, 10, null, null))
                // Jalur-data (server).
                actions.save(BngAction.provision(tenantId, access.id, nasId, access.username, "plan:${access.planId}", null, null))
                actions.save(BngAction.deprovision(tenantId, nasId, access.username, null, null))
                actions.save(
                    BngAction.syncGroup(
                        tenantId, nasId, "plan:${access.planId}", "10M/50M",
                        simultaneousUse = 1, fupGroupname = null, fupRateLimit = null,
                        requestedBy = null, requestedByEmail = null,
                    ),
                )

                val dispatch = actions.findDispatchableByNasIds(listOf(nasId)).map { it.action }
                assertThat(dispatch).containsExactlyInAnyOrder(BngActionType.DISCONNECT, BngActionType.COA)

                val provisioning = actions.findServerProvisioningPending(100).map { it.action }
                assertThat(provisioning).containsExactlyInAnyOrder(
                    BngActionType.PROVISION, BngActionType.DEPROVISION, BngActionType.SYNC_GROUP,
                )
            }
        }
    }
}
