package com.duluin.ftth.cpe.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.cpe.domain.model.CpeActionStatus
import com.duluin.ftth.cpe.domain.model.CpeActionType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Proyeksi CPE tersimpan. Atribut selain identitas boleh berubah tiap sinkronisasi
 * (perangkat melaporkan firmware/IP baru), jadi mutable; [genieacsId] & [serialNumber]
 * adalah identitas dan `updatable = false`.
 */
@Entity
@Table(name = "cpe_device")
class CpeDeviceJpaEntity(
    id: UUID,

    @Column(name = "genieacs_id", nullable = false, length = 128, updatable = false)
    var genieacsId: String,

    @Column(name = "serial_number", nullable = false, length = 64, updatable = false)
    var serialNumber: String,

    @Column(length = 32)
    var oui: String?,

    @Column(name = "product_class", length = 128)
    var productClass: String?,

    @Column(length = 128)
    var manufacturer: String?,

    @Column(length = 128)
    var model: String?,

    @Column(name = "software_version", length = 128)
    var softwareVersion: String?,

    @Column(name = "ip_address", length = 64)
    var ipAddress: String?,

    @Column(name = "last_inform_at")
    var lastInformAt: Instant?,

    @Column(length = 64)
    var ssid: String?,

    @Column(name = "temperature_c")
    var temperatureC: Double?,

    @Column(name = "customer_id")
    var customerId: UUID?,

    @Column(name = "onu_id")
    var onuId: UUID?,
) : TenantAwareJpaEntity(id)

/**
 * Jejak audit perintah ke CPE. Append-only — sekali ditulis tak diubah, jadi seluruh
 * kolom `updatable = false`.
 */
@Entity
@Table(name = "cpe_action_log")
class CpeActionLogJpaEntity(
    id: UUID,

    @Column(name = "device_id", nullable = false, updatable = false)
    var deviceId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var action: CpeActionType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var status: CpeActionStatus,

    @Column(length = 500, updatable = false)
    var detail: String?,

    @Column(name = "requested_by", nullable = false, updatable = false)
    var requestedBy: UUID,

    @Column(name = "requested_by_email", length = 320, updatable = false)
    var requestedByEmail: String?,

    @Column(name = "requested_at", nullable = false, updatable = false)
    var requestedAt: Instant,
) : TenantAwareJpaEntity(id)
