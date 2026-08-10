import { api } from './client'

/**
 * Akun milik diri sendiri — 2FA. Endpointnya di bawah `/api/me`, tanpa izin apa pun:
 * mengamankan akun sendiri bukan wewenang yang perlu diberikan siapa-siapa.
 */

export interface TwoFactorStatus {
  enabled: boolean
  /** Rahasia sudah dibuat tapi belum dikonfirmasi — pendaftaran terhenti di tengah jalan. */
  pending: boolean
  recoveryCodesLeft: number
}

export interface TotpEnrollment {
  /** Base32 untuk diketik manual kalau kamera tak bisa memindai. */
  secret: string
  otpauthUri: string
}

export interface RecoveryCodes {
  codes: string[]
}

export const getTwoFactorStatus = () => api.get<TwoFactorStatus>('/api/me/2fa')

export const startTwoFactorSetup = () => api.post<TotpEnrollment>('/api/me/2fa/setup')

export const enableTwoFactor = (code: string) => api.post<RecoveryCodes>('/api/me/2fa/enable', { code })

export const disableTwoFactor = (password: string) => api.post<void>('/api/me/2fa/disable', { password })

export const regenerateRecoveryCodes = (password: string) =>
  api.post<RecoveryCodes>('/api/me/2fa/recovery-codes', { password })

/** Jalur admin: mengosongkan 2FA pengguna lain (ponsel hilang). Butuh `iam.user.update`. */
export const resetTwoFactorFor = (userId: string) => api.post<void>(`/api/users/${userId}/2fa/reset`)
