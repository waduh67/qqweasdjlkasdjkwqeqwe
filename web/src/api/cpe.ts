import { api } from './client'

/**
 * Tipe & panggilan module cpe (router/ONT pelanggan lewat GenieACS).
 *
 * Daftar & detail dibaca dari proyeksi tersimpan (cepat); keadaan langsung
 * (`live` — WiFi & host) memanggil ACS, jadi ditarik terpisah hanya saat panel
 * dibuka. Cermin `CpeController` di server.
 */

/** Proyeksi satu CPE beserta status online yang dihitung dari inform terakhir. */
export interface CpeDeviceView {
  id: string
  genieacsId: string
  serialNumber: string
  customerId: string | null
  onuId: string | null
  oui: string | null
  productClass: string | null
  manufacturer: string | null
  model: string | null
  softwareVersion: string | null
  ipAddress: string | null
  lastInformAt: string | null
  /** Dihitung server dari [lastInformAt] terhadap ambang basi saat query. */
  online: boolean
}

/** Detail satu device untuk halaman, dengan jejak aksi terakhir. */
export interface CpeDeviceDetail {
  device: CpeDeviceView
  recentActions: CpeActionView[]
}

/** Keadaan langsung dari ACS — tak tersimpan, dibaca saat panel dibuka. */
export interface CpeLiveView {
  wifi: WifiView[]
  hosts: HostView[]
}

export interface WifiView {
  /** Penunjuk jaringan untuk perintah balik; tak ditampilkan ke pengguna. */
  ref: string
  ssid: string
  /** Null bila firmware menyembunyikan kunci — UI menampilkan "tersembunyi". */
  passphrase: string | null
  band: string | null
  enabled: boolean
}

export interface HostView {
  hostName: string | null
  ipAddress: string | null
  macAddress: string | null
  active: boolean
}

/** REBOOT, SET_WIFI, PING_TEST, atau SPEED_TEST. */
export type CpeAction = 'REBOOT' | 'SET_WIFI' | 'PING_TEST' | 'SPEED_TEST'
/** SUCCESS atau FAILED. */
export type CpeActionStatus = 'SUCCESS' | 'FAILED'
/** Arah uji kecepatan TR-143. */
export type SpeedDirection = 'DOWNLOAD' | 'UPLOAD'

/** Satu baris jejak audit aksi ke perangkat. */
export interface CpeActionView {
  id: string
  action: CpeAction
  status: CpeActionStatus
  detail: string | null
  requestedBy: string
  requestedByEmail: string | null
  requestedAt: string
}

/** Label manusiawi untuk jenis aksi di jejak audit. */
export const CPE_ACTION_LABEL: Record<CpeAction, string> = {
  REBOOT: 'Reboot',
  SET_WIFI: 'Ubah WiFi',
  PING_TEST: 'Ping test',
  SPEED_TEST: 'Uji kecepatan',
}

/** Perubahan WiFi; field null/kosong berarti "biarkan apa adanya". */
export interface SetWifiRequest {
  ref: string
  ssid?: string | null
  passphrase?: string | null
}

/**
 * Hasil ping diagnostik — tak tersimpan, dikembalikan langsung. [ok] menandai
 * diagnostik tuntas & metriknya terbaca; bila false, [message] menjelaskan sebabnya.
 */
export interface PingDiagnosticView {
  ok: boolean
  host: string
  /** `DiagnosticsState` perangkat, mis. "Complete" atau kode error. */
  state: string
  successCount: number | null
  failureCount: number | null
  averageResponseMs: number | null
  minimumResponseMs: number | null
  maximumResponseMs: number | null
  message: string
}

/** Hasil uji kecepatan TR-143; [throughputMbps] dihitung server dari byte/waktu. */
export interface SpeedTestDiagnosticView {
  ok: boolean
  direction: SpeedDirection
  state: string
  throughputMbps: number | null
  testBytes: number | null
  durationMs: number | null
  message: string
}

/** CPE milik satu pelanggan (0..n; biasanya satu). */
export const listCpeDevices = (customerId: string) =>
  api.get<CpeDeviceView[]>(`/api/cpe/devices?customerId=${customerId}`)

/** Detail satu device beserta riwayat aksi terakhir. */
export const getCpeDevice = (id: string) => api.get<CpeDeviceDetail>(`/api/cpe/devices/${id}`)

/** Keadaan langsung dari ACS: WiFi & host tersambung. */
export const getCpeLive = (id: string) => api.get<CpeLiveView>(`/api/cpe/devices/${id}/live`)

/** Jadwalkan reboot; hasilnya tercatat di jejak audit. */
export const rebootCpe = (id: string) => api.post<CpeActionView>(`/api/cpe/devices/${id}/reboot`)

/** Ubah SSID dan/atau password satu jaringan WiFi. */
export const setCpeWifi = (id: string, body: SetWifiRequest) =>
  api.post<CpeActionView>(`/api/cpe/devices/${id}/wifi`, body)

/** Jalankan ping diagnostik; [host] kosong berarti pakai sasaran bawaan server. */
export const runCpePing = (id: string, host?: string) =>
  api.post<PingDiagnosticView>(`/api/cpe/devices/${id}/diagnostics/ping`, host ? { host } : {})

/** Jalankan uji kecepatan TR-143 pada arah unduh/unggah. */
export const runCpeSpeedTest = (id: string, direction: SpeedDirection) =>
  api.post<SpeedTestDiagnosticView>(`/api/cpe/devices/${id}/diagnostics/speedtest?direction=${direction}`)
