import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import type { IncidentDetail } from '@/api/incident'
import { IncidentDetailBlade } from './incidents/IncidentDetailBlade'

const auth = vi.hoisted(() => ({ permissions: ['incident.ticket.update', 'incident.ticket.close', 'notification.broadcast.send'], readOnly: false }))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'operator', platformAdmin: false, permissions: auth.permissions }, readOnly: auth.readOnly }) }))
vi.mock('@/system', () => ({ useToast: () => toast }))
const initial: IncidentDetail = {
  incident: { id: 'incident-1', key: 'onu-1', rootType: 'ONU', rootId: 'onu-1', rootLabel: 'Siti Lestari', severity: 'MAJOR', status: 'OPEN', title: 'Sinyal ONU Siti Lestari', alarmCount: 1, affectedCustomerCount: 1, suspectedCause: null, openedAt: '2026-10-17T03:00:00Z', lastSeenAt: '2026-10-17T04:00:00Z', acknowledgedAt: null, resolvedAt: null },
  members: [{ entityType: 'ONU', entityId: 'onu-1', kind: 'ONU_HIGH_RX', severity: 'MAJOR', label: 'Siti Lestari', downCause: null }],
  timeline: [{ type: 'OPENED', message: 'Insiden dibuka.', at: '2026-10-17T03:00:00Z' }],
}
let detail: IncidentDetail
beforeEach(() => {
  vi.restoreAllMocks(); auth.permissions = ['incident.ticket.update', 'incident.ticket.close', 'notification.broadcast.send']; auth.readOnly = false
  detail = structuredClone(initial)
  vi.spyOn(api, 'get').mockImplementation(async () => detail)
  vi.spyOn(api, 'post').mockImplementation(async path => {
    detail = { ...detail, incident: { ...detail.incident, status: path.endsWith('/resolve') ? 'RESOLVED' : 'ACKNOWLEDGED' } }
    return detail.incident
  })
})
function open() {
  const close = vi.fn(), changed = vi.fn()
  render(<IncidentDetailBlade id="incident-1" onClose={close} onChanged={changed} />)
  return { user: userEvent.setup(), close, changed }
}
it('acknowledges and resolves without closing the detail, then shows activity', async () => {
  const { user, close, changed } = open()
  await user.click(await screen.findByRole('button', { name: 'Akui insiden' }))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Akui insiden' })).toBeNull())
  await user.click(screen.getByRole('button', { name: 'Tutup insiden' }))
  await screen.findByText('Selesai')
  expect(screen.queryByRole('button', { name: 'Tutup insiden' })).toBeNull()
  expect(close).not.toHaveBeenCalled(); expect(changed).toHaveBeenCalledTimes(2)
  await user.click(screen.getByRole('tab', { name: 'Aktivitas' }))
  expect(await screen.findByText('Insiden dibuka.')).toBeTruthy()
  expect(api.post).toHaveBeenCalledWith('/api/incidents/incident-1/resolve')
})
it('retries a failed read and preserves detail when a status update fails', async () => {
  vi.mocked(api.get).mockRejectedValueOnce(new ApiError(503, 'Detail belum tersedia.'))
  vi.mocked(api.post).mockRejectedValueOnce(new ApiError(503, 'Coba lagi nanti.'))
  const { user } = open()
  await user.click(await screen.findByRole('button', { name: 'Coba lagi' }))
  await user.click(await screen.findByRole('button', { name: 'Akui insiden' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Coba lagi nanti.')
  expect(screen.getByText('Terbuka')).toBeTruthy()
})
it('reviews a nested message before sending and returns to the same incident', async () => {
  vi.mocked(api.post).mockResolvedValue({ sentCount: 1, skippedCount: 0 })
  const { user, close } = open()
  await user.click(await screen.findByRole('button', { name: 'Beritahu pelanggan' }))
  const form = within(screen.getByRole('dialog', { name: 'Beritahu pelanggan' }))
  await user.clear(form.getByRole('textbox', { name: 'Pesan' }))
  await user.type(form.getByRole('textbox', { name: 'Pesan' }), 'Tim teknis sedang memeriksa jaringan.')
  await user.click(form.getByRole('tab', { name: 'Tinjau + kirim' }))
  expect(api.post).not.toHaveBeenCalled()
  await user.click(form.getByRole('button', { name: 'Kirim pemberitahuan' }))
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Beritahu pelanggan' })).toBeNull())
  expect(api.post).toHaveBeenCalledWith('/api/notifications/broadcasts', { incidentId: 'incident-1', channel: 'WHATSAPP', message: 'Tim teknis sedang memeriksa jaringan.' })
  expect(screen.getByRole('dialog', { name: initial.incident.title })).toBeTruthy(); expect(close).not.toHaveBeenCalled()
})
it('respects permissions and the subscription read-only state', async () => {
  auth.permissions = []
  const { user } = open()
  await screen.findByText('Sinyal terima terlalu kuat')
  expect(screen.queryByRole('button', { name: 'Akui insiden' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Beritahu pelanggan' })).toBeNull()
  auth.permissions = ['incident.ticket.update']; auth.readOnly = true
  await user.click(screen.getByRole('button', { name: 'Segarkan' }))
  await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2))
  expect(screen.queryByRole('button', { name: 'Akui insiden' })).toBeNull()
  expect(api.post).not.toHaveBeenCalled()
})
