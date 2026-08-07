package com.duluin.ftth

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
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
import java.time.Instant
import java.util.UUID

/**
 * Uji tren trafik di DB nyata ([AccountingRecordRepository.trafficSince]) — jalur baca yang
 * memberi grafik bandwidth per akun. Menegaskan dua hal: (1) laju Mbps dihitung dari selisih
 * penghitung kumulatif antar-ember dan penghitung yang MUNDUR (sesi baru) dijadikan null
 * alih-alih laju negatif palsu; (2) `time_bucket` meringkas cuplikan mentah sehingga rentang
 * padat tetap sedikit titik.
 *
 * Konvensi arah konsisten seluruh lapisan: `out` = arah pelanggan (Down/unduh), `in` = Up
 * (unggah). Seed + baca dibungkus satu transaksi di dalam [TenantContext.runAs] agar GUC
 * `app.tenant_id` terpasang (RLS aktif) dan tulisan langsung terbaca.
 */
@SpringBootTest
@ActiveProfiles("test")
class BngTrafficIT {

    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var accounting: AccountingRecordRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun point(tenantId: UUID, accessId: UUID, at: Instant, inO: Long, outO: Long) =
        AccountingRecordPoint(
            time = at,
            tenantId = tenantId,
            subscriberAccessId = accessId,
            nasId = null,
            inOctets = inO,
            outOctets = outO,
            uptimeSeconds = null,
        )

    @Test
    fun `trafficSince menghitung Mbps antar-ember dan memutus laju saat counter reset`() {
        val tenantId = tenantApi.ensureTenant("traffic-rate-${uniq()}", "Traffic Rate Co").id
        val access = UuidV7.generate()
        val t0 = Instant.parse("2026-07-01T00:00:00Z")

        val samples = TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                accounting.saveAll(
                    listOf(
                        // Baseline.
                        point(tenantId, access, t0, inO = 0, outO = 0),
                        // +60 dtk: out +60_000_000 octet → 8 Mbps Down; in +15_000_000 → 2 Mbps Up.
                        point(tenantId, access, t0.plusSeconds(60), inO = 15_000_000, outO = 60_000_000),
                        // +120 dtk: counter MUNDUR (sesi baru) → laju null, bukan negatif.
                        point(tenantId, access, t0.plusSeconds(120), inO = 1_000, outO = 1_000),
                    ),
                )
                // Ember 30 dtk → tiap cuplikan jadi ember sendiri (selang 60 dtk).
                accounting.trafficSince(access, t0, bucketSeconds = 30)
            }!!
        }

        assertThat(samples).hasSize(3)
        // Titik pertama tak punya pembanding → null.
        assertThat(samples[0].downMbps).isNull()
        assertThat(samples[0].upMbps).isNull()
        // Titik kedua: laju terhitung dari selisih antar-ember.
        assertThat(samples[1].downMbps).isEqualTo(8.0)
        assertThat(samples[1].upMbps).isEqualTo(2.0)
        // Titik ketiga: counter reset → null (bukan lonjakan/negatif palsu).
        assertThat(samples[2].downMbps).isNull()
        assertThat(samples[2].upMbps).isNull()
    }

    @Test
    fun `trafficSince meringkas cuplikan padat lewat ember lebar`() {
        val tenantId = tenantApi.ensureTenant("traffic-bucket-${uniq()}", "Traffic Bucket Co").id
        val access = UuidV7.generate()
        val t0 = Instant.parse("2026-07-01T00:00:00Z")

        val samples = TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                // 10 cuplikan tiap 60 dtk (rentang 9 mnt), penghitung naik terus.
                accounting.saveAll(
                    (0L until 10L).map { i ->
                        point(tenantId, access, t0.plusSeconds(i * 60), inO = i * 1_000_000, outO = i * 2_000_000)
                    },
                )
                // Ember 300 dtk (5 mnt) → 10 cuplikan meringkas jadi 2 ember (0–5 mnt, 5–10 mnt).
                accounting.trafficSince(access, t0, bucketSeconds = 300)
            }!!
        }

        // Bukti bucketing: jauh lebih sedikit titik daripada cuplikan mentah.
        assertThat(samples).hasSize(2)
    }
}
