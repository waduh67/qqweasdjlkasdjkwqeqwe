import type { TicketView } from '@/api/helpdesk'

function duration(iso: string) {
  const minutes = Math.max(0, Math.round(Math.abs(new Date(iso).getTime() - Date.now()) / 60_000))
  if (minutes < 60) return `${minutes} mnt`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} jam${minutes % 60 ? ` ${minutes % 60} mnt` : ''}`
  return `${Math.floor(hours / 24)} hari`
}
export function ticketSla(ticket: TicketView): { label: string; overdue?: boolean } {
  if (ticket.status === 'CLOSED') return { label: '—' }
  if (ticket.responseOverdue && ticket.responseDueAt) return { label: `Respons terlambat ${duration(ticket.responseDueAt)}`, overdue: true }
  if (ticket.resolutionOverdue) return { label: `Penyelesaian terlambat ${duration(ticket.resolutionDueAt)}`, overdue: true }
  if (ticket.responseDueAt) return { label: `Respons dalam ${duration(ticket.responseDueAt)}` }
  if (ticket.resolvedAt) return { label: '—' }
  return { label: `Selesai dalam ${duration(ticket.resolutionDueAt)}` }
}
export const ticketTime = (iso: string) => new Date(iso).toLocaleString('id-ID', { dateStyle: 'medium', timeStyle: 'short' })
