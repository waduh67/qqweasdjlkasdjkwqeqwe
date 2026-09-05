/** Broadcast pemberitahuan gangguan (module `notification`). */

import { api } from './client'

/** Kanal yang benar-benar punya pengirim: WA lewat gateway tenant, email lewat SMTP platform. */
export type NotificationChannel = 'WHATSAPP' | 'EMAIL'

export const CHANNEL_LABEL: Record<NotificationChannel, string> = {
  WHATSAPP: 'WhatsApp',
  EMAIL: 'Email',
}

export interface BroadcastView {
  id: string
  incidentId: string | null
  channel: string
  message: string
  recipientCount: number
  sentCount: number
  skippedCount: number
  failedCount: number
  createdAt: string
}

export interface BroadcastRecipientView {
  customerId: string | null
  customerName: string
  /** Tujuan sesuai kanal siarannya: nomor WhatsApp atau alamat email. */
  destination: string | null
  status: string
  detail: string | null
  at: string
}

export interface BroadcastDetail {
  broadcast: BroadcastView
  recipients: BroadcastRecipientView[]
}

/** ------------------------------------------------------------------
 *  Setelan notifikasi tenant: gateway WA bawa-sendiri + saklar pemicu.
 *  ------------------------------------------------------------------ */

export type WhatsAppProvider = 'LOG' | 'HTTP_GENERIC' | 'FONNTE' | 'META_CLOUD' | 'QONTAK'

export const PROVIDER_LABEL: Record<WhatsAppProvider, string> = {
  LOG: 'Catat ke log (mode uji)',
  HTTP_GENERIC: 'HTTP generik (gateway pihak ketiga)',
  FONNTE: 'Fonnte',
  META_CLOUD: 'Meta WhatsApp Cloud API',
  QONTAK: 'Mekari Qontak (WhatsApp Business API)',
}

/**
 * Setelan seperti dibaca dari server. Token TAK pernah dikembalikan — hanya penanda
 * sudah terisi (`httpTokenSet`/`metaAccessTokenSet`/`qontakAccessTokenSet`) agar rahasia
 * tak bocor ke UI.
 */
export interface NotificationSettingsView {
  provider: WhatsAppProvider
  gatewayEnabled: boolean
  /** Saklar kanal email (SMTP platform), berdiri sendiri di luar gateway WA. */
  emailEnabled: boolean
  httpEndpointUrl: string | null
  httpTokenSet: boolean
  httpPhoneField: string
  httpMessageField: string
  metaPhoneNumberId: string | null
  metaAccessTokenSet: boolean
  metaWabaId: string | null
  qontakAccessTokenSet: boolean
  qontakChannelIntegrationId: string | null
  /** Prasyarat kartu template terpenuhi (gateway hidup + penyedia resmi + kredensial tersimpan). */
  templateReady: boolean
  /** Kalimat siap-tampil tentang apa yang kurang; null bila sudah siap. */
  templateBlockedReason: string | null
  notifyOnSubscriptionLifecycle: boolean
  notifyOnInvoiceReminder: boolean
  notifyOnWorkOrderSchedule: boolean
  notifyOnIncidentOpen: boolean
}

/**
 * Perubahan setelan. Token (`httpToken`/`metaAccessToken`/`qontakAccessToken`) null/kosong =
 * biarkan yang tersimpan apa adanya, jadi menyunting field lain tak menghapus rahasia.
 */
export interface UpdateNotificationSettingsRequest {
  provider: WhatsAppProvider
  gatewayEnabled: boolean
  emailEnabled: boolean
  httpEndpointUrl: string | null
  httpToken: string | null
  httpPhoneField: string | null
  httpMessageField: string | null
  metaPhoneNumberId: string | null
  metaAccessToken: string | null
  metaWabaId: string | null
  qontakAccessToken: string | null
  qontakChannelIntegrationId: string | null
  notifyOnSubscriptionLifecycle: boolean
  notifyOnInvoiceReminder: boolean
  notifyOnWorkOrderSchedule: boolean
  notifyOnIncidentOpen: boolean
}

