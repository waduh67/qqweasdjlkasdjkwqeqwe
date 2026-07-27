import { api } from './client'

/**
 * Tipe & panggilan module vpn (back-haul OpenVPN: remote Mikrotik tanpa IP publik).
 * Cermin `VpnController` di server.
 *
 * Rahasia tak pernah dibaca balik: sertifikat/kunci hub hanya keluar lewat unduh
 * config, password peer hanya lewat unduh .ovpn/RouterOS. Token node hub MENTAH
 * hanya terisi tepat setelah hub dibuat / token dirotasi — sekali tampil.
 */

/** Protokol transport hub. UDP disarankan (lebih ringan untuk tunnel). */
export type VpnProtocol = 'UDP' | 'TCP'

/**
 * Proyeksi satu hub VPN. [hasCaCert]/[hasTlsAuth] menandai rahasia terisi tanpa
 * membocorkannya; [pkiReady] true bila aplikasi sudah menerbitkan CA + sertifikat
 * server (hub siap dipasang). [nodeToken]/[installCommand] hanya terisi tepat setelah
 * create / rotasi token — sekali tampil, null pada list/get.
 */
export interface VpnServerView {
  id: string
  name: string
  host: string
  port: number
  protocol: VpnProtocol
  tunnelCidr: string
  serverAddress: string
  status: string
  hasCaCert: boolean
  hasTlsAuth: boolean
  pkiReady: boolean
  peerCount: number
  nodeToken: string | null
  installCommand: string | null
}

/**
 * Proyeksi satu peer/perangkat. Password sengaja tak disertakan — hanya bisa dirotasi,
 * tak pernah dibaca balik lewat pandangan biasa (hanya lewat unduh .ovpn/RouterOS).
 */
export interface VpnPeerView {
  id: string
  serverId: string
  name: string
  username: string
  overlayIp: string
  status: string
  deviceType: string | null
  deviceId: string | null
  /** ISO UTC waktu handshake terakhir; null bila belum pernah terhubung. */
  lastHandshakeAt: string | null
}

/** Buat hub baru; port/protocol/tunnelCidr null = di-default dari server. */
export interface CreateVpnServerRequest {
  name: string
  host: string
  port?: number | null
  protocol?: VpnProtocol | null
  tunnelCidr?: string | null
}

/** Ubah nama & titik dial hub; subnet dan kredensial tak disentuh dari sini. */
export interface UpdateVpnServerRequest {
  name: string
  host: string
  port: number
  protocol: VpnProtocol
}

/** Tambah peer; username kosong = diturunkan dari nama & dijamin unik per hub. */
export interface CreateVpnPeerRequest {
  name: string
  deviceType?: string | null
  deviceId?: string | null
  username?: string | null
}

// ---- Hub VPN ----

/** Daftar hub VPN tenant. */
export const listServers = () => api.get<VpnServerView[]>('/api/vpn/servers')

/** Detail satu hub. */
export const getServer = (id: string) => api.get<VpnServerView>(`/api/vpn/servers/${id}`)

/** Daftarkan hub baru; balikannya memuat [nodeToken] + [installCommand] sekali tampil. */
export const createServer = (body: CreateVpnServerRequest) => api.post<VpnServerView>('/api/vpn/servers', body)

/** Ubah nama & titik dial hub. */
export const updateServer = (id: string, body: UpdateVpnServerRequest) =>
  api.put<VpnServerView>(`/api/vpn/servers/${id}`, body)

/** Rotasi token node hub; balikannya memuat token + perintah pasang baru sekali tampil. */
export const regenerateToken = (id: string) =>
  api.post<VpnServerView>(`/api/vpn/servers/${id}/regenerate-token`)

/** Hapus hub (ditolak server bila masih punya peer). */
export const deleteServer = (id: string) => api.del<void>(`/api/vpn/servers/${id}`)

// ---- Peer/perangkat ----

/** Daftar peer sebuah hub. */
export const listPeers = (serverId: string) => api.get<VpnPeerView[]>(`/api/vpn/servers/${serverId}/peers`)

/** Tambah peer (IP overlay & password digenerate otomatis). */
export const createPeer = (serverId: string, body: CreateVpnPeerRequest) =>
  api.post<VpnPeerView>(`/api/vpn/servers/${serverId}/peers`, body)

/** Aktifkan peer. */
export const enablePeer = (id: string) => api.post<VpnPeerView>(`/api/vpn/peers/${id}/enable`)

/** Nonaktifkan peer. */
export const disablePeer = (id: string) => api.post<VpnPeerView>(`/api/vpn/peers/${id}/disable`)

/** Rotasi password peer (password baru hanya keluar lewat unduh config berikutnya). */
export const rotatePeerPassword = (id: string) => api.post<VpnPeerView>(`/api/vpn/peers/${id}/rotate-password`)

/** Hapus peer. */
export const deletePeer = (id: string) => api.del<void>(`/api/vpn/peers/${id}`)

// ---- Unduh config (berisi kredensial; izin vpn.config.view) ----

/** Unduh berkas .ovpn peer sebagai Blob (dijadikan unduhan file oleh pemanggil). */
export const downloadPeerOvpn = (id: string) => api.blob(`/api/vpn/peers/${id}/ovpn`)

/** Unduh skrip RouterOS peer sebagai Blob. */
export const downloadPeerRouterOs = (id: string) => api.blob(`/api/vpn/peers/${id}/routeros`)
