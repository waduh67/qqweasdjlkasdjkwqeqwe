package com.duluin.ftth.vpn.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.vpn.domain.model.VpnPeerStatus
import com.duluin.ftth.vpn.domain.model.VpnProtocol
import com.duluin.ftth.vpn.domain.model.VpnServerStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Hub OpenVPN — infrastruktur PLATFORM (tanpa tenant/RLS). [caCert]/[tlsAuthKey] disimpan
 * terenkripsi (batas enkripsi di adapter); kolomnya `text` agar muat PEM sertifikat +
 * ciphertext yang lebih panjang dari plaintext.
 */
@Entity
@Table(name = "vpn_server")
class VpnServerJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(nullable = false, length = 255)
    var host: String,

    @Column(nullable = false)
    var port: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var protocol: VpnProtocol,

    @Column(name = "tunnel_cidr", nullable = false, length = 64)
    var tunnelCidr: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: VpnServerStatus,

    @Column(name = "ca_cert", columnDefinition = "text")
    var caCert: String?,

    @Column(name = "tls_auth_key", columnDefinition = "text")
    var tlsAuthKey: String?,

    @Column(name = "ca_key", columnDefinition = "text")
    var caKey: String?,

    @Column(name = "server_cert", columnDefinition = "text")
    var serverCert: String?,

    @Column(name = "server_key", columnDefinition = "text")
    var serverKey: String?,
) : BaseJpaEntity(id)

/**
 * Akun VPN milik tenant (satu perangkat yang men-dial hub). Tabel TANPA RLS (cermin
 * [CollectorJpaEntity]): [tenantId] kolom biasa (bukan `@TenantId`) — difilter tenant di
 * aplikasi untuk daftar/kelola, sekaligus bisa dibaca lintas-tenant per hub untuk auth
 * callback. Identitas ([serverId], [username], [overlayIp]) tak berubah setelah dibuat →
 * `updatable = false`. [password] terenkripsi (batas enkripsi di adapter). [lastHandshakeAt]
 * dicadangkan untuk liveness kelak.
 */
@Entity
@Table(name = "vpn_peer")
class VpnPeerJpaEntity(
    id: UUID,

    @Column(name = "tenant_id", nullable = false, updatable = false)
    var tenantId: UUID,

    @Column(name = "server_id", nullable = false, updatable = false)
    var serverId: UUID,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(nullable = false, length = 64, updatable = false)
    var username: String,

    @Column(name = "overlay_ip", nullable = false, length = 45, updatable = false)
    var overlayIp: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: VpnPeerStatus,

    @Column(name = "device_type", length = 60)
    var deviceType: String?,

    @Column(name = "device_id")
    var deviceId: UUID?,

    @Column(name = "last_handshake_at")
    var lastHandshakeAt: Instant?,

    @Column(nullable = false, length = 512)
    var password: String,
) : BaseJpaEntity(id)

/**
 * Token node per hub. [BaseJpaEntity] dan tabelnya TANPA RLS — persis pola [CollectorJpaEntity]:
 * barisnya dicari lewat hash token untuk mengenali hub yang memanggil balik. Menaut ke hub
 * saja (hub adalah infrastruktur platform tanpa tenant); peer di-resolve dari username.
 */
@Entity
@Table(name = "vpn_node_token")
class VpnNodeTokenJpaEntity(
    id: UUID,

    @Column(name = "server_id", nullable = false, updatable = false)
    var serverId: UUID,

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    var tokenHash: String,

    @Column(name = "token_hint", nullable = false, length = 16, updatable = false)
    var tokenHint: String,
) : BaseJpaEntity(id)
