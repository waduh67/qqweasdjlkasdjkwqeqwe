import { api } from './client'

/**
 * Tipe & panggilan module vpn (VPN-as-a-service). Dua bidang terpisah:
 *
 *  - HUB (server) — infrastruktur PLATFORM: OpenVPN jalan di VPS kita dengan IP publik kita.
 *    Hanya admin platform yang mengelolanya (izin `vpn.server.*`). Cermin `VpnServerController`.
 *  - AKUN — milik tenant: cukup sekali klik generate, sistem AUTO-ASSIGN ke hub yang tersedia
 *    dan mengembalikan kredensial siap tempel ke Mikrotik. Cermin `VpnAccountController`.
 *
 * Rahasia tak pernah dibaca balik: token node hub & perintah pasang hanya tampil sekali
 * (saat buat/rotasi); password akun hanya tampil sekali (saat generate/rotasi) atau lewat
 * unduh .ovpn/RouterOS.
 */

/** Protokol transport hub. UDP disarankan (lebih ringan untuk tunnel). */
export type VpnProtocol = 'UDP' | 'TCP'

// ============================ HUB (platform) ============================

/**
 * Proyeksi satu hub VPN platform. [hasCaCert]/[hasTlsAuth] menandai rahasia terisi tanpa
 * membocorkannya; [pkiReady] true bila aplikasi sudah menerbitkan CA + sertifikat server
 * (hub siap dipasang). [peerCount] = jumlah akun terpasang (lintas-tenant).
 * [nodeToken]/[installCommand] hanya terisi tepat setelah create / rotasi token — sekali tampil.
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

/** Daftar hub VPN platform. */
export const listServers = () => api.get<VpnServerView[]>('/api/vpn/servers')

/** Detail satu hub. */
export const getServer = (id: string) => api.get<VpnServerView>(`/api/vpn/servers/${id}`)

/** Daftarkan hub baru; balikannya memuat [nodeToken] + [installCommand] sekali tampil. */
export const createServer = (body: CreateVpnServerRequest) => api.post<VpnServerView>('/api/vpn/servers', body)

/** Ubah nama & titik dial hub. */
export const updateServer = (id: string, body: UpdateVpnServerRequest) =>
  api.put<VpnServerView>(`/api/vpn/servers/${id}`, body)

/** Rotasi token node hub; balikannya memuat token + perintah pasang baru sekali tampil. */
export const regenerateToken = (id: string) => api.post<VpnServerView>(`/api/vpn/servers/${id}/regenerate-token`)

/** Hapus hub (ditolak server bila masih menampung akun). */
export const deleteServer = (id: string) => api.del<void>(`/api/vpn/servers/${id}`)

// ============================ AKUN (tenant) ============================

/**
 * Proyeksi satu AKUN VPN milik tenant — semua yang perlu ditempel ke Mikrotik. Endpoint
 * ([host]:[port]/[protocol]) + [securityType] berasal dari hub yang di-auto-assign.
 * [password] SENGAJA hanya terisi sekali saat generate/rotasi (sekali tampil); pada list/get
 * selalu null dan hanya bisa diperoleh ulang lewat unduh config.
 */
export interface VpnAccountView {
  id: string
  label: string
  serverName: string
  host: string
  port: number
  protocol: VpnProtocol
  cipher: string
  securityType: string
  username: string
  overlayIp: string
  /** Port publik TCP di hub yang di-DNAT ke Winbox (8291) perangkat. */
  remotePort: number
  /** Alamat siap-Winbox `host:remotePort` — remote perangkat tanpa ikut men-dial tunnel. */
  winboxAddress: string
  status: string
  lastHandshakeAt: string | null
  /** True bila hub-nya TCP → juga melayani RouterOS v6 (TCP + AES-256-CBC), bukan cuma v7. */
  supportsV6: boolean
  password: string | null
  /** Perintah RouterOS v7 satu-baris siap tempel di terminal Mikrotik; sekali tampil bersama [password]. */
  routerOsCommand: string | null
  /** Perintah RouterOS v6 (TCP + AES-256-CBC, best-effort); sekali tampil, hanya bila [supportsV6]. */
  routerOsCommandV6: string | null
}

/** Varian klien saat unduh config: v7 (UDP/TCP + GCM) atau v6 (TCP + CBC). */
export type VpnClientVariant = 'V7' | 'V6'

/** Semua opsional — alur unggulan cukup POST kosong. */
export interface GenerateVpnAccountRequest {
  label?: string | null
  deviceType?: string | null
  deviceId?: string | null
  username?: string | null
}

/** Daftar akun VPN tenant. */
export const listAccounts = () => api.get<VpnAccountView[]>('/api/vpn/accounts')

/** Detail satu akun (password selalu null di sini). */
export const getAccount = (id: string) => api.get<VpnAccountView>(`/api/vpn/accounts/${id}`)

/** Generate akun baru (auto-assign hub); balikannya memuat kredensial + password sekali tampil. */
export const generateAccount = (body: GenerateVpnAccountRequest = {}) =>
  api.post<VpnAccountView>('/api/vpn/accounts/generate', body)

/** Aktifkan akun. */
export const enableAccount = (id: string) => api.post<VpnAccountView>(`/api/vpn/accounts/${id}/enable`)

/** Nonaktifkan akun. */
export const disableAccount = (id: string) => api.post<VpnAccountView>(`/api/vpn/accounts/${id}/disable`)

/** Rotasi password akun; password baru tampil sekali di balikan. */
export const rotateAccountPassword = (id: string) =>
  api.post<VpnAccountView>(`/api/vpn/accounts/${id}/rotate-password`)

/** Hapus akun. */
export const deleteAccount = (id: string) => api.del<void>(`/api/vpn/accounts/${id}`)

// ---- Unduh config akun (berisi kredensial; izin vpn.config.view) ----

/** Unduh berkas .ovpn akun sebagai Blob (dijadikan unduhan file oleh pemanggil). [variant] V7 (GCM)/V6 (CBC). */
export const downloadAccountOvpn = (id: string, variant: VpnClientVariant = 'V7') =>
  api.blob(`/api/vpn/accounts/${id}/ovpn?variant=${variant}`)

/** Unduh skrip RouterOS akun sebagai Blob. [variant] V7 (default)/V6 (perangkat lama). */
export const downloadAccountRouterOs = (id: string, variant: VpnClientVariant = 'V7') =>
  api.blob(`/api/vpn/accounts/${id}/routeros?variant=${variant}`)
