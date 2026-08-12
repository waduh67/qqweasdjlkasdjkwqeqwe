/**
 * Setelan email dua tingkat: bawaan PLATFORM (SMTP + identitas + tampilan + subjek) yang
 * bisa ditimpa tiap TENANT (nama pengirim + alamat balasan + tampilan + subjek — tanpa SMTP
 * dan tanpa alamat pengirim, karena relay platform hanya menerima pengirim terverifikasi).
 *
 * Dua sisi dijadikan satu modul karena bentuknya cermin dan dipakai berpasangan di layar
 * tenant: kartu tenant menampilkan nilai warisan platform sebagai placeholder, jadi tipe
 * keduanya memang selalu berjalan bersama.
 */

import { api } from './client'

/** Pemicu yang punya baris subjek sendiri; sama persis dengan enum `NotificationTrigger`. */
export type EmailTrigger =
  | 'MANUAL'
  | 'SUBSCRIPTION_ACTIVATED'
  | 'SUBSCRIPTION_ISOLATED'
  | 'SUBSCRIPTION_TERMINATED'
  | 'INVOICE_DUE_SOON'
  | 'INVOICE_OVERDUE'
  | 'WORK_ORDER_SCHEDULED'
  | 'INCIDENT_OPENED'
  | 'PORTAL_PASSWORD_RESET'
  | 'TENANT_SIGNED_UP'

/**
 * Pemicu yang subjeknya milik platform: server mengabaikan timpaan tenant untuk keduanya dan
 * memang tak pernah mengirim barisnya ke layar tenant. Didaftar di sini hanya agar layar
 * platform bisa menerangkannya — bukan sebagai penyaring.
 */
export const PLATFORM_ONLY_TRIGGERS: readonly EmailTrigger[] = ['PORTAL_PASSWORD_RESET', 'TENANT_SIGNED_UP']

/** Label operator untuk tiap pemicu — istilah yang dikenal, bukan nama konstanta. */
export const EMAIL_TRIGGER_LABEL: Record<EmailTrigger, string> = {
  MANUAL: 'Siaran manual',
  SUBSCRIPTION_ACTIVATED: 'Layanan diaktifkan',
  SUBSCRIPTION_ISOLATED: 'Layanan diisolir',
  SUBSCRIPTION_TERMINATED: 'Layanan dihentikan',
  INVOICE_DUE_SOON: 'Tagihan akan jatuh tempo',
  INVOICE_OVERDUE: 'Tagihan lewat jatuh tempo',
  WORK_ORDER_SCHEDULED: 'Jadwal kunjungan teknisi',
  INCIDENT_OPENED: 'Gangguan layanan dibuka',
  PORTAL_PASSWORD_RESET: 'Pemulihan password portal',
  TENANT_SIGNED_UP: 'Pendaftaran ISP baru',
}

/**
 * Satu baris subjek. `subject` = timpaan yang benar-benar tersimpan di tingkat ini (null =
 * tak menimpa); `inheritedSubject` = yang terpakai bila dibiarkan kosong, dipasang sebagai
 * placeholder supaya jelas mana yang disetel sendiri dan mana yang diwarisi.
 */
export interface EmailSubjectView {
  trigger: EmailTrigger
  subject: string | null
  inheritedSubject: string
}

/** Hasil kirim uji apa adanya; `detail` dari transport, ditampilkan mentah untuk diagnosa. */
export interface EmailTestResult {
  delivered: boolean
  detail: string
}

/**
 * Setelan platform. Password SMTP write-only: yang keluar hanya `smtpPasswordSet`.
 * `smtpConfigured` false berarti pengiriman memakai `spring.mail.*` dari env — bukan mati.
 */
export interface PlatformEmailSettingsView {
  smtpHost: string | null
  smtpPort: number
  smtpUsername: string | null
  smtpPasswordSet: boolean
  smtpAuth: boolean
  smtpStartTls: boolean
  smtpConfigured: boolean
  fromAddress: string | null
  fromName: string
  logoSet: boolean
  logoUrl: string | null
  accentColor: string | null
  footerText: string | null
  signatureText: string | null
  publicBaseUrl: string | null
  subjects: EmailSubjectView[]
}

