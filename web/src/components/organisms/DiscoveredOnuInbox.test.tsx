import { afterEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { assetIds as id, assetJobFixture, assetSourceFixture } from '@/test/customerAssetFixture'
import { DiscoveredOnuInbox } from './DiscoveredOnuInbox'

const mocks = vi.hoisted(() => ({ toast: { success: vi.fn(), error: vi.fn() }, confirm: vi.fn() }))
vi.mock('@/system', () => ({ useToast: () => mocks.toast, useConfirm: () => mocks.confirm }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => true }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'actor', tenantId: 'tenant' }, readOnly: false }) }))
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('opens suggested customer review without provisioning until an eligible serial is selected and explicitly confirmed', async () => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }; HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  const discovery = { id: id.document, serialNumber: 'ONU-A1', oltId: id.allocation, oltCode: 'OLT-1', ponPortLabel: 'PON1', lastStatus: 'ONLINE', lastRxPowerDbm: -22,
    firstSeenAt: '2026-09-25T01:00:00Z', lastSeenAt: '2026-09-25T01:00:00Z', seenCount: 3, state: 'DISCOVERED', suggestion: { confidence: 'HIGH', customerId: id.customer, customerName: 'Pelanggan Satu', reason: 'Pelanggan menunggu pemasangan', odpCode: null } }
  const response = (value: unknown) => new Response(JSON.stringify(value)), page = (items: unknown[]) => response({ items, page: 0, size: 25, totalElements: items.length })
  const fetch = vi.fn(async (path: string, input?: RequestInit) => {
    if (input?.method === 'POST') return path.endsWith('/authorize') ? response({ authorizationId: id.plan, operationId: id.assignment, revision: 0 }) : response({ ...discovery, state: 'PROVISIONED' })
    if (path.startsWith('/api/monitoring/discovered-onus?')) return response([discovery])
    if (path.startsWith('/api/odps?')) return response({ content: [], totalElements: 0 })
    if (path.includes('/jobs?')) return page([assetJobFixture()])
    if (path.includes('/sources?')) return page([assetSourceFixture()])
    if (path.includes('/sources/')) return response(assetSourceFixture())
    if (path.includes('/jobs/')) return response(assetJobFixture())
    throw new Error(path)
  }); vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><DiscoveredOnuInbox /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Aksi sel' }))
  fireEvent.click(await screen.findByRole('menuitem', { name: 'Terima' }))
  expect(fetch.mock.calls.filter(([, input]) => input?.method === 'POST')).toHaveLength(0)
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'WO pemasangan' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'WO pemasangan' }), { target: { value: id.source } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Perangkat yang sudah diterima' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Perangkat yang sudah diterima' }), { target: { value: id.piece } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Serial perangkat' }), { target: { value: 'ONU-A1' } }); fireEvent.keyDown(screen.getByRole('textbox', { name: 'Serial perangkat' }), { key: 'Enter' })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemasangan' }))
  const dialog = await screen.findByRole('dialog', { name: 'Konfirmasi provisi perangkat terdeteksi' })
  fireEvent.click(within(dialog).getByRole('button', { name: 'Pasang perangkat' }))
  await waitFor(() => expect(fetch.mock.calls.filter(([, input]) => input?.method === 'POST')).toHaveLength(2))
  const post = fetch.mock.calls.find(([path]) => path.endsWith('/provision'))!
  expect(JSON.parse(String(post[1]?.body))).toMatchObject({ customerId: id.customer, authorizationId: id.plan, expectedRevision: 0 })
})
