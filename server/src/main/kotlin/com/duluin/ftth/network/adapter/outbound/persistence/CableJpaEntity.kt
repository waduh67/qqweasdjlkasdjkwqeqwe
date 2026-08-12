package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableInstallation
import com.duluin.ftth.network.domain.model.CableOwnership
import com.duluin.ftth.network.domain.model.CableType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.locationtech.jts.geom.LineString
import java.util.UUID

@Entity
@Table(name = "cable")
class CableJpaEntity(
    id: UUID,

    /**
     * Dulu `updatable = false` — kode dianggap pengenal seumur hidup. Itu tak bertahan
     * di lapangan: ruas yang terlanjur berkode buatan sistem harus bisa dirapikan ke
     * penomoran perusahaan, dan salah ketik pada label selubung tak semestinya menuntut
     * kabelnya digambar ulang. Yang jadi pengenal tetap hanya `id`; kode itu label.
     */
    @Column(nullable = false, length = 40)
    var code: String,

    @Column(nullable = false, length = 150)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "cable_type", nullable = false, length = 20)
    var cableType: CableType,

    @Column(name = "core_count", nullable = false)
    var coreCount: Int,

    @Column(nullable = false, columnDefinition = "geometry(LineString,4326)")
    var route: LineString,

    /** Turunan dari [route]; disimpan agar bisa di-sort & diagregasi di SQL. */
    @Column(name = "length_meters", nullable = false)
    var lengthMeters: Double,

    // Simpul yang disentuh kabel TIDAK lagi di sini. Sejak V99 tempatnya
    // `cable_attachment` — barisan singgahan berurutan, karena satu selubung
    // bisa dikupas di belasan kotak dan sepasang kolom from/to tak sanggup
    // menampungnya. Lihat CableAttachmentJpaEntity.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AssetStatus,

    /** Null = belum disurvei; bukan "tak terpasang". Lihat V88. */
    @Enumerated(EnumType.STRING)
    @Column(name = "installation_method", length = 20)
    var installationMethod: CableInstallation?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var ownership: CableOwnership,
) : TenantAwareJpaEntity(id)
