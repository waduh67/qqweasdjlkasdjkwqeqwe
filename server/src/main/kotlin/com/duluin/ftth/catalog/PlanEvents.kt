package com.duluin.ftth.catalog

import java.util.UUID

/**
 * Peristiwa daur hidup paket yang diterbitkan modul `catalog` setelah perubahan
 * berhasil.
 *
 * Diletakkan di base package (permukaan publik catalog) supaya modul lain — khususnya
 * `bng`, yang menegakkan kecepatan paket ke RADIUS — bisa bereaksi tanpa bergantung
 * pada internal catalog, dan tanpa menimbulkan ketergantungan balik. Penerbitan
 * in-JVM; konsumen mendengarkan pada fase AFTER_COMMIT agar hanya melihat perubahan
 * yang benar-benar ter-commit.
 */
data class PlanCreated(val tenantId: UUID, val planId: UUID)

/**
 * Atribut paket berubah. Konsumen jaringan (`bng`) menyinkronkan ulang grup RADIUS
 * paket ini lalu mendorong CoA ke sesi hidup agar kecepatan baru langsung berlaku.
 */
data class PlanUpdated(val tenantId: UUID, val planId: UUID)

/** Paket dinonaktifkan — tak lagi bisa dipilih langganan baru (langganan lama tetap jalan). */
data class PlanDeactivated(val tenantId: UUID, val planId: UUID)
