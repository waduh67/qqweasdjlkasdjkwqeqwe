package com.duluin.ftth.common.security

import java.util.UUID

/**
 * Dimensi SCOPE pada RBAC diterjemahkan ke satu nilai yang dimengerti port
 * repository: `null` = tanpa batas area, non-null = hanya area tersebut.
 *
 * Dibuat sebagai extension di shared kernel agar setiap module bisnis menerapkan
 * pembatasan yang sama persis, bukan menafsirkan `areaIds` sendiri-sendiri —
 * salah tafsir di satu module berarti kebocoran data antar wilayah.
 */
fun AuthenticatedUser.areaScope(): Set<UUID>? = if (areaRestricted) areaIds else null
