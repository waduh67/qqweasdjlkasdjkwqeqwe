package com.duluin.ftth.portal.security

import java.util.UUID

/**
 * Identitas PELANGGAN yang login ke portal self-service untuk request saat ini —
 * principal realm portal, sengaja BUKAN [com.duluin.ftth.common.security.AuthenticatedUser]
 * (operator IAM). Cakupannya selalu dirinya sendiri: tak ada izin RBAC, tak ada area;
 * server men-scope semua baca/tulis ke [customerId] + tenant-nya.
 */
data class PortalCustomer(
    val customerId: UUID,
    val tenantId: UUID,
    val login: String,
    val name: String,
)