/** Satu kanal WhatsApp di akun Qontak, untuk dropdown pemilihan channel. */
export interface QontakChannelView {
  id: string
  name: string
}

export function getNotificationSettings(): Promise<NotificationSettingsView> {
  return api.get('/api/notifications/settings')
}

export function updateNotificationSettings(
  body: UpdateNotificationSettingsRequest,
): Promise<NotificationSettingsView> {
  return api.put('/api/notifications/settings', body)
}

/** Kanal WA di akun Qontak — memakai token yang SUDAH tersimpan, bukan yang sedang diketik. */
export function getQontakChannels(): Promise<QontakChannelView[]> {
  return api.get('/api/notifications/settings/qontak/channels')
}

export interface WhatsAppTestRequest {
  readonly provider: 'FONNTE' | 'HTTP_GENERIC'
  readonly destination: string
  readonly message: string
  readonly httpToken: string | null
  readonly httpEndpointUrl: string | null
  readonly httpPhoneField: string | null
  readonly httpMessageField: string | null
}

export interface WhatsAppTestResultView {
  readonly delivered: boolean
  readonly detail: string
}

export function sendWhatsAppTest(body: WhatsAppTestRequest): Promise<WhatsAppTestResultView> {
  return api.post('/api/notifications/settings/whatsapp/test', body)
}

export function sendFonnteTest(destination: string): Promise<WhatsAppTestResultView> {
  return api.post('/api/notifications/settings/fonnte/test', { destination })
}

/** ------------------------------------------------------------------
 *  Template pesan WhatsApp (Meta Cloud / Mekari Qontak) + pemetaan
 *  pemicu → template. Katalog lokal adalah CERMIN dari penyedia:
 *  tambah/ubah/hapus di sini benar-benar memanggil API mereka.
 *  ------------------------------------------------------------------ */

/** Pemicu otomatis di backend. `MANUAL` sengaja tak ditawarkan di UI. */
export type NotificationTrigger =
  | 'SUBSCRIPTION_ACTIVATED'
  | 'SUBSCRIPTION_ISOLATED'
  | 'SUBSCRIPTION_TERMINATED'
  | 'INVOICE_DUE_SOON'
  | 'INVOICE_OVERDUE'
  | 'WORK_ORDER_SCHEDULED'
  | 'INCIDENT_OPENED'
  | 'PORTAL_PASSWORD_RESET'

/** Urutan tampil = urutan perjalanan pelanggan, bukan urutan enum. */
export const TRIGGERS: NotificationTrigger[] = [
  'SUBSCRIPTION_ACTIVATED',
  'SUBSCRIPTION_ISOLATED',
  'SUBSCRIPTION_TERMINATED',
  'INVOICE_DUE_SOON',
  'INVOICE_OVERDUE',
  'WORK_ORDER_SCHEDULED',
  'INCIDENT_OPENED',
  'PORTAL_PASSWORD_RESET',
]

export const TRIGGER_LABEL: Record<NotificationTrigger, string> = {
  SUBSCRIPTION_ACTIVATED: 'Langganan aktif',
  SUBSCRIPTION_ISOLATED: 'Langganan diisolir',
  SUBSCRIPTION_TERMINATED: 'Langganan dihentikan',
  INVOICE_DUE_SOON: 'Tagihan menjelang jatuh tempo',
  INVOICE_OVERDUE: 'Tagihan menunggak',
  WORK_ORDER_SCHEDULED: 'Kunjungan teknisi terjadwal',
  INCIDENT_OPENED: 'Gangguan dibuka',
  // Bukan pemberitahuan yang bisa dimatikan pelanggan: tanpa ini akun tak bisa dipulihkan.
  // Templatenya sebaiknya berkategori AUTHENTICATION (Meta memperlakukan OTP secara khusus).
  PORTAL_PASSWORD_RESET: 'Kode pemulihan password portal',
}

export type TemplateStatus = 'APPROVED' | 'PENDING' | 'REJECTED' | 'PAUSED' | 'DISABLED' | 'UNKNOWN'
export type TemplateSource = 'MANUAL' | 'REMOTE'

