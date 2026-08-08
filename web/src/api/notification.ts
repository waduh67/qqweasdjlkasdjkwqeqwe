/** Broadcast pemberitahuan gangguan (module `notification`). */

import { api } from './client'

export type NotificationChannel = 'WHATSAPP' | 'SMS' | 'TELEGRAM'

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
  phone: string | null
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

export type WhatsAppProvider = 'LOG' | 'HTTP_GENERIC' | 'META_CLOUD'

export const PROVIDER_LABEL: Record<WhatsAppProvider, string> = {
  LOG: 'Catat ke log (mode uji)',
  HTTP_GENERIC: 'HTTP generik (Fonnte, Wablas, WAHA, dsb.)',
  META_CLOUD: 'Meta WhatsApp Cloud API',
}

/**
 * Setelan seperti dibaca dari server. Token TAK pernah dikembalikan — hanya penanda
 * sudah terisi (`httpTokenSet`/`metaAccessTokenSet`) agar rahasia tak bocor ke UI.
 */
export interface NotificationSettingsView {
  provider: WhatsAppProvider
  gatewayEnabled: boolean
  httpEndpointUrl: string | null
  httpTokenSet: boolean
  httpPhoneField: string
  httpMessageField: string
  metaPhoneNumberId: string | null
  metaAccessTokenSet: boolean
  metaWabaId: string | null
  /** Prasyarat kartu template terpenuhi (gateway hidup + Meta Cloud + kredensial tersimpan). */
  metaTemplateReady: boolean
  notifyOnSubscriptionLifecycle: boolean
  notifyOnInvoiceReminder: boolean
  notifyOnWorkOrderSchedule: boolean
  notifyOnIncidentOpen: boolean
}

/**
 * Perubahan setelan. Token (`httpToken`/`metaAccessToken`) null/kosong = biarkan yang
 * tersimpan apa adanya, jadi menyunting field lain tak menghapus rahasia.
 */
export interface UpdateNotificationSettingsRequest {
  provider: WhatsAppProvider
  gatewayEnabled: boolean
  httpEndpointUrl: string | null
  httpToken: string | null
  httpPhoneField: string | null
  httpMessageField: string | null
  metaPhoneNumberId: string | null
  metaAccessToken: string | null
  metaWabaId: string | null
  notifyOnSubscriptionLifecycle: boolean
  notifyOnInvoiceReminder: boolean
  notifyOnWorkOrderSchedule: boolean
  notifyOnIncidentOpen: boolean
}

export function getNotificationSettings(): Promise<NotificationSettingsView> {
  return api.get('/api/notifications/settings')
}

export function updateNotificationSettings(
  body: UpdateNotificationSettingsRequest,
): Promise<NotificationSettingsView> {
  return api.put('/api/notifications/settings', body)
}

/** ------------------------------------------------------------------
 *  Template pesan WhatsApp (Meta Cloud) + pemetaan pemicu → template.
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

/** Urutan tampil = urutan perjalanan pelanggan, bukan urutan enum. */
export const TRIGGERS: NotificationTrigger[] = [
  'SUBSCRIPTION_ACTIVATED',
  'SUBSCRIPTION_ISOLATED',
  'SUBSCRIPTION_TERMINATED',
  'INVOICE_DUE_SOON',
  'INVOICE_OVERDUE',
  'WORK_ORDER_SCHEDULED',
  'INCIDENT_OPENED',
]

export const TRIGGER_LABEL: Record<NotificationTrigger, string> = {
  SUBSCRIPTION_ACTIVATED: 'Langganan aktif',
  SUBSCRIPTION_ISOLATED: 'Langganan diisolir',
  SUBSCRIPTION_TERMINATED: 'Langganan dihentikan',
  INVOICE_DUE_SOON: 'Tagihan menjelang jatuh tempo',
  INVOICE_OVERDUE: 'Tagihan menunggak',
  WORK_ORDER_SCHEDULED: 'Kunjungan teknisi terjadwal',
  INCIDENT_OPENED: 'Gangguan dibuka',
}

export type TemplateStatus = 'APPROVED' | 'PENDING' | 'REJECTED' | 'PAUSED' | 'DISABLED' | 'UNKNOWN'
export type TemplateSource = 'MANUAL' | 'META'

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
  bodyPreview: string | null
  /** Jumlah `{{n}}` unik di body; server selalu mengirim tepat satu parameter. */
  bodyParamCount: number
  syncedAt: string | null
  usedBy: NotificationTrigger[]
}

/**
 * Isi kartu template. `manageable`/`syncable` menentukan aksi mana yang boleh ditawarkan;
 * `blockedReason` menjelaskan apa yang kurang bila terkunci.
 */
export interface TemplateCatalogView {
  templates: NotificationTemplateView[]
  /** Pemicu → id template; pemicu yang tak disebut = kirim teks biasa. */
  assignments: Partial<Record<NotificationTrigger, string>>
  manageable: boolean
  syncable: boolean
  blockedReason: string | null
}

export interface SaveTemplateRequest {
  name: string
  language: string | null
}

export interface SyncTemplatesResult {
  fetched: number
  imported: number
  updated: number
  skipped: number
  message: string
  catalog: TemplateCatalogView
}

export function getTemplates(): Promise<TemplateCatalogView> {
  return api.get('/api/notifications/templates')
}

export function createTemplate(body: SaveTemplateRequest): Promise<TemplateCatalogView> {
  return api.post('/api/notifications/templates', body)
}

export function updateTemplate(id: string, body: SaveTemplateRequest): Promise<TemplateCatalogView> {
  return api.put(`/api/notifications/templates/${id}`, body)
}

export function deleteTemplate(id: string): Promise<TemplateCatalogView> {
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
