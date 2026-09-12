package com.duluin.ftth.order

import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Kontrak nomor pesanan `ORD-YYMM-NNNN`.
 *
 * Balapan sesungguhnya dijaga baris `order_number_counter` di Postgres
 * (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`, lihat `OrderNumberPersistenceAdapter`) dan
 * diregresi di `OrderIT`. Yang dikunci DI SINI adalah kontrak yang harus dipenuhi implementasi
 * mana pun: bentuk nomornya, cacahnya yang berurut per (tenant, periode), dan larangan nomor
 * kembar — supaya implementasi pengganti yang melanggarnya ketahuan tanpa perlu database.
 */
class OrderNumberTest {

    private val generator = InMemoryOrderNumberGenerator()
    private val tenant = UuidV7.generate()

    // WIB: periode dihitung di zona operator, bukan UTC. Pesanan pukul 08:00 tanggal 1 di Jakarta
    // masih 01:00 UTC hari yang sama, tapi pesanan pukul 07:00 tanggal 1 adalah 31 pukul 24:00 UTC
    // bulan sebelumnya — memakai UTC membuat nomor bulan baru bocor ke bulan lama.
    private val septemberWib = Instant.parse("2026-09-12T03:00:00Z")
    private val octoberWib = Instant.parse("2026-10-01T03:00:00Z")

    @Test
    fun `number follows ORD-YYMM-NNNN and counts up within the period`() {
        assertThat(generator.next(tenant, septemberWib)).isEqualTo("ORD-2609-0001")
        assertThat(generator.next(tenant, septemberWib)).isEqualTo("ORD-2609-0002")
        assertThat(generator.next(tenant, septemberWib)).isEqualTo("ORD-2609-0003")
    }

    @Test
    fun `the counter restarts every month`() {
        generator.next(tenant, septemberWib)
        generator.next(tenant, septemberWib)
        assertThat(generator.next(tenant, octoberWib)).isEqualTo("ORD-2610-0001")
        // Bulan lama tak ikut mundur: dua periode punya pencacah sendiri-sendiri.
        assertThat(generator.next(tenant, septemberWib)).isEqualTo("ORD-2609-0003")
    }

    @Test
    fun `two tenants never share a counter`() {
        val other = UuidV7.generate()
        assertThat(generator.next(tenant, septemberWib)).isEqualTo("ORD-2609-0001")
        assertThat(generator.next(other, septemberWib)).isEqualTo("ORD-2609-0001")
        // Nomor boleh sama persis antar tenant: keunikannya dijaga UNIQUE (tenant_id, order_number),
        // bukan keunikan global. Operator tenant lain tak pernah melihat nomor tetangganya.
        assertThat(generator.next(tenant, septemberWib)).isEqualTo("ORD-2609-0002")
    }

    @Test
    fun `concurrent callers never receive the same number`() {
        val callers = 16
        val perCaller = 25
        val pool = Executors.newFixedThreadPool(callers)
        val produced = java.util.concurrent.ConcurrentLinkedQueue<String>()
        try {
            repeat(callers) {
                pool.submit { repeat(perCaller) { produced += generator.next(tenant, septemberWib) } }
            }
            pool.shutdown()
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        } finally {
            pool.shutdownNow()
        }

        // Nomor kembar berarti UNIQUE (tenant_id, order_number) menolak salah satu pesanan dan
        // permintaan pelanggan hilang tanpa pernah tercatat — karena itu `SELECT max()+1` dilarang.
        assertThat(produced).hasSize(callers * perCaller)
        assertThat(produced.toSet()).hasSize(callers * perCaller)
        assertThat(produced).contains("ORD-2609-0001", "ORD-2609-0400")
    }
}
