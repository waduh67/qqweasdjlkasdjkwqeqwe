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
