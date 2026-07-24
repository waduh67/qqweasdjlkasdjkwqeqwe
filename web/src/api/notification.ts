/** Broadcast pemberitahuan gangguan (module `notification`). */

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
