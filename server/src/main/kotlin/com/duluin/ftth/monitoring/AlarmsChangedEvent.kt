package com.duluin.ftth.monitoring

import java.util.UUID

/**
 * Dipublikasikan monitoring saat alarm sebuah tenant mungkin berubah — sekali per
 * operasi (ingest batch, denyut collector, siklus watchdog), bukan per alarm,
 * agar badai onset tidak memicu korelasi berulang-ulang.
 *
 * Module `incident` mendengarkannya (setelah commit) dan mendamaikan insiden untuk
 * tenant itu. Diletakkan di base package monitoring — permukaan publiknya — karena
 * hanya monitoring yang menerbitkannya dan consumer (`incident`) memang boleh
 * bergantung pada monitoring.
 */
data class AlarmsChangedEvent(val tenantId: UUID)
