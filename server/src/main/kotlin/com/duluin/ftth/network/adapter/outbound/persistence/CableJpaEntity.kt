package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.CableInstallation
import com.duluin.ftth.network.domain.model.CableOwnership
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
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

    @Column(nullable = false, length = 40, updatable = false)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "from_kind", nullable = false, length = 20)
    var fromKind: NetworkNodeKind,

    @Column(name = "from_id", nullable = false)
    var fromId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_kind", nullable = false, length = 20)
    var toKind: NetworkNodeKind,

    @Column(name = "to_id", nullable = false)
    var toId: UUID,

    /** FEEDER: PON port OLT sumber. Null untuk kabel lama / ujung non-OLT. */
    @Column(name = "from_pon_port_id")
    var fromPonPortId: UUID?,

    /** Sumber: kaki splitter ODC / slot ODP. Null untuk kabel lama. */
    @Column(name = "from_port_number")
    var fromPortNumber: Int?,

    /** Input tujuan (opsional, umumnya tunggal). Null untuk kabel lama. */
    @Column(name = "to_port_number")
    var toPortNumber: Int?,

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
