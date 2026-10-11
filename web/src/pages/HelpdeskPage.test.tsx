import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import * as api from '@/api/helpdesk'
import { ApiError } from '@/api/client'
import { HelpdeskPage } from './HelpdeskPage'

const auth = vi.hoisted(() => ({ permissions: ['helpdesk.ticket.view', 'helpdesk.ticket.manage', 'helpdesk.ticket.reply'] }))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'operator-1', platformAdmin: false, permissions: auth.permissions }, readOnly: false }) }))
vi.mock('@/hooks/useStaff', () => ({ useStaff: () => [{ id: 'operator-1', name: 'Rina' }] }))
vi.mock('@/system', () => ({ useToast: () => toast }))
const ticket: api.TicketView = { id: 'ticket-1', code: 'HD-001', customerId: 'customer-1', customerName: 'Budi', category: 'KONEKSI_PUTUS',
  subject: 'Internet terputus', status: 'OPEN', priority: 'HIGH', assigneeId: null, assigneeName: null, workOrderId: null, workOrderCode: null,
  openedAt: '2026-10-11T09:00:00Z', lastActivityAt: '2026-10-11T10:00:00Z', firstResponseAt: null, responseDueAt: '2026-10-11T11:00:00Z',
  resolutionDueAt: '2026-10-12T09:00:00Z', responseOverdue: true, resolutionOverdue: false, resolvedAt: null, closedAt: null }
