package com.duluin.ftth.customer

import java.util.UUID

/**
 * Peristiwa daur hidup langganan yang diterbitkan module customer setelah perubahan
 * status berhasil.
 *
 * Diletakkan di base package (permukaan publik customer) supaya module lain —
 * khususnya `bng`, yang menegakkan identitas jaringan pelanggan (akun PPPoE) — bisa
 * bereaksi tanpa bergantung pada internal customer, dan tanpa menimbulkan
 * ketergantungan balik (customer tidak perlu tahu bng ada). Penerbitan in-JVM;
 * konsumen mendengarkan pada fase AFTER_COMMIT agar hanya melihat perubahan yang
 * benar-benar ter-commit.
 */
data class SubscriptionActivated(val tenantId: UUID, val subscriptionId: UUID, val customerId: UUID)

/** Langganan diisolir sementara (mis. tunggakan) — layanan jaringan harus dimatikan. */
data class SubscriptionIsolated(val tenantId: UUID, val subscriptionId: UUID, val customerId: UUID)

/** Langganan diakhiri — identitas jaringannya harus dihentikan. */
data class SubscriptionTerminated(val tenantId: UUID, val subscriptionId: UUID, val customerId: UUID)

/**
 * Paket langganan berganti (upgrade/downgrade) — kecepatan yang dieksekusi jaringan harus
 * ikut pindah.
 *
 * Terbit HANYA bila [planId] benar-benar berbeda dari sebelumnya; menyunting harga atau nama
 * paket tanpa mengganti paketnya bukan peristiwa jaringan. Tanpa event ini sisi komersial dan
 * sisi jaringan berjalan sendiri-sendiri: pelanggan ditagih paket baru sementara BRAS terus
 * memberi kecepatan paket lama, dan tak ada satu pun layar yang menunjukkan keduanya berbeda.
 *
 * [planId] nullable karena langganan boleh lepas dari katalog (paket ad-hoc); null berarti tak
 * ada paket katalog yang bisa diturunkan ke RADIUS, jadi konsumen mengabaikannya.
 */
data class SubscriptionPlanChanged(
    val tenantId: UUID,
    val subscriptionId: UUID,
    val customerId: UUID,
    val planId: UUID?,
)
