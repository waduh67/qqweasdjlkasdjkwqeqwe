import { api } from './client'
import type { ServiceType } from './catalog'

/**
 * PSB ekspres — satu panggilan onboarding merangkai pendaftaran pelanggan + langganan +
 * akun jaringan + WO PSB dalam satu transaksi (cermin `OnboardingController`). Langganan &
 * akun lahir PENDING: BELUM ditulis ke RADIUS sampai WO PSB dituntaskan teknisi.
 *
 * Kredensial ([username]/[secret]) boleh dikosongkan — server meng-generate-nya. Karena secret
 * tak pernah dibalikkan API, operator memakai password yang ia isi/generate di sisi klien.
 */

/** Titik lokasi pelanggan; longitude dulu (urutan GeoJSON/PostGIS). */
export interface LocationPayload {
  longitude: number
  latitude: number
}

export interface ExpressPsbRequest {
  // Pelanggan
  /** Kosong/undefined = server membuat kode berurut otomatis (CUST-000001). */
  code?: string
  name: string
  phone?: string | null
  email?: string | null
  address: string
  location: LocationPayload
  areaId?: string | null
  // Langganan
  planId: string
  monthlyFeeOverride?: number | null
  // Akun jaringan
  username?: string | null
  secret?: string | null
  serviceType?: ServiceType | null
  nasId?: string | null
  framedIp?: string | null
  // Work order PSB
  title?: string | null
  description?: string | null
  scheduledAt?: string | null
  /** Roster teknisi awal WO PSB (tim datar); kosong = belum ditugaskan. */
  assignees?: string[]
}

/**
 * Hasil PSB ekspres: id semua entitas yang terbentuk + kode WO. [username] final (server bisa
 * meng-generate). TAK memuat secret — operator memakai password yang ia isi/generate di klien.
 */
export interface ExpressPsbResult {
  customerId: string
  subscriptionId: string
  accessId: string
  username: string
  workOrderId: string
  workOrderCode: string
}

/** Onboarding PSB ekspres (satu POST, satu transaksi). */
export const onboardPsb = (body: ExpressPsbRequest) =>
  api.post<ExpressPsbResult>('/api/onboarding/psb', body)

// ---- Bulk-import PPPoE (migrasi akun RouterOS → sistem) ----

/**
 * Bulk-import PPPoE: migrasi akun `/ppp/secret` sebuah RouterOS jadi pelanggan+langganan+akun
 * yang langsung AKTIF dan terprovisi ke RADIUS pusat (cermin `OnboardingController.importPppoe`).
 * Beda dari PSB: tanpa Work Order (pelanggan sudah terpasang). Per-baris atomik — satu baris
 * gagal tak menggagalkan batch; hasilnya per-baris.
 */

/** Sumber baris: `NAS` = server menarik dari router; `INLINE` = baris dari paste/upload operator. */
export type ImportSource = 'NAS' | 'INLINE'

/** Satu baris impor INLINE (dari paste/upload). [name] = username PPPoE; sisanya opsional. */
export interface ImportRowPayload {
  name: string
  password?: string | null
  profile?: string | null
  comment?: string | null
  disabled?: boolean
}

export interface ImportPppoeRequest {
  nasId: string
  source: ImportSource
  /** Wajib untuk INLINE; diabaikan untuk NAS (server menarik sendiri). */
  rows?: ImportRowPayload[]
  /** Peta profil RouterOS → id paket katalog. */
  profilePlanId?: Record<string, string>
  /** Paket fallback bila profil tak terpetakan; null → baris ber-profil tak dikenal dilewati. */
  defaultPlanId?: string | null
  /** Lewati akun yang dimatikan di router (default true di server). */
  skipDisabled?: boolean
  /** Batasi ke username terpilih; kosong/absen = semua baris. */
  onlyNames?: string[] | null
  /** Data pelanggan yang tak ada di `/ppp/secret` (placeholder, diperkaya belakangan). */
  areaId?: string | null
  defaultAddress?: string | null
  defaultLocation?: LocationPayload | null
}

export type ImportRowStatus = 'CREATED' | 'SKIPPED' | 'FAILED'