const detail: api.TicketDetail = { ticket, description: 'Lampu LOS menyala sejak pagi.', messages: [] }
const permissions = [...auth.permissions]
beforeEach(() => {
  vi.restoreAllMocks(); auth.permissions = [...permissions]
  vi.spyOn(api, 'listTickets').mockResolvedValue({ content: [ticket], page: 0, size: 25, totalElements: 60, totalPages: 3 })
  vi.spyOn(api, 'getTicketSummary').mockResolvedValue({ open: 6, inProgress: 4, resolved: 2, unassigned: 3, overdue: 1 })
  vi.spyOn(api, 'getTicket').mockResolvedValue(detail)
  vi.spyOn(api, 'replyTicket').mockResolvedValue({ ...detail, messages: [{ author: 'OPERATOR', authorName: 'Rina', body: 'Kami periksa jaringannya.', at: '2026-10-11T12:00:00Z' }] })
  vi.spyOn(api, 'escalateTicket').mockResolvedValue({ ...detail, ticket: { ...ticket, workOrderId: 'wo-1', workOrderCode: 'WO-001' } })
})
function Location() { const location = useLocation(); return <output data-testid="location">{location.pathname + location.search}</output> }
function open() { render(<MemoryRouter initialEntries={['/helpdesk?keep=1']}><HelpdeskPage /><Location /></MemoryRouter>); return userEvent.setup() }
async function ticketPanel(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'HD-001' }))
  return screen.findByRole('dialog', { name: 'HD-001' })
}
it('filters all tickets on the server, resets pagination and retains filters across tabs', async () => {
  const user = open()
  await screen.findByRole('button', { name: 'HD-001' })
  await user.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await waitFor(() => expect(api.listTickets).toHaveBeenLastCalledWith(expect.anything(), 25, 1))
  await user.click(screen.getByRole('button', { name: 'Filter Status' }))
  await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'OPEN')
  await waitFor(() => expect(api.listTickets).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OPEN' }), 25, 0))
  await user.click(screen.getByRole('tab', { name: 'Ringkasan' }))
  await user.click(screen.getByRole('tab', { name: 'Tiket' }))
  expect(screen.getByRole('button', { name: 'Filter Status' }).textContent).toContain('Baru')
  await user.click(screen.getByRole('button', { name: 'Hapus Status' }))
  await waitFor(() => expect(api.listTickets).toHaveBeenLastCalledWith(expect.objectContaining({ status: '' }), 25, 0))
})
it('retains the reply after a failed send and guards it when closing the ticket', async () => {
  vi.mocked(api.replyTicket).mockRejectedValueOnce(new ApiError(503, 'Layanan sementara tidak tersedia.'))
  const user = open(), panel = within(await ticketPanel(user))
  await user.type(panel.getByRole('textbox', { name: 'Balasan' }), 'Kami periksa jaringannya.')
  await user.click(panel.getByRole('button', { name: 'Kirim balasan' }))
  expect(await panel.findByRole('alert')).toHaveProperty('textContent', 'Layanan sementara tidak tersedia.')
  expect(panel.getByRole('textbox', { name: 'Balasan' })).toHaveProperty('value', 'Kami periksa jaringannya.')
  await user.click(panel.getByRole('button', { name: 'Tutup' }))
  await user.click(await screen.findByRole('button', { name: 'Lanjutkan pengisian' }))
  await user.click(panel.getByRole('button', { name: 'Kirim balasan' }))
  await waitFor(() => expect(panel.getByRole('textbox', { name: 'Balasan' })).toHaveProperty('value', ''))
  expect(panel.getByRole('list', { name: 'Percakapan tiket' }).textContent).toContain('Kami periksa jaringannya.')
  await user.click(panel.getByRole('button', { name: 'Tutup' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(screen.getByTestId('location').textContent).toBe('/helpdesk?keep=1')
})
it('reviews a nested work order, preserves a rejected draft and only closes it after success', async () => {
  vi.mocked(api.escalateTicket).mockRejectedValueOnce(new ApiError(503, 'Coba lagi.'))
  const user = open(), parent = within(await ticketPanel(user))
  await user.click(parent.getByRole('button', { name: 'Buat work order' }))
  const form = within(await screen.findByRole('dialog', { name: 'Buat work order' }))
  expect(form.getByRole('combobox', { name: 'Prioritas' })).toHaveProperty('value', 'HIGH')
  await user.type(form.getByRole('textbox', { name: 'Catatan untuk teknisi' }), 'Periksa kabel drop.')
  await user.click(form.getByRole('tab', { name: 'Tinjau + buat' }))
  expect(api.escalateTicket).not.toHaveBeenCalled()
  await user.click(form.getByRole('button', { name: 'Buat work order' }))
  await form.findByRole('alert')
  await user.click(form.getByRole('button', { name: 'Sebelumnya' }))
  expect(form.getByRole('textbox', { name: 'Catatan untuk teknisi' })).toHaveProperty('value', 'Periksa kabel drop.')
  await user.click(form.getByRole('tab', { name: 'Tinjau + buat' }))
  await user.click(form.getByRole('button', { name: 'Buat work order' }))
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Buat work order' })).toBeNull())
  expect(api.escalateTicket).toHaveBeenLastCalledWith('ticket-1', 'HIGH', 'Periksa kabel drop.')
  await user.click(parent.getByRole('tab', { name: 'Detail' }))
  expect(parent.getByRole('link', { name: 'WO-001' }).getAttribute('href')).toBe('/work-orders/wo-1')
})
it('keeps read-only tickets readable without write actions and reports list errors', async () => {
  auth.permissions = ['helpdesk.ticket.view']
  vi.mocked(api.listTickets).mockRejectedValueOnce(new ApiError(503, 'failure'))
  const user = open()
  await screen.findByText('Daftar tiket belum berhasil dimuat.')
  expect(screen.queryByText('Belum ada tiket')).toBeNull()
  await user.click(screen.getByRole('button', { name: 'Coba lagi' }))
  const panel = within(await ticketPanel(user))
  expect(panel.queryByRole('button', { name: 'Buat work order' })).toBeNull()
  expect(panel.queryByRole('textbox', { name: 'Balasan' })).toBeNull()
  await user.click(panel.getByRole('tab', { name: 'Detail' }))
  expect(panel.queryByRole('combobox')).toBeNull()
})
it('offers legal transitions for resolved tickets and confirms permanent closure', async () => {
  vi.mocked(api.getTicket).mockResolvedValue({ ...detail, ticket: { ...ticket, status: 'RESOLVED' } })
  const status = vi.spyOn(api, 'changeTicketStatus').mockResolvedValue({ ...detail, ticket: { ...ticket, status: 'CLOSED' } })
  const user = open(), panel = within(await ticketPanel(user))
  expect(panel.queryByRole('button', { name: 'Tandai ditangani' })).toBeNull()
  expect(panel.getByRole('button', { name: 'Kembalikan ke antrean' })).toBeTruthy()
  await user.type(panel.getByRole('textbox', { name: 'Balasan' }), 'Catatan belum dikirim.')
  await user.click(panel.getByRole('button', { name: 'Tutup tiket' }))
  let confirmation = within(await screen.findByRole('dialog', { name: 'Tutup tiket?' }))
  expect(confirmation.getByText('Balasan yang belum dikirim akan dibuang.')).toBeTruthy()
  expect(status).not.toHaveBeenCalled()
  await user.click(confirmation.getByRole('button', { name: 'Batal' }))
  expect(panel.getByRole('textbox', { name: 'Balasan' })).toHaveProperty('value', 'Catatan belum dikirim.')
  await user.click(panel.getByRole('button', { name: 'Tutup tiket' }))
  confirmation = within(await screen.findByRole('dialog', { name: 'Tutup tiket?' }))
  await user.click(confirmation.getByRole('button', { name: 'Tutup tiket' }))
  await panel.findByText('Tiket sudah ditutup. Balasan tidak tersedia.')
  expect(status).toHaveBeenCalledWith('ticket-1', 'CLOSED')
  expect(panel.queryByRole('textbox', { name: 'Balasan' })).toBeNull()
})
