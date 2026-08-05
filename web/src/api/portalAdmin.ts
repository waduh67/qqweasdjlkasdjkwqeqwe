import { api } from './client'

/**
 * Tipe & panggilan sisi OPERATOR untuk mengelola kredensial portal pelanggan —
 * cermin `PortalCredentialAdminController` (`/api/portal-admin/...`). Ini realm operator
 * (token IAM biasa), TERPISAH dari portal pelanggan sendiri (`/api/portal/...`).
 * Dipakai kartu "Kredensial Portal" di halaman detail pelanggan.
 */

/** Status kredensial portal satu pelanggan. `provisioned=false` → belum pernah dibuatkan. */
export interface PortalCredentialStatus {
  provisioned: boolean
  customerId: string | null
  login: string | null
  /** Aktif = boleh login. Nonaktif = disetel, tapi login diblokir sementara. */
  active: boolean
}

/**
 * Hasil provisi/reset. `temporaryPassword` HANYA terisi saat server yang membangkitkan
 * password (operator mengosongkan field) — ditampilkan sekali lalu tak bisa dilihat lagi.
 */
export interface PortalCredentialProvisioned {
  customerId: string
  login: string
  active: boolean
  temporaryPassword: string | null
}

export const getPortalCredential = (customerId: string) =>
  api.get<PortalCredentialStatus>(`/api/portal-admin/customers/${customerId}/credential`)

/** Buat/aktifkan kredensial. Login & password opsional (server memberi default bila kosong). */
export const provisionPortalCredential = (
  customerId: string,
  body: { login?: string | null; password?: string | null },
) => api.post<PortalCredentialProvisioned>(`/api/portal-admin/customers/${customerId}/credential`, body)

/** Reset password. Kosongkan `newPassword` agar server membangkitkan yang acak. */
export const resetPortalPassword = (customerId: string, newPassword?: string | null) =>
  api.post<PortalCredentialProvisioned>(
    `/api/portal-admin/customers/${customerId}/credential/reset-password`,
    { newPassword: newPassword ?? null },
  )

export const enablePortalCredential = (customerId: string) =>
  api.post<PortalCredentialStatus>(`/api/portal-admin/customers/${customerId}/credential/enable`)

export const disablePortalCredential = (customerId: string) =>
  api.post<PortalCredentialStatus>(`/api/portal-admin/customers/${customerId}/credential/disable`)
