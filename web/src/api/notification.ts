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
  metaTemplateName: string | null
  metaTemplateLang: string
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
  metaTemplateName: string | null
  metaTemplateLang: string | null
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
