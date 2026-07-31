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
  code: string
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
  assignedTo?: string | null
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
