import { api } from './client'

/**
 * Tipe & panggilan module bng (BRAS/RADIUS: paket layanan, registri BRAS, dan
 * identitas jaringan/akun PPPoE pelanggan). Cermin `BngController` di server.
 *
 * Slice fondasi: murni data — belum ada perintah nyata ke BRAS. Secret (password
 * PPPoE / CoA BRAS) tak pernah dibaca balik: hanya bisa diisi saat provisi atau
 * di-reset, dan kehadirannya di BRAS ditandai boolean `hasCoaSecret`.
 */

/** Vendor BRAS yang dikenal — menentukan adapter kontrol sesi nantinya. */
export type NasVendor = 'MIKROTIK' | 'CISCO' | 'JUNIPER' | 'FREERADIUS' | 'OTHER'

/** ACTIVE, ISOLATED, atau TERMINATED — mengekor status langganan. */
export type AccessStatus = 'ACTIVE' | 'ISOLATED' | 'TERMINATED'

/** Label manusiawi vendor BRAS untuk dropdown & tabel. */
export const NAS_VENDOR_LABEL: Record<NasVendor, string> = {
  MIKROTIK: 'MikroTik (RouterOS)',
  CISCO: 'Cisco',
  JUNIPER: 'Juniper',
  FREERADIUS: 'FreeRADIUS',
  OTHER: 'Lainnya',
}

/** Urutan vendor untuk pilihan pada form. */
export const NAS_VENDORS: NasVendor[] = ['MIKROTIK', 'CISCO', 'JUNIPER', 'FREERADIUS', 'OTHER']

/** Proyeksi satu paket layanan (rate profile). */
export interface RateProfileView {
  id: string
  name: string
  description: string | null
  downMbps: number
  upMbps: number
  /** Nama profil yang dikenal BRAS/RADIUS (profil PPP Mikrotik / grup FreeRADIUS). */
  radiusProfileName: string | null
}

/** Perubahan paket; dipakai untuk create maupun update. */
export interface SaveRateProfileRequest {
  name: string
  description?: string | null
  downMbps: number
  upMbps: number
  radiusProfileName?: string | null
}

/** Proyeksi satu BRAS. [hasCoaSecret] menandai secret CoA terisi tanpa membocorkannya. */
export interface NasView {
  id: string
  name: string
  vendor: NasVendor
  address: string | null
  nasIdentifier: string | null
  hasCoaSecret: boolean
  collectorId: string | null
  enabled: boolean
}

/** Perubahan BRAS; [coaSecret] kosong saat update = biarkan secret apa adanya. */
export interface SaveNasRequest {
  name: string
  vendor: NasVendor
  address?: string | null
  nasIdentifier?: string | null
  coaSecret?: string | null
  collectorId?: string | null
  enabled: boolean
}

/**
 * Proyeksi satu akun PPPoE. Password (secret) sengaja tak disertakan — hanya bisa
 * diisi saat provisi atau di-reset. [rateProfileName]/[nasName] sudah diresolusi
 * di server agar UI tak perlu memanggil balik.
 */
export interface SubscriberAccessView {
  id: string
  subscriptionId: string
  customerId: string
  username: string
  authType: string
  rateProfileId: string
  rateProfileName: string | null
  nasId: string | null
  nasName: string | null
  status: AccessStatus
}

/** Provisi akun PPPoE baru untuk sebuah langganan. */
export interface ProvisionAccessRequest {
  subscriptionId: string
  username: string
  secret: string
  rateProfileId: string
  nasId?: string | null
}

/** Ganti paket dan/atau BRAS sebuah akun. */
export interface UpdateAccessRequest {
  rateProfileId: string
  nasId?: string | null
}

// ---- Paket (rate profile) ----

/** Daftar paket layanan tenant. */
export const listPlans = () => api.get<RateProfileView[]>('/api/bng/plans')

/** Buat paket baru. */
export const createPlan = (body: SaveRateProfileRequest) => api.post<RateProfileView>('/api/bng/plans', body)

/** Ubah paket. */
export const updatePlan = (id: string, body: SaveRateProfileRequest) =>
  api.put<RateProfileView>(`/api/bng/plans/${id}`, body)

/** Hapus paket (ditolak server bila masih dipakai akun). */
export const deletePlan = (id: string) => api.del<void>(`/api/bng/plans/${id}`)

// ---- BRAS/NAS ----

/** Daftar BRAS tenant. */
export const listNas = () => api.get<NasView[]>('/api/bng/nas')

/** Daftarkan BRAS baru. */
export const createNas = (body: SaveNasRequest) => api.post<NasView>('/api/bng/nas', body)

/** Ubah BRAS; secret kosong = biarkan apa adanya. */
export const updateNas = (id: string, body: SaveNasRequest) => api.put<NasView>(`/api/bng/nas/${id}`, body)

/** Hapus BRAS (ditolak server bila masih menaungi akun). */
export const deleteNas = (id: string) => api.del<void>(`/api/bng/nas/${id}`)

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
