package com.duluin.ftth.bng.application.port.inbound

import java.util.UUID

/** Proyeksi satu paket (rate profile) untuk UI. */
data class RateProfileView(
    val id: UUID,
    val name: String,
    val description: String?,
    val downMbps: Int,
    val upMbps: Int,
    val radiusProfileName: String?,
)

/**
 * Proyeksi satu BRAS/NAS. [hasCoaSecret] menandai secret CoA sudah diisi tanpa
 * pernah membocorkan nilainya lewat API.
 */
data class NasView(
    val id: UUID,
    val name: String,
    val vendor: String,
    val address: String?,
    val nasIdentifier: String?,
    val hasCoaSecret: Boolean,
    val collectorId: UUID?,
    val enabled: Boolean,
)

/**
 * Proyeksi satu akun PPPoE. Password (secret) SENGAJA tidak disertakan — hanya bisa
 * diisi/reset, tak pernah dibaca balik. [rateProfileName]/[nasName] diresolusi untuk
 * tampilan agar UI tak perlu memanggil balik.
 */
data class SubscriberAccessView(
    val id: UUID,
    val subscriptionId: UUID,
    val customerId: UUID,
    val username: String,
    val authType: String,
    val rateProfileId: UUID,
    val rateProfileName: String?,
    val nasId: UUID?,
    val nasName: String?,
    val status: String,
)
