import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import type { AssetExceptionContext } from '@/api/warehouse/customerAssetExceptions'
import type { AssetHistory } from '@/api/warehouse/customerAssets'
import { assetIds as id, assetHistoryFixture, assetJobFixture, assetWorkspaceFixture } from '@/test/customerAssetFixture'
import { countLocation } from '@/test/warehouseCountFixture'
import { CustomerAssetPanel } from './CustomerAssetPanel'

const access = vi.hoisted(() => ({ permissions: new Set<string>(), readOnly: false }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (p: string) => access.permissions.has(p) }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'actor', tenantId: 'tenant' }, readOnly: access.readOnly }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const page = (items: unknown[], size = 10) => response({ items, page: 0, size, totalElements: items.length })
const accepted = () => { const row = assetHistoryFixture(); row.asset.handoverState = 'ACCEPTED'; row.asset.revision = 1; return row }
const contextFor = (row = accepted()): AssetExceptionContext => ({ ownership: { assignmentId: row.asset.id, assetId: row.asset.assetId, customerId: row.asset.customerId, workOrderId: row.asset.workOrderId,
  ownershipMode: row.asset.ownershipMode, legalOwner: row.asset.legalOwner, assignmentRevision: row.asset.revision, titleRevision: row.asset.titleRevision, handoverId: id.allocation },
  customerLabel: 'Pelanggan Satu', workOrder: { id: id.source, code: 'WO-PSB-01', revision: 7, signature: assetJobFixture().signature }, canRequestTitleCorrection: true, canRequestLoss: true })
