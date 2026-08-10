import { api } from './client'
import type { PageResponse } from './types'

/**
 * Meja bantuan sisi OPERATOR (module `helpdesk`) — antrean keluhan yang dilaporkan
 * pelanggan sendiri dari portal. Pintu pelanggannya ada di realm portal
 * (`portal/portalApi.ts`), memakai token & klien HTTP yang berbeda.
 */

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
export type TicketCategory = 'KONEKSI_PUTUS' | 'KONEKSI_LAMBAT' | 'PERANGKAT' | 'TAGIHAN' | 'LAINNYA'
export type TicketAuthor = 'CUSTOMER' | 'OPERATOR' | 'SYSTEM'
export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

export interface TicketView {
  id: string
  code: string
  customerId: string
  customerName: string
  category: TicketCategory
  subject: string
  status: TicketStatus
  priority: TicketPriority
  /** Operator pemegang tiket; null = masih di antrean bersama. */
  assigneeId: string | null
  assigneeName: string | null
  /** Work order hasil eskalasi; null selama keluhan belum butuh kunjungan teknisi. */
  workOrderId: string | null
  workOrderCode: string | null
  openedAt: string
  lastActivityAt: string
  /** Balasan tim yang pertama — bahan laporan, bukan penanda SLA berjalan. */
  firstResponseAt: string | null
  /** Tenggat balasan yang sedang ditunggu pelanggan; null = bola tak di tangan tim. */
  responseDueAt: string | null
  resolutionDueAt: string
  /**
   * Sudah lewat tenggat, DIHITUNG SERVER. Sengaja tidak dibandingkan ulang dengan
   * `Date.now()` di sini: jam browser operator bisa meleset dan "lewat SLA" harus
   * sama persis dengan yang dipakai laporan.
   */
  responseOverdue: boolean
  resolutionOverdue: boolean
  resolvedAt: string | null
  closedAt: string | null
}

export interface TicketMessageView {
  author: TicketAuthor
  authorName: string
  body: string
  at: string
}

/** Tiket + laporan awal pelanggan + seluruh utas percakapannya. */
export interface TicketDetail {
  ticket: TicketView
  description: string
  messages: TicketMessageView[]
}

export interface TicketSummaryView {
  open: number
  inProgress: number
  resolved: number
  /** Tiket hidup yang belum dipegang siapa pun. */
  unassigned: number
  /** Tiket hidup yang salah satu tenggat SLA-nya sudah lewat. */
  overdue: number
}

export interface TicketFilter {
  query?: string
  status?: TicketStatus | ''
  category?: TicketCategory | ''
  customerId?: string
  assigneeId?: string
  unassigned?: boolean
  overdue?: boolean
}

export const TICKET_STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: 'Baru',
  IN_PROGRESS: 'Ditangani',
  RESOLVED: 'Menunggu konfirmasi',
  CLOSED: 'Selesai',
}

export const TICKET_PRIORITY_LABEL: Record<TicketPriority, string> = {
  LOW: 'Rendah',
  NORMAL: 'Normal',
  HIGH: 'Tinggi',
  URGENT: 'Mendesak',
}

export const TICKET_CATEGORY_LABEL: Record<TicketCategory, string> = {
  KONEKSI_PUTUS: 'Koneksi putus',
  KONEKSI_LAMBAT: 'Koneksi lambat',
  PERANGKAT: 'Perangkat',
  TAGIHAN: 'Tagihan',
  LAINNYA: 'Lainnya',
}

export function listTickets(filter: TicketFilter = {}, size = 100): Promise<PageResponse<TicketView>> {
  const params = new URLSearchParams({ size: String(size) })
  if (filter.query?.trim()) params.set('query', filter.query.trim())
  if (filter.status) params.set('status', filter.status)
  if (filter.category) params.set('category', filter.category)
  if (filter.customerId) params.set('customerId', filter.customerId)
  if (filter.assigneeId) params.set('assigneeId', filter.assigneeId)
  if (filter.unassigned) params.set('unassigned', 'true')
  if (filter.overdue) params.set('overdue', 'true')
  return api.get<PageResponse<TicketView>>(`/api/helpdesk/tickets?${params}`)
}

export const getTicketSummary = () => api.get<TicketSummaryView>('/api/helpdesk/tickets/summary')

export const getTicket = (id: string) => api.get<TicketDetail>(`/api/helpdesk/tickets/${id}`)

export const replyTicket = (id: string, body: string) =>
  api.post<TicketDetail>(`/api/helpdesk/tickets/${id}/replies`, { body })

export const changeTicketStatus = (id: string, status: TicketStatus) =>
  api.post<TicketDetail>(`/api/helpdesk/tickets/${id}/status`, { status })

/** `userId` null mengembalikan tiket ke antrean bersama. */
export const assignTicket = (id: string, userId: string | null) =>
  api.post<TicketDetail>(`/api/helpdesk/tickets/${id}/assignee`, { userId })

/** Menggeser kedua tenggat SLA tiket sekaligus. */
export const changeTicketPriority = (id: string, priority: TicketPriority) =>
  api.post<TicketDetail>(`/api/helpdesk/tickets/${id}/priority`, { priority })

/** Terbitkan work order REPAIR dari keluhan ini; satu tiket hanya boleh sekali. */
export const escalateTicket = (id: string, priority: TicketPriority, note?: string) =>
  api.post<TicketDetail>(`/api/helpdesk/tickets/${id}/escalate`, { priority, note: note?.trim() || null })