/** `smtpPassword` kosong/absen = biarkan yang tersimpan. `subjects` mengganti SELURUH timpaan. */
export interface UpdatePlatformEmailSettingsRequest {
  smtpHost: string | null
  smtpPort: number
  smtpUsername: string | null
  smtpPassword: string | null
  smtpAuth: boolean
  smtpStartTls: boolean
  fromAddress: string | null
  fromName: string | null
  accentColor: string | null
  footerText: string | null
  signatureText: string | null
  publicBaseUrl: string | null
  subjects: Partial<Record<EmailTrigger, string>>
}

/** Timpaan tenant beserta nilai warisannya (untuk placeholder). */
export interface TenantEmailSettingsView {
  replyToAddress: string | null
  fromName: string | null
  logoSet: boolean
  accentColor: string | null
  footerText: string | null
  signatureText: string | null
  /** Alamat `From` yang berlaku — TERKUNCI, bukan warisan yang bisa ditimpa. */
  platformFromAddress: string | null
  inheritedFromName: string
  effectiveLogoUrl: string | null
  inheritedAccentColor: string | null
  inheritedFooterText: string | null
  inheritedSignatureText: string | null
  subjects: EmailSubjectView[]
}

/** Semua field opsional: null/kosong = hapus timpaan dan warisi platform lagi. */
export interface UpdateTenantEmailSettingsRequest {
  replyToAddress: string | null
  fromName: string | null
  accentColor: string | null
  footerText: string | null
  signatureText: string | null
  subjects: Partial<Record<EmailTrigger, string>>
}

const PLATFORM = '/api/platform/email-settings'
const TENANT = '/api/notifications/email-settings'

/** Path byte logo ter-gate — untuk pola `AuthedImage` (api.blob + createObjectURL). */
export const PLATFORM_EMAIL_LOGO_PATH = `${PLATFORM}/logo`
export const TENANT_EMAIL_LOGO_PATH = `${TENANT}/logo`

export function getPlatformEmailSettings(): Promise<PlatformEmailSettingsView> {
  return api.get(PLATFORM)
}

export function updatePlatformEmailSettings(
  body: UpdatePlatformEmailSettingsRequest,
): Promise<PlatformEmailSettingsView> {
  return api.put(PLATFORM, body)
}

export function uploadPlatformEmailLogo(file: File): Promise<PlatformEmailSettingsView> {
  const form = new FormData()
  form.append('file', file)
  return api.postForm(PLATFORM_EMAIL_LOGO_PATH, form)
}

export function deletePlatformEmailLogo(): Promise<PlatformEmailSettingsView> {
  return api.del(PLATFORM_EMAIL_LOGO_PATH)
}

export function sendPlatformTestEmail(to: string): Promise<EmailTestResult> {
  return api.post(`${PLATFORM}/test`, { to })
}

export function getTenantEmailSettings(): Promise<TenantEmailSettingsView> {
  return api.get(TENANT)
}

export function updateTenantEmailSettings(
  body: UpdateTenantEmailSettingsRequest,
): Promise<TenantEmailSettingsView> {
  return api.put(TENANT, body)
}

export function uploadTenantEmailLogo(file: File): Promise<TenantEmailSettingsView> {
  const form = new FormData()
  form.append('file', file)
  return api.postForm(TENANT_EMAIL_LOGO_PATH, form)
}

export function deleteTenantEmailLogo(): Promise<TenantEmailSettingsView> {
  return api.del(TENANT_EMAIL_LOGO_PATH)
}

export function sendTenantTestEmail(to: string): Promise<EmailTestResult> {
  return api.post(`${TENANT}/test`, { to })
}

/**
 * Pratinjau dibalas server sebagai `text/html`, bukan JSON — jadi ia diambil lewat `api.blob`
 * lalu dibaca sebagai teks untuk ditaruh di `<iframe srcDoc>`. Sengaja dirender jalur yang
 * sama dengan email sungguhan: pratinjau yang dirakit ulang di klien cepat atau lambat
 * berbohong soal apa yang benar-benar diterima pelanggan.
 */
export async function previewPlatformEmail(): Promise<string> {
  return (await api.blob(`${PLATFORM}/preview`)).text()
}

export async function previewTenantEmail(): Promise<string> {
  return (await api.blob(`${TENANT}/preview`)).text()
}

/** Daftar subjek dari view → peta siap kirim; yang kosong dibuang (= kembali ke warisan). */
export function subjectsToPayload(rows: EmailSubjectView[]): Partial<Record<EmailTrigger, string>> {
  const out: Partial<Record<EmailTrigger, string>> = {}
  for (const row of rows) {
    const value = (row.subject ?? '').trim()
    if (value) out[row.trigger] = value
  }
  return out
}
