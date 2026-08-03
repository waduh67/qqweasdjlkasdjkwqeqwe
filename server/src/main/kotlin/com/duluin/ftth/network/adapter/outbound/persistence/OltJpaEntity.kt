package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.OltVendor
import com.duluin.ftth.network.domain.model.SnmpVersion
import com.duluin.ftth.network.domain.model.WebProtocol
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.locationtech.jts.geom.Point
import java.util.UUID

@Entity
@Table(name = "olt")
class OltJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 40, updatable = false)
    var code: String,

    @Column(name = "site_id", nullable = false)
    var siteId: UUID,

    @Column(nullable = false, length = 150)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var vendor: OltVendor,

    @Column(length = 80)
    var model: String?,

    @Column(name = "management_ip", length = 45)
    var managementIp: String?,

    /** Ciphertext AES-GCM, bukan plaintext — lihat AesGcmSecretCipher. */
    @Column(name = "snmp_community")
    var snmpCommunity: String?,

    @Column(name = "snmp_port", nullable = false)
    var snmpPort: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AssetStatus,

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    var location: Point,

    @Column(name = "area_id")
    var areaId: UUID?,

    @Column(columnDefinition = "text")
    var description: String?,

    @Column(name = "snmp_enabled", nullable = false)
    var snmpEnabled: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "snmp_version", nullable = false, length = 10)
    var snmpVersion: SnmpVersion,

    @Column(name = "web_enabled", nullable = false)
    var webEnabled: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "web_protocol", nullable = false, length = 10)
    var webProtocol: WebProtocol,

    @Column(name = "web_port")
    var webPort: Int?,

    @Column(name = "web_username", length = 100)
    var webUsername: String?,

    /** Ciphertext AES-GCM, bukan plaintext — lihat AesGcmSecretCipher. */
    @Column(name = "web_password")
    var webPassword: String?,
) : TenantAwareJpaEntity(id)
