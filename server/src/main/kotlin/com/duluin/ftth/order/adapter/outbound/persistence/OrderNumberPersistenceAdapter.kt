package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.order.application.port.outbound.OrderNumberGenerator
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Nomor pesanan `ORD-YYMM-NNNN` yang berurut per (tenant, periode).
 *
 * PILIHAN DESAIN — tabel pencacah, BUKAN `SELECT max(order_number) + 1`:
 * dua permintaan bersamaan pada `max()` membaca nilai yang sama sebelum salah satunya menulis,
 * lalu keduanya mengarang nomor yang identik. Satu ditolak UNIQUE (tenant_id, order_number) dan
 * pesanan pelanggan hilang tanpa pernah tercatat — tepat pada jam tersibuk, ketika permintaan
 * memang paling mungkin bersamaan.
 *
 * `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` di bawah aman karena Postgres mengambil kunci
 * baris pada baris pencacah: transaksi kedua MENUNGGU transaksi pertama commit, lalu membaca
 * versi baris yang SUDAH naik. Tidak ada dua pemanggil yang bisa melihat angka yang sama.
 *
 * Sequence Postgres per tenant sengaja tidak dipakai: ia menuntut DDL saat tenant baru dibuat
 * (dan saat bulan berganti), dan DDL di jalur permintaan pengguna adalah sumber deadlock.
 *
 * Dua konsekuensi yang diterima sadar-sadar:
 *  1. Pembuatan pesanan untuk SATU tenant jadi berurutan selama transaksinya hidup (kunci baris
 *     pencacah dipegang sampai commit). Untuk laju pemesanan FTTH ini tidak terasa, dan
 *     pertukarannya jelas: throughput sedikit ditukar dengan nomor yang tak pernah kembar.
 *  2. Nomor bisa BERLUBANG kalau transaksi pemesanan di-rollback setelah pencacah naik. Nomor
 *     berurut rapat tidak sepenting nomor yang dijamin unik.
 */
@Component
class OrderNumberPersistenceAdapter : OrderNumberGenerator {

    @PersistenceContext private lateinit var entityManager: EntityManager

    @Transactional(propagation = Propagation.MANDATORY)
    override fun next(tenantId: UUID, at: Instant): String {
        val period = PERIOD.format(at.atZone(ZONE))
        val sequence = entityManager.createNativeQuery(
            """INSERT INTO order_number_counter (tenant_id, period, last_value, updated_at)
               VALUES (:tenant, :period, 1, now())
               ON CONFLICT (tenant_id, period)
               DO UPDATE SET last_value = order_number_counter.last_value + 1, updated_at = now()
               RETURNING last_value""",
        ).setParameter("tenant", tenantId).setParameter("period", period)
            .resultList.firstOrNull() as? Number
            ?: error("Pencacah nomor pesanan tidak mengembalikan nilai untuk tenant $tenantId periode $period")
        return "ORD-$period-${sequence.toInt().toString().padStart(SEQUENCE_WIDTH, '0')}"
    }

    private companion object {
        /**
         * Periode dihitung di zona WIB, bukan UTC: pesanan yang masuk pukul 07.00 tanggal 1
         * (masih 31 di UTC) harus bernomor bulan yang sama dengan yang dilihat operator.
         */
        val ZONE: ZoneId = ZoneId.of("Asia/Jakarta")
        val PERIOD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMM")
        const val SEQUENCE_WIDTH = 4
    }
}
