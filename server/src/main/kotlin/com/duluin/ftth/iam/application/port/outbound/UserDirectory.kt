package com.duluin.ftth.iam.application.port.outbound

import java.util.UUID

/**
 * Indeks pre-auth email→tenant untuk login TANPA slug tenant (1 email = 1 tenant).
 * Di-query SEBELUM tenant context terpasang (saat login server belum tahu tenant).
 * Dipelihara sebagai efek samping penyimpanan user (chokepoint [UserRepository.save]).
 */
interface UserDirectory {

    /** Tenant pemilik sebuah email (harus sudah lowercase), atau null bila tak dikenal. */
    fun findTenantByEmail(emailLower: String): UUID?

    /**
     * Email login perwakilan sebuah tenant (user terlama = admin onboarding pertama); null bila
     * tenant tak punya user. Non-RLS → aman dipanggil TANPA tenant context (mis. scheduler platform).
     */
    fun primaryEmailForTenant(tenantId: UUID): String?
}
