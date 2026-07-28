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
 * Uji agregasi pemakaian FUP di DB nyata ([AccountingRecordRepository.usageSince]) — inti
 * mesin FUP: menjumlah octet terpakai per akun sejak awal siklus, SADAR-RESET (counter
 * yang mundur karena sesi baru dihitung penuh, bukan jadi selisih negatif) dan mengabaikan
 * byte sebelum awal siklus (titik pertama jadi baseline).
 *
 * Seed + baca dibungkus satu transaksi yang dimulai DI DALAM [TenantContext.runAs] agar GUC
 * `app.tenant_id` terpasang pada connection (RLS aktif) dan tulisan langsung terbaca.
 */
@SpringBootTest
@ActiveProfiles("test")
class BngFupUsageIT {

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
    fun `usageSince menjumlah delta sadar-reset dan mengabaikan byte sebelum awal siklus`() {
        val tenantId = tenantApi.ensureTenant("fup-usage-${uniq()}", "FUP Usage Co").id
        val accessA = UuidV7.generate()
        val accessB = UuidV7.generate()
        val t0 = Instant.parse("2026-07-01T00:00:00Z")

        val usage = TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                accounting.saveAll(
                    listOf(
                        // Akun A: tumbuh 100→300 (in), 1000→1500 (out), lalu counter RESET ke 50/100.
                        point(tenantId, accessA, t0, inO = 100, outO = 1_000),
                        point(tenantId, accessA, t0.plusSeconds(60), inO = 300, outO = 1_500),
                        point(tenantId, accessA, t0.plusSeconds(120), inO = 50, outO = 100),
                        // Akun B: satu cuplikan saja → jadi baseline murni, kontribusi 0.
                        point(tenantId, accessB, t0, inO = 999, outO = 999),
                    ),
                )
                accounting.usageSince(listOf(accessA, accessB), t0)
            }!!
        }

        // A: baseline(0) + delta(200+500) + segmen pasca-reset(50+100) = 850
        assertThat(usage[accessA]).isEqualTo(850L)
        // B: hanya baseline → total 0.
        assertThat(usage[accessB] ?: 0L).isEqualTo(0L)
    }

    @Test
    fun `usageSince hanya menghitung sejak awal siklus`() {
        val tenantId = tenantApi.ensureTenant("fup-since-${uniq()}", "FUP Since Co").id
        val access = UuidV7.generate()
        val t0 = Instant.parse("2026-07-01T00:00:00Z")

        val usage = TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                accounting.saveAll(
                    listOf(
                        point(tenantId, access, t0, inO = 100, outO = 100),
                        point(tenantId, access, t0.plusSeconds(60), inO = 300, outO = 200),
                        point(tenantId, access, t0.plusSeconds(120), inO = 900, outO = 400),
                    ),
                )
                // Mulai dari cuplikan kedua: titik itu jadi baseline; hanya delta ke titik ketiga terhitung.
                accounting.usageSince(listOf(access), t0.plusSeconds(60))
            }!!
        }

        // delta in 900-300=600, out 400-200=200 → 800
        assertThat(usage[access]).isEqualTo(800L)
    }
}