/** Nasib satu baris: dibuat, dilewati (sudah ada / profil tak dipetakan), atau gagal. */
export interface ImportRowOutcome {
  username: string
  status: ImportRowStatus
  message: string | null
}

/** Rekap hasil impor + rincian per-baris. */
export interface ImportPppoeResult {
  created: number
  skipped: number
  failed: number
  rows: ImportRowOutcome[]
}

/** Jalankan bulk-import PPPoE (satu POST → rekap per-baris). */
export const importPppoe = (body: ImportPppoeRequest) =>
  api.post<ImportPppoeResult>('/api/onboarding/import/pppoe', body)

// ---- Impor/ekspor CSV pelanggan (generik, upsert menurut mikrotik_username) ----

/**
 * Impor CSV pelanggan generik: satu berkas berisi biodata + langganan + akun jaringan per baris,
 * di-UPSERT menurut `mikrotik_username` (cermin `OnboardingController.importCustomers`). Berbeda
 * dari impor PPPoE yang menyedot `/ppp/secret`: sumbernya berkas CSV yang dirakit operator dan
 * bersifat upsert. Baris sudah diurai KLIEN jadi bentuk terstruktur sebelum dikirim.
 */

/**
 * Urutan kolom kanonis CSV pelanggan — identik header ekspor server (`CustomerCsv.HEADER`).
 * Dipakai membangkitkan template unduhan; parser impor memetakan lewat NAMA kolom, jadi urutan
 * di berkas yang diunggah bebas selama nama header cocok.
 */
export const CUSTOMER_CSV_COLUMNS = [
  'name', 'phone', 'address', 'package_name', 'connection_type', 'installation_date',
  'mikrotik_username', 'mikrotik_password', 'email', 'router_name', 'framed_ip', 'id_card_number',
  'next_billing', 'latitude', 'longitude', 'notes',
] as const

/**
 * Satu baris impor CSV. [mikrotikUsername] = kunci upsert (kosong → server melewati baris; untuk
 * tipe DHCP/Static isinya MAC). [connectionType] `pppoe`/`hotspot`/`dhcp`/`static` (kosong → pppoe;
 * tak dikenal → baris gagal). [framedIp] reservasi Framed-IP untuk Static (wajib)/DHCP (opsional).
 * [installationDate] ISO `YYYY-MM-DD`. Kolom lain opsional: pada jalur update yang kosong
 * dipertahankan; [mikrotikPassword] kosong = pertahankan password lama.
 */
export interface CustomerCsvRow {
  name?: string | null
  phone?: string | null
  address?: string | null
  packageName?: string | null
  connectionType?: string | null
  installationDate?: string | null
  mikrotikUsername?: string | null
  mikrotikPassword?: string | null
  email?: string | null
  routerName?: string | null
  framedIp?: string | null
  idCardNumber?: string | null
  nextBillingDay?: number | null
  latitude?: number | null
  longitude?: number | null
}

export interface ImportCustomersRequest {
  rows: CustomerCsvRow[]
}

export type CustomerImportStatus = 'CREATED' | 'UPDATED' | 'SKIPPED' | 'FAILED'

/** Nasib satu baris: dibuat, diperbarui, dilewati (username kosong/sudah diimpor), atau gagal. */
export interface CustomerImportOutcome {
  username: string
  status: CustomerImportStatus
  message: string | null
}

/** Rekap hasil impor CSV + rincian per-baris. */
export interface ImportCustomersResult {
  created: number
  updated: number
  skipped: number
  failed: number
  rows: CustomerImportOutcome[]
}

/** Jalankan impor CSV pelanggan (satu POST → rekap per-baris). */
export const importCustomers = (body: ImportCustomersRequest) =>
  api.post<ImportCustomersResult>('/api/onboarding/import/customers', body)

/**
 * Ekspor CSV pelanggan sebagai Blob (byte ter-gate → diambil dengan header Bearer, bukan `<a href>`).
 * Pemanggil menjadikannya unduhan berkas (object URL). Header kolomnya cocok template impor.
 */
export const exportCustomersCsv = () => api.blob('/api/onboarding/export/customers')
