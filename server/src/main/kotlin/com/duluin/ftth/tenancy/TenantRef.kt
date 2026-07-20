package com.duluin.ftth.tenancy

import java.util.UUID

/**
 * Representasi tenant yang stabil untuk konsumen lintas-module — sengaja bukan
 * entity domain internal, agar module lain tidak kopel ke detail internal tenancy.
 */
data class TenantRef(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: TenantStatus,
)
