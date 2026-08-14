import { api } from './client'
import type { ServiceType } from './catalog'

/**
 * Tipe & panggilan module bng (BRAS/RADIUS: registri BRAS dan identitas jaringan/akun
 * PPPoE pelanggan). Cermin `BngController` di server. Katalog paket pindah ke modul
 * `catalog` (`web/src/api/catalog.ts`) — akun cukup merujuk `planId`.
 *
 * Secret (password PPPoE / shared secret BRAS) tak pernah dibaca balik: hanya bisa diisi
 * saat provisi atau di-reset, dan kehadirannya di BRAS ditandai boolean `hasCoaSecret`.
 * `coaSecret` adalah shared secret RADIUS dua-arah: auth Mikrotik→FreeRADIUS + CoA server→BRAS.
 */

/** Vendor BRAS yang dikenal — menentukan adapter kontrol sesi nantinya. */
export type NasVendor = 'MIKROTIK' | 'CISCO' | 'JUNIPER' | 'OTHER'

/** ACTIVE, ISOLATED, atau TERMINATED — mengekor status langganan. */
export type AccessStatus = 'ACTIVE' | 'ISOLATED' | 'TERMINATED'

/** Label manusiawi vendor BRAS untuk dropdown & tabel. */
export const NAS_VENDOR_LABEL: Record<NasVendor, string> = {
  MIKROTIK: 'MikroTik (RouterOS)',
  CISCO: 'Cisco',
  JUNIPER: 'Juniper',
  OTHER: 'Lainnya',
}

/** Urutan vendor untuk pilihan pada form. */
export const NAS_VENDORS: NasVendor[] = ['MIKROTIK', 'CISCO', 'JUNIPER', 'OTHER']

/**
 * Proyeksi satu BRAS. [hasCoaSecret]/[hasApiSecret] menandai rahasianya terisi tanpa
 * membocorkannya. Kredensial kontrol non-rahasia
 * ([apiUsername]/[apiPort]/[apiUseTls]) dibalikkan apa adanya untuk memuat ulang form
 * saat diedit — dipakai adapter REST RouterOS (vendor MIKROTIK).
 */
export interface NasView {
  id: string
  name: string
  vendor: NasVendor
  address: string | null
  nasIdentifier: string | null
  hasCoaSecret: boolean
  collectorId: string | null
  enabled: boolean
  apiUsername: string | null
  hasApiSecret: boolean
  apiPort: number | null
  apiUseTls: boolean
  /** Area yang dinaungi BRAS ini — dasar auto-pilih BRAS dari area pelanggan saat PSB. */
  areaIds: string[]
}

/**
 * Perubahan BRAS; [coaSecret]/[apiSecret] kosong saat update = biarkan rahasia apa adanya.
 * [apiUsername]/[apiPort]/[apiUseTls] = kredensial kontrol REST RouterOS (vendor MIKROTIK).
 */
export interface SaveNasRequest {
  name: string
  vendor: NasVendor
  address?: string | null
  nasIdentifier?: string | null
  coaSecret?: string | null
  collectorId?: string | null
  enabled: boolean
  apiUsername?: string | null
  apiSecret?: string | null
  apiPort?: number | null
  apiUseTls?: boolean
  /** Area yang dinaungi BRAS ini — diganti TOTAL tiap simpan (cermin `enabled`). */
  areaIds?: string[]
}

/**
 * Koordinat FreeRADIUS pusat yang router tenant arahkan (auth/acct). Sama untuk semua
 * tenant — satu server RADIUS-as-a-service. [configured] false berarti platform belum
 * mengisi [host] → UI menampilkan catatan alih-alih host tebakan. [coaPort] = arah balik
 * (server → BRAS) untuk DAE/CoA, agar operator membuka port itu di Mikrotik.
 */
export interface RadiusEndpointView {
  host: string | null
  authPort: number
  acctPort: number
  coaPort: number
  configured: boolean
  /** Alamat RADIUS versi overlay, satu per blok tunnel VPN; kosong bila VPN tak dipakai. */
  vpnHosts: RadiusVpnHostView[]
}

