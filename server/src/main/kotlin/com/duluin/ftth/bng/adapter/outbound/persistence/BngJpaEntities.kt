package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.BngActionStatus
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.NasReachability
import com.duluin.ftth.bng.domain.model.NasVendor
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

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

    @Column(name = "api_username", length = 128)
    var apiUsername: String?,

    @Column(name = "api_secret", length = 512)
    var apiSecret: String?,

    @Column(name = "api_port")
    var apiPort: Int?,

    @Column(name = "api_use_tls", nullable = false)
    var apiUseTls: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var reachability: NasReachability,
) : TenantAwareJpaEntity(id)

/**
 * Identitas jaringan (akun PPPoE/Hotspot atau berbasis MAC untuk DHCP/Static). Identitas
 * ([subscriptionId], [customerId], [username], [authType], [framedIp]) tak berubah setelah
 * dibuat → `updatable = false`. [secret] disimpan terenkripsi (batas enkripsi di adapter).
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

    @Column(name = "plan_id", nullable = false)
    var planId: UUID,

    @Column(name = "nas_id")
    var nasId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AccessStatus,

    @Column(name = "fup_throttled", nullable = false)
    var fupThrottled: Boolean,

    /** Reservasi Framed-IP-Address untuk DHCP/Static; null untuk PPPoE/Hotspot. Terikat identitas. */
    @Column(name = "framed_ip", length = 45, updatable = false)
    var framedIp: String?,
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

/**
 * Antrean + jejak audit perintah BRAS (Phase 7c). Identitas ([subscriberAccessId],
 * [nasId], [username], [action], payload, [requestedBy]/[requestedAt]) tak berubah
 * setelah dibuat → `updatable = false`; hanya status & waktu penuntasannya berpindah.
 *
 * [subscriberAccessId] nullable: perintah tingkat-grup (SYNC_GROUP) & penghapusan
 * (DEPROVISION) sengaja LEPAS dari akun agar tak ikut ter-CASCADE saat akun dihapus,
 * sehingga penghapusan RADIUS tetap terkirim. Kolom payload ([groupname]..[fupRateLimit])
 * terisi sesuai jenis perintah.
 */
@Entity
@Table(name = "bng_action")
class BngActionJpaEntity(
    id: UUID,

    @Column(name = "subscriber_access_id", updatable = false)
    var subscriberAccessId: UUID?,

    @Column(name = "nas_id", nullable = false, updatable = false)
    var nasId: UUID,

    @Column(nullable = false, length = 64, updatable = false)
    var username: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var action: BngActionType,

    /** Skema identitas akun yang dituju — memetakan penulisan radius-db (slug-prefix vs MAC). */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20, updatable = false)
    var authType: AuthType,

    @Column(name = "down_mbps", updatable = false)
    var downMbps: Int?,

    @Column(name = "up_mbps", updatable = false)
    var upMbps: Int?,

    @Column(name = "groupname", length = 128, updatable = false)
    var groupname: String?,

    @Column(name = "rate_limit", length = 200, updatable = false)
    var rateLimit: String?,

    @Column(name = "simultaneous_use", updatable = false)
    var simultaneousUse: Int?,

    @Column(name = "fup_group", length = 128, updatable = false)
    var fupGroupname: String?,

    @Column(name = "fup_rate_limit", length = 200, updatable = false)
    var fupRateLimit: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: BngActionStatus,

    @Column(length = 500)
    var detail: String?,

    @Column(name = "requested_by", updatable = false)
    var requestedBy: UUID?,

    @Column(name = "requested_by_email", length = 320, updatable = false)
    var requestedByEmail: String?,

    @Column(name = "requested_at", nullable = false, updatable = false)
    var requestedAt: Instant,

    @Column(name = "dispatched_at")
    var dispatchedAt: Instant?,

    @Column(name = "completed_at")
    var completedAt: Instant?,
) : TenantAwareJpaEntity(id)
