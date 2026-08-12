import { api } from './client'

/**
 * Tipe & panggilan KONSOL ACS (halaman `/acs`) — pandangan se-armada, terpisah dari
 * `cpe.ts` yang melayani panel satu pelanggan. Cermin `acsviews.kt` di server.
 *
 * Dua izin yang berbeda dengan sengaja: `cpe.acs.view` cukup untuk info server &
 * health (nilai env global, nol data tenant — teknisi memegang ini); segala yang
 * menyentuh armada tenant butuh `cpe.device.view`.
 */

/** Setelan TR-069 yang harus diketik operator/teknisi ke halaman ACS di ONT. */
export interface AcsServerInfoView {
  /** Alamat NBI yang dipakai APLIKASI (internal) — keterangan, bukan yang diketik ke ONT. */
  nbiBaseUrl: string
  /** URL CWMP yang DIHUBUNGI PERANGKAT; null bila FTTH_CPE_PUBLIC_HOST belum diisi. */
  cwmpUrl: string | null
  acsUsername: string | null
  acsPassword: string | null
  connectionRequestUsername: string | null
  connectionRequestPassword: string | null
  periodicInformEnabled: boolean
  /** Bawaan pabrik ONT umumnya 3600 — nilai ini yang benar. */
  periodicInformIntervalSeconds: number
  syncIntervalSeconds: number
  configured: boolean
}

/** Hasil probe kesehatan ACS; [message] sudah dibersihkan server dari URI internal. */
export interface AcsHealthView {
  status: 'ONLINE' | 'OFFLINE'
  latencyMs: number | null
  checkedAt: string
  message: string
}

/** Ringkasan armada tenant — seluruhnya dari proyeksi ber-RLS, tak pernah dari ACS. */
export interface AcsStatsView {
  totalDevices: number
  onlineDevices: number
  offlineDevices: number
  avgRxPowerDbm: number | null
  /** Penyebut rata-rata sinyal; ditampilkan agar angkanya tak terbaca se-armada. */
  signalSampleCount: number
  lastSyncAt: string | null
  lastSyncOk: boolean | null
}

/** Satu baris tabel device; tiap kolom dari sumber terbaiknya (ACS, OLT, bng). */
export interface AcsDeviceRowView {
  id: string
  serialNumber: string
  customerId: string | null
  customerName: string | null
  manufacturer: string | null
  model: string | null
  softwareVersion: string | null
  online: boolean
  lastInformAt: string | null
  ipAddress: string | null
  ssid: string | null
  pppoeUsername: string | null
  pppoeOnline: boolean | null
  rxPowerDbm: number | null
  txPowerDbm: number | null
  /** Parameter vendor — null di hampir semua armada sampai path suhunya dikonfigurasi. */
  temperatureC: number | null
}

/** Satu baris jejak aksi lintas device. */
export interface AcsActivityView {
  id: string
  deviceId: string
  serialNumber: string | null
  customerName: string | null
  action: string
  status: 'SUCCESS' | 'FAILED'
  detail: string | null
  requestedByEmail: string | null
  requestedAt: string
}

/**
 * Hasil "Segarkan Batch" — sapuan BERPLAFON, bukan "semua". [skipped] dikembalikan
 * jujur supaya operator tahu fiturnya berplafon, bukan rusak.
 */
export interface AcsBulkRefreshView {
  candidates: number
  attempted: number
  connected: number
  queued: number
  failed: number
  skipped: number
  message: string
}

export type AcsStatusFilter = 'ALL' | 'ONLINE' | 'OFFLINE'
export type AcsSignalFilter = 'ALL' | 'GOOD' | 'WARN' | 'CRITICAL' | 'UNKNOWN'

/** Saringan tabel; nilai kosong dibuang agar querystring-nya tetap ringkas. */
export interface AcsDeviceQuery {
  q?: string
  status?: AcsStatusFilter
  signal?: AcsSignalFilter
  brand?: string
}

function queryString(filter: AcsDeviceQuery): string {
  const params = new URLSearchParams()
  if (filter.q?.trim()) params.set('q', filter.q.trim())
  if (filter.status && filter.status !== 'ALL') params.set('status', filter.status)
  if (filter.signal && filter.signal !== 'ALL') params.set('signal', filter.signal)
  if (filter.brand) params.set('brand', filter.brand)
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

/** Setelan TR-069 untuk ONT (nilai global dari env deploy). */
export const getAcsServerInfo = () => api.get<AcsServerInfoView>('/api/cpe/acs/server')

/** Probe kesehatan ACS; server memoisasi hasilnya sepuluh detik. */
export const getAcsHealth = () => api.get<AcsHealthView>('/api/cpe/acs/health')

/** Ringkasan armada; saringan yang sama dengan tabel agar dua angka tak berselisih. */
export const getAcsStats = (filter: AcsDeviceQuery = {}) =>
  api.get<AcsStatsView>(`/api/cpe/acs/stats${queryString(filter)}`)

/** Tabel seluruh CPE tenant. */
export const listAcsDevices = (filter: AcsDeviceQuery = {}) =>
  api.get<AcsDeviceRowView[]>(`/api/cpe/acs/devices${queryString(filter)}`)

/** Ekspor CSV tabel yang SEDANG tersaring — tanpa kredensial apa pun. */
export const exportAcsDevicesCsv = (filter: AcsDeviceQuery = {}) =>
  api.blob(`/api/cpe/acs/devices.csv${queryString(filter)}`)

/** Jejak aksi ACS terbaru lintas perangkat. */
export const listAcsLogs = (limit = 100) =>
  api.get<AcsActivityView[]>(`/api/cpe/acs/logs?limit=${limit}`)

/** Sapuan connection request berplafon ke perangkat online. */
export const refreshAcsFleet = () => api.post<AcsBulkRefreshView>('/api/cpe/acs/refresh-all')
