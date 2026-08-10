import { api } from './client'

/**
 * Kotak masuk operator (module `inbox`) — lonceng di pojok konsol.
 *
 * Isinya milik pengguna yang sedang login; server yang memutuskan apa yang boleh dilihat
 * dari izin di token, jadi tak ada satu pun parameter "punya siapa" di sini.
 */

export type NotificationKind = 'HELPDESK_SLA' | 'INCIDENT_OPENED' | 'WORK_ORDER_ASSIGNED'
export type NotificationSeverity = 'INFO' | 'WARNING' | 'CRITICAL'

export interface InboxNotification {
  id: string
  kind: NotificationKind
  severity: NotificationSeverity
  title: string
  body: string
  /** Rute konsol tujuan; null bila pemberitahuan ini tak menunjuk halaman tertentu. */
  link: string | null
  createdAt: string
  /** Null = belum dibaca. */
  readAt: string | null
}

export interface InboxFeed {
  unread: number
  items: InboxNotification[]
}

export const getInboxFeed = (limit = 20) => api.get<InboxFeed>(`/api/inbox/notifications?limit=${limit}`)

/** Hanya angka lencana — dipanggil berkala selagi konsol terbuka. */
export const getInboxUnreadCount = () => api.get<{ unread: number }>('/api/inbox/notifications/unread-count')

export const markInboxRead = (ids: string[]) => api.post<{ marked: number }>('/api/inbox/notifications/read', { ids })

export const markAllInboxRead = () => api.post<{ marked: number }>('/api/inbox/notifications/read-all', {})
