package com.duluin.ftth.network

import java.util.UUID

/**
 * Dipublikasikan network saat sebuah OLT dihapus dari inventory.
 *
 * Sebagian module menyimpan data berkaitan OLT TANPA foreign key lintas-module —
 * batas antar-module dijaga dengan merujuk OLT sebagai uuid polos, bukan relasi DB
 * (mis. kotak masuk ONU terdeteksi di `monitoring`). Karena itu penghapusan OLT
 * tidak meng-cascade sendiri; module tersebut mendengarkan event ini untuk
 * membersihkan sisa yatimnya.
 *
 * Diletakkan di base package network — permukaan publiknya — karena hanya network
 * yang menerbitkannya dan consumer memang boleh bergantung pada network.
 */
data class OltDeletedEvent(
    val tenantId: UUID,
    val oltId: UUID,
    val oltCode: String,
)
