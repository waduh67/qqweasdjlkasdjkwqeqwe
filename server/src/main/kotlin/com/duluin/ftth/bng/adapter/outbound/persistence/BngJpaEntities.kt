package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.NasVendor
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Profil layanan (paket): kecepatan + pemetaan ke profil RADIUS. Semua atribut mutable. */
@Entity
@Table(name = "rate_profile")
class RateProfileJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 60)
    var name: String,

    @Column(length = 200)
    var description: String?,

    @Column(name = "down_mbps", nullable = false)
    var downMbps: Int,

    @Column(name = "up_mbps", nullable = false)
    var upMbps: Int,

    @Column(name = "radius_profile_name", length = 100)
    var radiusProfileName: String?,
) : TenantAwareJpaEntity(id)

/**
 * Registri BRAS/NAS. [coaSecret] disimpan terenkripsi (batas enkripsi di adapter),
 * kolomnya dilonggarkan agar muat ciphertext yang lebih panjang dari plaintext.
 */
@Entity
@Table(name = "nas")
class NasJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 80)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var vendor: NasVendor,

    @Column(length = 255)
    var address: String?,

    @Column(name = "nas_identifier", length = 128)
    var nasIdentifier: String?,

    @Column(name = "coa_secret", length = 512)
    var coaSecret: String?,

    @Column(name = "collector_id")
    var collectorId: UUID?,

    @Column(nullable = false)
    var enabled: Boolean,
) : TenantAwareJpaEntity(id)

/**
 * Identitas jaringan (akun PPPoE). Identitas ([subscriptionId], [customerId],
 * [username], [authType]) tak berubah setelah dibuat → `updatable = false`. [secret]
 * disimpan terenkripsi (batas enkripsi di adapter).
 */
@Entity
@Table(name = "subscriber_access")
class SubscriberAccessJpaEntity(
    id: UUID,

    @Column(name = "subscription_id", nullable = false, updatable = false)
    var subscriptionId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(nullable = false, length = 64, updatable = false)
    var username: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20, updatable = false)
    var authType: AuthType,

    @Column(nullable = false, length = 512)
    var secret: String,

    @Column(name = "rate_profile_id", nullable = false)
    var rateProfileId: UUID,

    @Column(name = "nas_id")
    var nasId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AccessStatus,
) : TenantAwareJpaEntity(id)

/**
 * Sesi PPPoE terkini per akun — di-upsert tiap poll BRAS, satu baris per akun
 * ([subscriberAccessId] unik, dijaga index migrasi). Bukan deret waktu: hanya keadaan
 * terakhir. Identitas turunan ([subscriptionId], [customerId], [username]) ikut disimpan
 * agar panel bisa langsung ditampilkan tanpa join lintas modul.
 */
@Entity
@Table(name = "radius_session")
class RadiusSessionJpaEntity(
    id: UUID,

    @Column(name = "subscriber_access_id", nullable = false, updatable = false)
    var subscriberAccessId: UUID,

    @Column(name = "subscription_id", nullable = false, updatable = false)
    var subscriptionId: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(nullable = false, length = 64, updatable = false)
    var username: String,

    @Column(name = "nas_id")
    var nasId: UUID?,

    @Column(name = "nas_ip", length = 45)
    var nasIp: String?,

    @Column(name = "framed_ip", length = 45)
    var framedIp: String?,

    @Column(name = "session_id", length = 128)
    var sessionId: String?,

    @Column(name = "calling_station_id", length = 64)
    var callingStationId: String?,

    @Column(nullable = false)
    var online: Boolean,

    @Column(name = "uptime_seconds")
    var uptimeSeconds: Long?,

    @Column(name = "started_at")
    var startedAt: Instant?,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
) : TenantAwareJpaEntity(id)
