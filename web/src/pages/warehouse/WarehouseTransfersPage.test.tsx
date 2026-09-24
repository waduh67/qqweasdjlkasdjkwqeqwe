import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { transferDetailsFixture, transferFixture, transferIds as id, transferLocations, transferPositionFixture } from '@/test/warehouseTransferFixture'
import type { WarehouseTransfer } from '@/api/warehouse/transfers'
import { WarehouseTransfersPage } from './WarehouseTransfersPage'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value), user: { id: '', name: 'Petugas asal' } } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: mocks.user }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, size = 25, totalElements = items.length) => ({ items, page: number, size, totalElements })
const root = '/api/v1/warehouse/transfers'
function show(path = `/warehouse/transfers?transferId=${id.document}`) { return render(<MemoryRouter initialEntries={[path]}><WarehouseTransfersPage /></MemoryRouter>) }
function shipped(): WarehouseTransfer { const draft = transferFixture(); return { ...draft, state: 'DISPATCHED', revision: 1, lines: [{ ...draft.lines[0], inTransitBase: '100000' }] } }
function partial(): WarehouseTransfer { const prior = shipped(); return { ...prior, state: 'PART_RECEIVED', revision: 2, lines: [{ ...prior.lines[0], receivedBase: '60000', inTransitBase: '40000', remainingIdentityId: id.demandLine }] } }
function read(path: string, transfer = transferFixture()) {
  if (path.endsWith('/details')) return response(transferDetailsFixture(transfer))
  if (path.includes('/history/page?')) return response(page([transfer]))
  throw new Error(`Unexpected read ${path}`)
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  mocks.permissions.clear(); for (const permission of ['inventory.transfer.view', 'inventory.transfer.manage', 'inventory.item.view', 'inventory.location.view', 'inventory.approval.request', 'inventory.approval.view']) mocks.permissions.add(permission)
  mocks.user.id = id.sender; tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('blocks inaccessible or malformed deep links before reading warehouse data', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  mocks.permissions.delete('inventory.transfer.view')
  const denied = show(); expect(screen.getByRole('alert')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled(); denied.unmount()
  mocks.permissions.add('inventory.transfer.view')
  show(`/warehouse/transfers?transferId=${id.document}&transferId=${id.document}`)
  expect(screen.getByText('Alamat transfer tidak dikenal.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
})
it('pages discoverable transfers with current named locations and receiver', async () => {
  const fetch = vi.fn(async (path: string) => {
    const second = path.includes('page=1'), details = transferDetailsFixture({ ...transferFixture(), code: second ? 'TR-LAST' : 'TR-FIRST' })
    return response(page([details], second ? 1 : 0, 1, 2))
  }); vi.stubGlobal('fetch', fetch); show('/warehouse/transfers')
  await screen.findByRole('link', { name: 'TR-FIRST' }); expect(screen.getByText('Petugas tujuan')).toBeTruthy()
  expect(screen.getByText('Rak A · BIN-A → Gudang B · WH-B')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await screen.findByRole('link', { name: 'TR-LAST' }); expect(fetch.mock.calls.at(-1)![0]).toContain('page=1')
})
it('creates a reviewed draft with a real position and own recipient without reading the IAM directory', async () => {
  const created = { ...transferFixture(), receiverId: id.sender }, details = transferDetailsFixture(created)
  details.references.people = [{ id: id.sender, name: 'Petugas asal' }]
  const position = transferPositionFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response(created, 201)
    if (path.startsWith(`${root}?`)) return response(page([]))
    if (path.endsWith('/details')) return response(details)
    if (path.includes('/history/page?')) return response(page([created]))
    if (path.includes('/locations?')) return response(page(transferLocations))
    if (path.includes('/stock/positions?')) return response(page([position]))
    if (path.includes('/lots/')) return response({ id: id.evidence, skuId: id.sku, code: 'REEL-TRANSFER', name: 'Kabel drop', received: position.physical, receivedAt: created.recordedAt, admission: 'VERIFIED', origin: null, cost: null,
      conservation: { consistent: true, physicalQuantityBase: '1000000', rootQuantityBase: '1000000', activeQuantityBase: '1000000', terminalQuantityBase: '0', rootCount: 1, splitCount: 0 } })
    throw new Error(`Unexpected read ${path}`)
  }); vi.stubGlobal('fetch', fetch); show('/warehouse/transfers')
  fireEvent.click(screen.getByRole('button', { name: 'Buat transfer' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Lokasi asal transfer' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi asal transfer' }), { target: { value: id.source } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi tujuan transfer' }), { target: { value: id.destination } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi transit transfer' }), { target: { value: id.transit } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Penerima transfer' }), { target: { value: id.sender } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan transfer' }), { target: { value: 'Pengisian gudang B' } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Barang transfer 1' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang transfer 1' }), { target: { value: id.position } })
  await screen.findByText('Lot / reel: REEL-TRANSFER')
  fireEvent.change(screen.getByRole('textbox', { name: 'Jumlah transfer 1 (m)' }), { target: { value: '100' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau transfer' }))
  expect((await screen.findByRole('dialog', { name: 'Simpan draft transfer' })).textContent).toContain('Penerima: Petugas asal')
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan transfer' }))
  await screen.findByRole('heading', { name: 'TR-001' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ sourceLocationId: id.source, destinationLocationId: id.destination, transitLocationId: id.transit, receiverId: id.sender, lines: [{ stockIdentityId: id.piece, sourceBalanceId: id.position, quantityBase: '100000' }] })
  expect(fetch.mock.calls.some(([path]) => path.startsWith('/api/users'))).toBe(false)
})
it('dispatches the bound draft revision after named receiver review and reloads actual transit', async () => {
  let current = transferFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = shipped(); return response(current) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Kirim ke transit' }))
  const review = await screen.findByRole('dialog', { name: 'Kirim transfer ke transit' })
  expect(review.textContent).toContain('Penerima: Petugas tujuan'); expect(review.textContent).toContain('100,000 m')
  fireEvent.click(screen.getByRole('button', { name: 'Konfirmasi kirim transfer' }))
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Kirim ke transit' })).toBeNull())
  await screen.findByRole('button', { name: 'Terima transfer' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(writes[0][0]).toBe(`${root}/${id.document}/dispatch`)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 0 })
  expect(screen.getByRole('button', { name: 'Terima transfer' })).toHaveProperty('disabled', true)
})
it('records sixty metres actually received while forty remain in transit', async () => {
  mocks.user.id = id.receiver; let current = shipped()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = partial(); return response(current) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Terima transfer' }))
  fireEvent.click(screen.getByRole('checkbox', { name: 'Terima Kabel drop · REEL-TRANSFER' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Jumlah diterima (m)' }), { target: { value: '60' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi bukti transfer' }), { target: { value: 'SJ-TERIMA' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Catat penerimaan' }))
  await waitFor(() => expect(screen.getByRole('region', { name: 'Detail transfer' }).textContent).toContain('Sebagian diterima'))
  const row = screen.getAllByRole('row').find(row => row.textContent?.includes('Identitas asal:'))!
  expect(row).toBeTruthy(); expect(within(row).getByText('60,000 m')).toBeTruthy(); expect(within(row).getByText('40,000 m')).toBeTruthy()
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 1, evidenceReference: 'SJ-TERIMA', lines: [{ lineId: id.line, quantityBase: '60000', baseUnit: 'MM' }] })
})
it('requires a fresh review after a conflicting receipt and does not retry with an invented revision', async () => {
  mocks.user.id = id.receiver; let current = shipped()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = partial(); return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Terima transfer' }))
  fireEvent.click(screen.getByRole('checkbox', { name: 'Terima Kabel drop · REEL-TRANSFER' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Jumlah diterima (m)' }), { target: { value: '60' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi bukti transfer' }), { target: { value: 'SJ-STALE' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan' })); fireEvent.click(await screen.findByRole('button', { name: 'Catat penerimaan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await screen.findByRole('heading', { name: 'TR-001' })
  expect(screen.queryByRole('textbox', { name: 'Jumlah diterima (m)' })).toBeNull()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
it('does not offer another actor dispatch or treat independent resolution as receipt or available customer stock', async () => {
  mocks.user.id = id.receiver
  let current = transferFixture()
  vi.stubGlobal('fetch', vi.fn(async (path: string) => read(path, current)))
  const view = show(); expect(await screen.findByRole('button', { name: 'Kirim ke transit' })).toHaveProperty('disabled', true); view.unmount()
  current = { ...partial(), state: 'DISCREPANCY', resolutionDocumentId: id.evidence, lines: [{ ...partial().lines[0], inTransitBase: '0', resolvedBase: '40000', remainingIdentityId: null, legalOwner: 'CUSTOMER' }] }
  show(); await screen.findByText(/Barang milik pelanggan tetap milik pelanggan/)
  expect(screen.queryByRole('button', { name: 'Terima transfer' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Laporkan selisih' })).toBeNull()
  expect(screen.getByRole('link', { name: 'Buka persetujuan gudang' }).getAttribute('href')).toContain(id.evidence)
})

it('reports the remaining quantity for independent resolution without changing receipt or transit', async () => {
  mocks.user.id = id.receiver; let current = partial()
  const lost = { ...transferLocations[2], id: id.evidence, code: 'LOST', name: 'Barang hilang', kind: 'LOST' }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = { ...current, state: 'DISCREPANCY', revision: 3, resolutionDocumentId: id.evidence }; return response(current) }
    if (path.includes('/locations?')) return response(page([lost]))
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Laporkan selisih' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Tujuan penanganan selisih' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Tujuan penanganan selisih' }), { target: { value: id.evidence } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan selisih' }), { target: { value: 'Sisa belum ditemukan' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi bukti transfer' }), { target: { value: 'BA-SELISIH' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau selisih' }))
  const review = await screen.findByRole('dialog', { name: 'Konfirmasi selisih transfer' })
  expect(review.textContent).toContain('40,000 m'); expect(review.textContent).toContain('Barang hilang')
  expect(review.textContent).toContain('Stok tetap di transit')
  fireEvent.click(screen.getByRole('button', { name: 'Catat selisih' }))
  await screen.findByRole('link', { name: 'Buka persetujuan gudang' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(writes[0][0]).toBe(`${root}/${id.document}/discrepancy`)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 2, action: 'LOST', destinationLocationId: id.evidence, reason: 'Sisa belum ditemukan', evidenceReference: 'BA-SELISIH' })
  const row = screen.getAllByRole('row').find(row => row.textContent?.includes('Identitas asal:'))!
  expect(within(row).getByText('60,000 m')).toBeTruthy(); expect(within(row).getByText('40,000 m')).toBeTruthy()
})

it('reads another server history page without treating current labels as new stock facts', async () => {
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/history/page?')) { const second = path.includes('page=1'); return response(page([second ? transferFixture() : partial()], second ? 1 : 0, 1, 2)) }
    return read(path, partial())
  }); vi.stubGlobal('fetch', fetch); show()
  await screen.findByRole('heading', { name: 'TR-001' })
  fireEvent.click(screen.getByText('Riwayat transfer'))
  const history = screen.getByText('Riwayat transfer').closest('details')!
  await within(history).findByRole('heading', { name: 'Revisi 2 · Sebagian diterima' })
  fireEvent.click(within(history).getByRole('button', { name: 'Berikutnya' }))
  await within(history).findByRole('heading', { name: 'Revisi 0 · Draf' })
  expect(fetch.mock.calls.some(([path]) => path.endsWith('/history/page?page=1&size=25'))).toBe(true)
})