/**
 * Alamat RADIUS untuk BRAS yang masuk lewat overlay VPN — bukan [RadiusEndpointView.host]
 * yang publik. FreeRADIUS mengenali klien dari alamat ASAL paketnya: router ber-tunnel yang
 * diarahkan ke IP publik akan keluar lewat internet biasa, jadi paketnya datang dari IP
 * publik lokasi pelanggan dan diabaikan diam-diam sebagai klien tak dikenal.
 */
export interface RadiusVpnHostView {
  tunnelCidr: string
  host: string
}

/**
 * Proyeksi satu akun jaringan. Password (secret) sengaja tak disertakan — hanya bisa
 * diisi saat provisi atau di-reset. [planName]/[nasName] sudah diresolusi di server
 * agar UI tak perlu memanggil balik. [planId] merujuk paket di modul catalog.
 *
 * [authType] menentukan skema identitas: PPPoE/Hotspot login+password; DHCP/Static
 * berbasis MAC ([username] = MAC). [framedIp] hanya terisi untuk DHCP/Static yang
 * mereservasi IP (Framed-IP-Address); null untuk PPPoE/Hotspot.
 */
export interface SubscriberAccessView {
  id: string
  subscriptionId: string
  customerId: string
  username: string
  authType: ServiceType
  framedIp: string | null
  planId: string
  planName: string | null
  nasId: string | null
  nasName: string | null
  status: AccessStatus
  /** Paket ini ber-FUP (punya kuota + kecepatan throttle). */
  fupEnabled: boolean
  /** Kuota FUP periode (MB); null bila paket tak ber-FUP. */
  fupQuotaMb: number | null
  /** Akun sedang di-throttle FUP (dipindah ke grup kecepatan turun). */
  fupThrottled: boolean
  /** Pemakaian akun sejak awal siklus (MB); null bila tak dihitung. */
  periodUsageMb: number | null
}

/**
 * Provisi akun jaringan baru untuk sebuah langganan. [username] = login (PPPoE/Hotspot)
 * atau MAC (DHCP/Static, dinormalkan server). [secret] wajib PPPoE/Hotspot, diabaikan
 * untuk tipe berbasis MAC. [authType] harus termasuk `serviceTypes` paket. [framedIp]
 * hanya DHCP/Static (wajib STATIC).
 */
export interface ProvisionAccessRequest {
  subscriptionId: string
  username: string
  secret?: string
  planId: string
  nasId?: string | null
  authType?: ServiceType
  framedIp?: string | null
}

/** Ganti paket dan/atau BRAS sebuah akun. */
export interface UpdateAccessRequest {
  planId: string
  nasId?: string | null
}

/**
 * Keadaan sesi PPPoE terkini sebuah akun ("B-ras Check"). [online] false berarti
 * BRAS melaporkan akun tak sedang tersambung; bila [lastSeenAt] juga null, akun
 * memang belum pernah terpantau. Waktu berformat ISO UTC — UI menyesuaikan zona.
 */
export interface BrasSessionView {
  subscriberAccessId: string
  username: string
  online: boolean
  framedIp: string | null
  nasId: string | null
  nasName: string | null
  nasIp: string | null
  callingStationId: string | null
  uptimeSeconds: number | null
  startedAt: string | null
  lastSeenAt: string | null
}

/** Satu titik tren trafik siap-gambar (Mbps). null = tak terhitung → garis diputus. */
export interface TrafficPoint {
  time: string
  downMbps: number | null
  upMbps: number | null
}

/**
 * Tren trafik satu akun dalam rentang [hours] jam ke belakang. `currentDownMbps`/`currentUpMbps`
 * = laju titik terakhir yang terhitung (throughput "sekarang"; null bila akun offline).
 * `totalBytes` = total pemakaian data (unggah+unduh) pada rentang.
 */
export interface TrafficHistoryView {
  subscriberAccessId: string
  hours: number
  points: TrafficPoint[]
  currentDownMbps: number | null
  currentUpMbps: number | null
  totalBytes: number
}

// ---- BRAS/NAS ----

/** Daftar BRAS tenant. */
export const listNas = () => api.get<NasView[]>('/api/bng/nas')