export type TemplateCategory = 'UTILITY' | 'MARKETING' | 'AUTHENTICATION'

export const TEMPLATE_CATEGORY_LABEL: Record<TemplateCategory, string> = {
  UTILITY: 'Utility — pesan transaksional (tagihan, jadwal, gangguan)',
  MARKETING: 'Marketing — promosi & penawaran',
  AUTHENTICATION: 'Authentication — kode OTP',
}

export const TEMPLATE_STATUS_LABEL: Record<TemplateStatus, string> = {
  APPROVED: 'Disetujui',
  PENDING: 'Menunggu tinjauan',
  REJECTED: 'Ditolak',
  PAUSED: 'Dijeda',
  DISABLED: 'Dinonaktifkan',
  UNKNOWN: 'Belum disinkron',
}

export interface NotificationTemplateView {
  id: string
  name: string
  language: string
  category: string
  status: TemplateStatus
  source: TemplateSource
  /** Teks komponen BODY yang diajukan ke penyedia. */
  bodyText: string | null
  /** Jumlah `{{n}}` unik di body; server selalu mengirim tepat satu parameter. */
  bodyParamCount: number
  readonly assignmentEligible: boolean
  readonly assignmentBlockedReason: string | null
  syncedAt: string | null
  usedBy: NotificationTrigger[]
}

/**
 * Isi kartu template. `manageable`/`syncable` menentukan aksi mana yang boleh ditawarkan;
 * `blockedReason` menjelaskan apa yang kurang bila terkunci. `canEdit`/`canDeleteRemotely`
 * mencerminkan kemampuan PENYEDIA aktif — Qontak tak punya API ubah maupun hapus.
 */
export interface TemplateCatalogView {
  templates: NotificationTemplateView[]
  /** Pemicu → id template; pemicu yang tak disebut = kirim teks biasa. */
  assignments: Partial<Record<NotificationTrigger, string>>
  manageable: boolean
  syncable: boolean
  blockedReason: string | null
  /** Nama penyedia aktif untuk teks UI, mis. "Meta Cloud". */
  providerLabel: string | null
  canEdit: boolean
  canDeleteRemotely: boolean
  /** Penyedia tak bisa kirim teks biasa → pemicu tanpa template akan dilewati (Qontak). */
  requiresTemplateForEveryTrigger: boolean
}

export interface CreateTemplateRequest {
  name: string
  language: string | null
  category: TemplateCategory
  bodyText: string
}

/** Suntingan: nama & bahasa terkunci di penyedia, jadi tak ikut dikirim. */
export interface EditTemplateRequest {
  category: TemplateCategory
  bodyText: string
}

export interface SyncTemplatesResult {
  fetched: number
  imported: number
  updated: number
  skipped: number
  /** Baris lokal yang tak lagi ada di penyedia; ditandai nonaktif, bukan dihapus. */
  missing: number
  message: string
  catalog: TemplateCatalogView
}

/** `removedRemotely` false = template MASIH ada di penyedia; `message` menjelaskannya. */
export interface DeleteTemplateResult {
  removedRemotely: boolean
  message: string
  catalog: TemplateCatalogView
}

export function getTemplates(): Promise<TemplateCatalogView> {
  return api.get('/api/notifications/templates')
}

export function createTemplate(body: CreateTemplateRequest): Promise<TemplateCatalogView> {
  return api.post('/api/notifications/templates', body)
}

export function updateTemplate(id: string, body: EditTemplateRequest): Promise<TemplateCatalogView> {
  return api.put(`/api/notifications/templates/${id}`, body)
}

export function deleteTemplate(id: string): Promise<DeleteTemplateResult> {
  return api.del(`/api/notifications/templates/${id}`)
}

/** Mengganti SELURUH peta: pemicu yang tak disebut kembali mengirim teks biasa. */
export function saveTemplateAssignments(
  assignments: Partial<Record<NotificationTrigger, string | null>>,
): Promise<TemplateCatalogView> {
  return api.put('/api/notifications/templates/assignments', { assignments })
}

export function syncTemplates(): Promise<SyncTemplatesResult> {
  return api.post('/api/notifications/templates/sync', {})
}
