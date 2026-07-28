package com.duluin.ftth.catalog.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

/**
 * Paket internet. Atribut jaringan disimpan sebagai angka terpisah (dirakit jadi
 * string Mikrotik-Rate-Limit di domain, bukan disimpan sebagai teks). [serviceTypes]
 * disimpan sebagai nama enum yang digabung koma — cukup untuk metadata ketersediaan
 * tanpa tabel anak. Semua atribut mutable.
 */
@Entity
@Table(name = "plan")
class PlanJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 60)
    var name: String,

    @Column(length = 200)
    var description: String?,

    @Column(nullable = false, precision = 14, scale = 2)
    var price: BigDecimal,

    @Column(name = "down_mbps", nullable = false)
    var downMbps: Int,

    @Column(name = "up_mbps", nullable = false)
    var upMbps: Int,

    @Column(name = "down_burst_mbps")
    var downBurstMbps: Int?,

    @Column(name = "up_burst_mbps")
    var upBurstMbps: Int?,

    @Column(name = "down_threshold_mbps")
    var downThresholdMbps: Int?,

    @Column(name = "up_threshold_mbps")
    var upThresholdMbps: Int?,

    @Column(name = "burst_time_sec")
    var burstTimeSec: Int?,

    @Column(name = "down_min_mbps")
    var downMinMbps: Int?,

    @Column(name = "up_min_mbps")
    var upMinMbps: Int?,

    @Column(nullable = false)
    var priority: Int,

    @Column(name = "connection_limit")
    var connectionLimit: Int?,

    @Column(name = "fup_enabled", nullable = false)
    var fupEnabled: Boolean,

    @Column(name = "fup_quota_mb")
    var fupQuotaMb: Long?,

    @Column(name = "fup_down_mbps")
    var fupDownMbps: Int?,

    @Column(name = "fup_up_mbps")
    var fupUpMbps: Int?,

    /** Nama-nama ServiceType digabung koma, mis. "PPPOE,STATIC". */
    @Column(name = "service_types", nullable = false, length = 100)
    var serviceTypes: String,

    @Column(name = "prorate_on_activation")
    var prorateOnActivation: Boolean?,

    @Column(name = "billing_day_of_month")
    var billingDayOfMonth: Int?,

    @Column(name = "due_days")
    var dueDays: Int?,

    @Column(name = "grace_days")
    var graceDays: Int?,

    @Column(name = "auto_isolir")
    var autoIsolir: Boolean?,

    @Column(nullable = false)
    var active: Boolean,
) : TenantAwareJpaEntity(id)