const lostLocation = { ...countLocation, id: id.inspection, name: 'Perangkat hilang', code: 'LOST-01', kind: 'LOST', issueEligible: false }
beforeEach(() => {
  access.permissions = new Set(['customer.onu.view', 'inventory.approval.request', 'inventory.approval.view', 'inventory.custody.manage', 'workorder.evidence.view', 'inventory.location.view'])
  access.readOnly = false
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); tokenStore.clear() })
function transport(options: { row?: AssetHistory; context?: AssetExceptionContext; stale?: boolean; lost?: boolean; denied?: boolean; noEvidenceAccess?: boolean } = {}) {
  const row = options.row ?? accepted(), context = options.context ?? contextFor(row)
  let reads = 0, lost = options.lost
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      if (options.denied) return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409)
      if (lost) { lost = false; throw new TypeError('response lost') }
      if (path.endsWith('/asset-title-corrections')) return response({ documentId: id.document, assignmentId: row.asset.id, sourceTitleRevision: row.asset.titleRevision, targetOwner: row.asset.legalOwner === 'ISP' ? 'CUSTOMER' : 'ISP' }, 201)
      if (path.endsWith('/asset-losses')) return response({ id: id.document, code: 'LOST-ASSET-01', revision: 0, state: 'DRAFT', assignmentId: row.asset.id, sourceHandoverId: context.ownership.handoverId,
        stockIdentityId: row.asset.assetId, destinationLocationId: lostLocation.id, quantityBase: '1', baseUnit: 'EA', evidenceId: id.evidence }, 201)
      throw new Error('Unexpected write')
    }
    if (path.endsWith('/workbench')) return response(assetWorkspaceFixture())
    if (path.includes('/history?')) return page([row])
    if (path.endsWith('/exceptions/context')) {
      reads++
      if (options.noEvidenceAccess) return response({ code: 'NOT_FOUND', message: 'NOT_FOUND' }, 404)
      return response({ ...context, workOrder: { ...context.workOrder, revision: context.workOrder.revision + (options.stale && reads > 1 ? 1 : 0) } })
    }
    if (path.includes('/locations?')) return page([lostLocation], 25)
    if (path.endsWith(`/locations/${lostLocation.id}`)) return response(lostLocation)
    throw new Error(`Unexpected read ${path}`)
  })
  vi.stubGlobal('fetch', fetch); return fetch
}
const mount = () => render(<MemoryRouter><CustomerAssetPanel customerId={id.customer} onChanged={vi.fn()} /></MemoryRouter>)
async function open(kind: 'title' | 'loss' = 'title') {
  fireEvent.click(await screen.findByRole('button', { name: kind === 'title' ? 'Ajukan koreksi kepemilikan' : 'Ajukan kehilangan perangkat' }))
  await screen.findByText(/Tanda tangan Pelanggan Satu/)
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan pengajuan' }), { target: { value: 'Berita acara dan persetujuan pelanggan' } })
}
it('lets an office requester review named ownership context and retry the same sealed proposal after response loss', async () => {
  const fetch = transport({ lost: true }); mount(); await open()
  expect(screen.queryByRole('button', { name: 'Pasang perangkat dari gudang' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengajuan' }))
  const dialog = await screen.findByRole('dialog'); expect(dialog.textContent).toContain('ONU-A1'); expect(dialog.textContent).toContain('WO-PSB-01')
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(within(dialog).getByRole('button', { name: 'Catat pengajuan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  expect(await screen.findByRole('link', { name: 'Lanjutkan ke persetujuan' })).toHaveProperty('href', expect.stringContaining(`sourceDocumentId=${id.document}`))
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1])
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ assignmentId: id.assignment, sourceHandoverId: id.allocation, expectedAssignmentRevision: 1, expectedTitleRevision: 0, targetOwner: 'CUSTOMER', reason: 'Berita acara dan persetujuan pelanggan', evidenceId: id.evidence })
  expect(screen.getByText('Milik ISP')).toBeDefined()
  expect(screen.getByText(/Stok, kepemilikan, dan kewajiban pengembalian belum berubah/)).toBeDefined()
  expect(fetch.mock.calls.every(([path]) => !path.includes('/jobs'))).toBe(true)
})
it('submits loss using the selected current assignment, original work order revision and revalidated LOST destination', async () => {
  const fetch = transport(); mount(); await open('loss')
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Lokasi kehilangan' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi kehilangan' }), { target: { value: id.inspection } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengajuan' }))
  const dialog = await screen.findByRole('dialog'); expect(dialog.textContent).toContain('Perangkat hilang')
  fireEvent.click(within(dialog).getByRole('button', { name: 'Catat pengajuan' }))
  await screen.findByText('LOST-ASSET-01 sudah tercatat')
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ assignmentId: id.assignment, sourceHandoverId: id.allocation, expectedRevision: 1, expectedTitleRevision: 0, expectedWorkOrderRevision: 7,
    destinationLocationId: id.inspection, reason: 'Berita acara dan persetujuan pelanggan', evidenceId: id.evidence })
  expect(fetch.mock.calls.some(([path]) => path.endsWith(`/locations/${id.inspection}`))).toBe(true)
  expect(screen.getByText('Terpasang')).toBeDefined()
})
it.each(['sold', 'pending', 'ended', 'unknown', 'repair'] as const)('does not offer loss for an ineligible %s asset', async state => {
  const row = accepted()
  if (state === 'sold') { row.asset.ownershipMode = 'SALE'; row.asset.legalOwner = 'CUSTOMER' }
  if (state === 'pending') row.asset.handoverState = 'PENDING'
  if (state === 'ended') row.asset.endedAt = '2026-09-26T00:00:00Z'
  if (state === 'unknown') row.asset.provenance = 'UNKNOWN'
  if (state === 'repair') row.asset.positionStatus = 'REPAIR'
  transport({ row }); mount(); await screen.findByText('ONU Rumah · ONU-A1')
  expect(screen.queryByRole('button', { name: 'Ajukan kehilangan perangkat' })).toBeNull()
  if (state === 'sold') expect(screen.getByRole('button', { name: 'Ajukan koreksi kepemilikan' })).toBeDefined()
})
it('requires existing signature evidence and explains inaccessible work order evidence without requesting technician jobs', async () => {
  const context = contextFor(); context.workOrder.signature = null
  const fetch = transport({ context }); const view = mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Ajukan koreksi kepemilikan' }))
  await screen.findByText(/Lengkapi bukti tanda tangan pada WO asal/)
  expect(screen.getByRole('button', { name: 'Tinjau pengajuan' })).toHaveProperty('disabled', true)
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  view.unmount(); transport({ noEvidenceAccess: true }); mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Ajukan koreksi kepemilikan' }))
  await screen.findByText('Data tidak ditemukan dalam cakupan gudang Anda.')
  expect(screen.getByText(/Akses bukti WO atau penugasan teknisi/)).toBeDefined()
  expect(screen.getByRole('button', { name: 'Tinjau pengajuan' })).toHaveProperty('disabled', true)
})
it('stops review when the original work order revision changes and requires a fresh review after command rejection', async () => {
  const fetch = transport({ stale: true }); const view = mount(); await open()
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengajuan' }))
  await screen.findByText(/Kepemilikan, WO, izin, atau bukti berubah/)
  expect(screen.queryByRole('dialog')).toBeNull(); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  view.unmount(); const denied = transport({ denied: true }); mount(); await open()
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengajuan' }))
  fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Catat pengajuan' }))
  await screen.findByRole('button', { name: 'Muat ulang dokumen' })
  expect(screen.queryByRole('link', { name: 'Lanjutkan ke persetujuan' })).toBeNull()
  expect(denied.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
it('disables captured proposal submission offline and disables entry in a read-only account', async () => {
  const fetch = transport(); const view = mount(); await open()
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengajuan' })); await screen.findByRole('dialog')
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false); fireEvent(window, new Event('offline'))
  expect(screen.getByRole('button', { name: 'Catat pengajuan' })).toHaveProperty('disabled', true)
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  view.unmount(); access.readOnly = true; vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true); mount()
  expect(await screen.findByRole('button', { name: 'Ajukan koreksi kepemilikan' })).toHaveProperty('disabled', true)
})
it('explains the missing approval view permission so a loss requester can continue their own proposal', async () => {
  access.permissions.delete('inventory.approval.view')
  const fetch = transport(); mount()
  expect(await screen.findByRole('button', { name: 'Ajukan kehilangan perangkat' })).toHaveProperty('disabled', true)
  expect(screen.getByText(/Akses lihat persetujuan diperlukan agar Anda dapat melanjutkan pengajuan kehilangan sendiri/)).toBeDefined()
  expect(screen.queryByRole('button', { name: 'Ajukan koreksi kepemilikan' })).toBeNull()
  expect(fetch.mock.calls.every(([path]) => !path.endsWith('/exceptions/context'))).toBe(true)
})