/** Koordinat FreeRADIUS pusat untuk arahkan router tenant (host+port). */
export const getRadiusEndpoint = () => api.get<RadiusEndpointView>('/api/bng/radius-endpoint')

/** Daftarkan BRAS baru. */
export const createNas = (body: SaveNasRequest) => api.post<NasView>('/api/bng/nas', body)

/** Ubah BRAS; secret kosong = biarkan apa adanya. */
export const updateNas = (id: string, body: SaveNasRequest) => api.put<NasView>(`/api/bng/nas/${id}`, body)

/** Hapus BRAS (ditolak server bila masih menaungi akun). */
export const deleteNas = (id: string) => api.del<void>(`/api/bng/nas/${id}`)

/**
 * Pratinjau akun PPPoE (`/ppp/secret`) di RouterOS BRAS ini untuk bulk-import — TANPA password
 * (server menariknya saat commit). Menyentuh router langsung; gagal 409 bila BRAS tak
 * ber-alamat/kredensial/terjangkau atau vendornya bukan MikroTik. Izin `bng.nas.manage`.
 */
export interface PppSecretPreview {
  name: string
  profile: string | null
  service: string | null
  comment: string | null
  disabled: boolean
}

/** Tarik pratinjau `/ppp/secret` dari sebuah BRAS. */
export const previewPppSecrets = (nasId: string) =>
  api.get<PppSecretPreview[]>(`/api/bng/nas/${nasId}/ppp-secrets`)

// ---- Akun PPPoE (identitas jaringan) ----

/** Semua akun PPPoE milik satu pelanggan (satu per langganan). */
export const listAccessForCustomer = (customerId: string) =>
  api.get<SubscriberAccessView[]>(`/api/bng/access?customerId=${customerId}`)

/** Provisi akun PPPoE untuk sebuah langganan. */
export const provisionAccess = (body: ProvisionAccessRequest) =>
  api.post<SubscriberAccessView>('/api/bng/access', body)

/** Ganti paket dan/atau BRAS sebuah akun. */
export const updateAccess = (id: string, body: UpdateAccessRequest) =>
  api.put<SubscriberAccessView>(`/api/bng/access/${id}`, body)

/** Ganti password PPPoE sebuah akun. */
export const resetAccessSecret = (id: string, secret: string) =>
  api.post<SubscriberAccessView>(`/api/bng/access/${id}/reset-secret`, { secret })

/** Hapus akun PPPoE. */
export const deleteAccess = (id: string) => api.del<void>(`/api/bng/access/${id}`)

// ---- Kendali jaringan (jalur tulis ke BRAS) ----

/**
 * Isolir akun: potong akses (status jadi ISOLATED) sekaligus antre DISCONNECT agar
 * sesi yang masih hidup benar-benar terputus. Izin `bng.access.isolate`.
 */
export const isolateAccess = (id: string) =>
  api.post<SubscriberAccessView>(`/api/bng/access/${id}/isolate`, {})

/** Pulihkan akun dari isolir (kembali ACTIVE). Izin `bng.access.isolate`. */
export const restoreAccess = (id: string) =>
  api.post<SubscriberAccessView>(`/api/bng/access/${id}/restore`, {})

/**
 * Reset Login: putus sesi PPPoE tanpa mengubah status akun, agar CPE dial ulang.
 * Ditolak server (409) bila akun belum ditugaskan ke BRAS. Izin `bng.session.reset`.
 */
export const resetAccessLogin = (id: string) =>
  api.post<SubscriberAccessView>(`/api/bng/access/${id}/reset-login`, {})

// ---- Sesi & trafik (jalur baca; izin bng.session.view) ----

/** Keadaan sesi PPPoE terkini sebuah akun. */
export const getBrasSession = (accessId: string) =>
  api.get<BrasSessionView>(`/api/bng/access/${accessId}/session`)

/** Tren trafik Down/Up sebuah akun untuk [hours] jam terakhir (bawaan 24). */
export const getBrasTraffic = (accessId: string, hours = 24) =>
  api.get<TrafficHistoryView>(`/api/bng/access/${accessId}/traffic?hours=${hours}`)
