import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { assetIds as id, assetHistoryFixture, assetJobFixture, assetSourceFixture, assetWorkspaceFixture } from '@/test/customerAssetFixture'
import { CustomerAssetPanel } from './CustomerAssetPanel'

const access = vi.hoisted(() => ({ permissions: new Set<string>(), readOnly: false }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (p: string) => access.permissions.has(p) }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'actor', tenantId: 'tenant' }, readOnly: access.readOnly }) }))
const response = (value: unknown) => new Response(JSON.stringify(value))
const page = (items: unknown[], size = 25) => response({ items, page: 0, size, totalElements: items.length })
beforeEach(() => {
  access.permissions = new Set(['customer.onu.view', 'customer.onu.assign', 'workorder.order.field', 'workorder.order.view']); access.readOnly = false
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }; HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); tokenStore.clear() })
function transport(options: { history?: ReturnType<typeof assetHistoryFixture>[]; stale?: boolean; legacy?: number; lost?: boolean; serial?: string; dismantle?: boolean } = {}) {
  let installed = false, removed = false, lost = options.lost
  const source = assetSourceFixture()
  if (options.serial) source.source.serial = options.serial
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      if (path.endsWith('/authorize')) return response({ authorizationId: id.plan, operationId: id.assignment, revision: 0 })
      if (path.endsWith('/install')) { if (lost) { lost = false; throw new TypeError('response lost') }; installed = true; return response({ assignmentId: id.assignment, episodeId: id.assignment, customerId: id.customer, assetId: id.piece }) }
      if (path.endsWith('/handover')) return response({ assignmentId: id.assignment, customerId: id.customer, revision: 1, handoverState: 'ACCEPTED' })
      if (path.endsWith('/remove')) {
        removed = true
        return response({ operationId: id.document, retired: { assignmentId: id.assignment, episodeId: id.assignment,
          customerId: id.customer, assetId: id.piece, retiredAt: '2026-09-25T10:00:00Z' }, replacement: null })
      }
      throw new Error(`unexpected write ${path}`)
    }
    if (path.startsWith('/api/odps?')) return response({ content: [], totalElements: 0 })
    if (path.endsWith('/workbench')) return response({ ...assetWorkspaceFixture(), unresolvedDevices: options.legacy ?? 0 })
    if (path.includes('/history?')) return page(removed ? [{ ...assetHistoryFixture(), asset: { ...assetHistoryFixture().asset, endedAt: '2026-09-25T10:00:00Z' } }] : installed ? [assetHistoryFixture()] : options.history ?? [], 10)
    if (path.includes('/history/')) return response((options.history ?? [assetHistoryFixture()])[0])
    if (path.includes('/jobs?')) return page([{ ...assetJobFixture(), ...(options.dismantle ? { workType: 'DISMANTLE' } : {}) }])
    if (path.includes('/sources?')) return page([source])
    if (path.includes('/sources/')) return options.stale ? new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'Source moved' }), { status: 404 }) : response(source)
    if (path.includes('/jobs/')) return response({ ...assetJobFixture(), ...(options.dismantle ? { workType: 'DISMANTLE' } : {}) })
    throw new Error(`unexpected read ${path}`)
  }); vi.stubGlobal('fetch', fetch); return fetch
}
const mount = () => render(<MemoryRouter><CustomerAssetPanel customerId={id.customer} onChanged={vi.fn()} /></MemoryRouter>)
async function selectSource(serial = 'ONU-A1') {
  fireEvent.click(await screen.findByRole('button', { name: 'Pasang perangkat dari gudang' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'WO pemasangan' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'WO pemasangan' }), { target: { value: id.source } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Perangkat yang sudah diterima' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Perangkat yang sudah diterima' }), { target: { value: id.piece } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Serial perangkat' }), { target: { value: serial } })
  fireEvent.keyDown(screen.getByRole('textbox', { name: 'Serial perangkat' }), { key: 'Enter' })
}
it.each(['ONU-A1', 'Onu-A1'])('installs reviewed serial %s and retries a lost reply using the exact same command', async (serial) => {
  const fetch = transport({ lost: true, serial }); mount(); await selectSource()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemasangan' }))
  const dialog = await screen.findByRole('dialog'); expect(dialog.textContent).toContain(serial)
  fireEvent.click(within(dialog).getByRole('button', { name: 'Pasang perangkat' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await screen.findByText(/Asal: RCV-01/)
  const installs = fetch.mock.calls.filter(([path]) => path.endsWith('/install')); expect(installs).toHaveLength(2); expect(installs[0]).toEqual(installs[1])
  expect(screen.queryByPlaceholderText(/Serial ONU baru/)).toBeNull()
})
it('blocks mismatched manual serial and revalidates moved stock before allowing confirmation', async () => {
  const fetch = transport({ stale: true }); mount(); await selectSource('WRONG')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemasangan' })); await screen.findByText('Pilih perangkat yang sudah diterima dan cocokkan serial fisiknya.')
  fireEvent.change(screen.getByRole('textbox', { name: 'Serial perangkat' }), { target: { value: 'ONU-A1' } }); fireEvent.keyDown(screen.getByRole('textbox', { name: 'Serial perangkat' }), { key: 'Enter' })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemasangan' })); await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.includes(`/sources/${id.piece}`))).toBe(true))
  expect(screen.queryByRole('dialog')).toBeNull(); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
})
it('shows customer title history and legacy reconciliation without granting assignment actions to read-only viewers', async () => {
  const row = assetHistoryFixture(); row.asset.ownershipMode = 'SALE'; row.asset.legalOwner = 'CUSTOMER'; row.asset.handoverState = 'ACCEPTED'; row.asset.recoveryRequired = false
  access.permissions = new Set(['customer.onu.view', 'inventory.provenance.manage']); const fetch = transport({ history: [row], legacy: 2 }); mount()
  await screen.findByText('Milik pelanggan'); expect(screen.getByText(/2 perangkat lama/)).toBeDefined(); expect(screen.getByRole('link', { name: 'Rekonsiliasi perangkat lama' })).toBeDefined()
  expect(screen.queryByRole('button', { name: 'Ganti perangkat' })).toBeNull(); expect(fetch.mock.calls.every(([path]) => !path.includes('/jobs'))).toBe(true)
})
it('accepts customer handover with the displayed signature and actual assignment revisions', async () => {
  const row = assetHistoryFixture(); row.asset.revision = 6; row.asset.titleRevision = 2
  const fetch = transport({ history: [row] }); mount(); fireEvent.click(await screen.findByRole('button', { name: 'Terima serah-terima pelanggan' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'WO tindakan perangkat' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'WO tindakan perangkat' }), { target: { value: id.source } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan' }))
  const dialog = await screen.findByRole('dialog'); expect(dialog.textContent).toContain('Pelanggan Satu')
  fireEvent.click(within(dialog).getByRole('button', { name: 'Terima serah-terima pelanggan' }))
  await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.endsWith('/handover'))).toBe(true))
  const call = fetch.mock.calls.find(([path]) => path.endsWith('/handover'))!; expect(JSON.parse(String(call[1]?.body))).toEqual({ assignmentId: id.assignment, expectedRevision: 6, expectedTitleRevision: 2, evidenceId: id.evidence })
})

it('closes a completed removal dialog and reloads retired history from the real response envelope', async () => {
  const fetch = transport({ history: [assetHistoryFixture()], dismantle: true }); mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Lepas perangkat fisik' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'WO tindakan perangkat' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'WO tindakan perangkat' }), { target: { value: id.source } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan' }))
  const dialog = await screen.findByRole('dialog')
  fireEvent.click(within(dialog).getByRole('button', { name: 'Lepas perangkat fisik' }))
  await screen.findByText('Sudah dilepas')
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.queryByRole('button', { name: 'Lepas perangkat fisik' })).toBeNull()
  expect(fetch.mock.calls.filter(([path]) => path.endsWith('/remove'))).toHaveLength(1)
})

it('retains offline draft edits and prevents account read-only state from submitting stock mutations', async () => {
  const fetch = transport(); const view = mount(); await selectSource()
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false); fireEvent(window, new Event('offline'))
  expect(screen.getByRole('button', { name: 'Tinjau pemasangan' })).toHaveProperty('disabled', true)
  fireEvent.change(screen.getByRole('combobox', { name: 'Kepemilikan perangkat' }), { target: { value: 'SALE' } })
  expect(screen.getByRole('combobox', { name: 'Kepemilikan perangkat' })).toHaveProperty('value', 'SALE')
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  view.unmount(); access.readOnly = true; vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true); mount()
  expect(await screen.findByRole('button', { name: 'Pasang perangkat dari gudang' })).toHaveProperty('disabled', true)
})
