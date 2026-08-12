import { api } from './client'

/**
 * Payload pendaftaran mandiri ISP — cermin `SignupRequest` di server. Tanpa `slug`: kode ISP
 * dipilih server dari nama dan dijamin unik, jadi pendaftar tak bisa (dan tak perlu) menebaknya.
 */
export interface SignupPayload {
  name: string
  adminName: string
  adminEmail: string
  adminPassword: string
}

/** Balasan sukses `POST /api/signup`. */
export interface SignupResult {
  /** Kode ISP yang di-assign server. Wajib ditampilkan — diminta setiap kali staf masuk. */
  slug: string
  name: string
  adminEmail: string
  message: string
}

/**
 * Daftarkan ISP baru. Endpoint publik (tanpa token) — `api.post` tak memasang
 * Authorization saat belum login, jadi aman dipanggil dari layar pendaftaran.
 */
export function signupTenant(payload: SignupPayload): Promise<SignupResult> {
  return api.post<SignupResult>('/api/signup', payload)
}
