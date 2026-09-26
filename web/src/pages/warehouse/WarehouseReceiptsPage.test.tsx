import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { receiptFixture, receiptIds, receiptPieceFixture } from '@/test/warehouseReceiptFixture'
import { WarehouseReceiptEditor } from './WarehouseReceiptEditor'
import { WarehouseReceiptsPage } from './WarehouseReceiptsPage'
import { buildReceiptLines, emptyReceiptRow, parseReceiptSerials, rowsFromReceipt } from './receiptDraft'
import { receiptCandidates } from './receiptActions'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ({ items, page: 0, size: 25, totalElements: items.length })
const id = receiptIds
const sku = { id: id.sku, code: 'CABLE', name: 'Kabel drop', tracking: 'LOT' as const, baseUnit: 'MM' as const, revision: 0, state: 'ACTIVE', category: null, model: null, allowedOwnershipModes: ['LOAN'], inspectionRequired: true, minimumQuantityBase: '0' }
const locations = [ { id: id.source, code: 'RECEIPT_SOURCE', name: 'Penerimaan pemasok', kind: 'TRANSIT' }, { id: id.inspection, code: 'QA', name: 'Pemeriksaan barang', kind: 'QUARANTINE' } ]
  .map(row => ({ ...row, revision: 0, state: 'ACTIVE', areaId: null, siteId: null, parentLocationId: null, custodianId: null, issueEligible: false }))
function directory(path: string) {
  if (path.includes('/locations?')) return response(page(locations))
  if (path.includes('/skus?')) return response(page([sku]))
  if (path.includes('/suppliers?')) return response(page([{ id: id.supplier, name: 'Distributor kabel', code: 'SUP', revision: 0, state: 'ACTIVE', contactReference: null }]))
  throw new Error('Unexpected fixture request: ' + path)
}
beforeEach(() => {
  mocks.permissions.clear(); ['inventory.receipt.view', 'inventory.receipt.manage', 'inventory.sku.view', 'inventory.location.view', 'inventory.cost.view'].forEach(p => mocks.permissions.add(p))
  Object.defineProperties(HTMLDialogElement.prototype, { showModal: { configurable: true, value: function(this: HTMLDialogElement) { this.open = true } }, close: { configurable: true, value: function(this: HTMLDialogElement) { this.open = false } } })
  tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it.each(['SEALED_SUPPLIER_REPLACEMENT', null] as const)('does not offer ordinary draft editing for current editability %s', async draftEditability => {
  const current = { ...receiptFixture(), draftEditability }
  const fetch = vi.fn(async (path: string) => {
    if (path.endsWith('/history')) return response([])
    if (path.includes('/attachments?')) return response(page([]))
    if (path.endsWith(`/receipts/${id.document}`)) return response(current)
    throw new Error('Unexpected fixture request: ' + path)
  })
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter initialEntries={[`/warehouse/receipts?id=${id.document}`]}><WarehouseReceiptsPage /></MemoryRouter>)
  await screen.findByRole('button', { name: 'Terima barang' })
  expect(screen.queryByRole('button', { name: 'Ubah draft' })).toBeNull()
  expect(screen.queryByText(/Ubah draft memerlukan akses rincian biaya/)).toBeNull()
  expect(screen.getByText(draftEditability ? /Usulan penerimaan pengganti sudah tercatat/ : /Muat ulang detail untuk memeriksa/)).toBeTruthy()
})

it('refuses entering the ordinary editor for a sealed replacement even through a stale caller', () => {
  vi.stubGlobal('fetch', vi.fn())
  render(<MemoryRouter><WarehouseReceiptEditor receipt={{ ...receiptFixture(), draftEditability: 'SEALED_SUPPLIER_REPLACEMENT' }} onClose={vi.fn()} onSaved={vi.fn()} onReload={vi.fn()} /></MemoryRouter>)
  expect(screen.getByRole('alert').textContent).toContain('usulan baru dari kasus retur asal')
  expect(screen.queryByRole('button', { name: 'Tinjau draft' })).toBeNull()
})

it('rejects normalized duplicate serials and MACs and mismatched serial count before constructing a receipt', () => {
  expect(() => parseReceiptSerials('ONU-1\n onu-1 ')).toThrow('Serial ganda')
  expect(() => parseReceiptSerials('ONU1, AA:BB:CC:DD:EE:FF\nONU2, AABB.CCDD.EEFF')).toThrow('MAC ganda')
  expect(() => buildReceiptLines([{ ...emptyReceiptRow(), sku: { ...sku, baseUnit: 'EA', tracking: 'SERIAL' }, quantity: '2', serials: 'ONU1' }], false)).toThrow('harus sama')
})

it('preserves exact metre conversion and original serial group cost when editing expanded receipt lines', () => {
  const source = receiptFixture(), line = source.lines[0]
  source.lines = ['ONU1', 'ONU2'].map((serial, index) => ({ ...line, id: index ? id.supplier : id.line, skuName: 'ONU', tracking: 'SERIAL', baseUnit: 'EA', quantityBase: '1', serial, lotCode: null,
    cost: { totalMinor: '1999995', currency: 'USD', costBasisQuantityBase: '2' }, conversion: { numerator: '2', denominator: '1', packageQuantity: '1' } }))
  expect(buildReceiptLines(rowsFromReceipt(source), true)).toMatchObject([{ quantityBase: '2', cost: { totalMinor: '1999995', currency: 'USD' }, conversion: { numerator: '2', denominator: '1', packageQuantity: '1' }, serials: [{ serial: 'ONU1' }, { serial: 'ONU2' }] }])
  const cable = { ...emptyReceiptRow(), sku, quantity: '82,500', lotCode: 'R1', useConversion: true, numerator: '165000', denominator: '2', packageQuantity: '1' }
  expect(buildReceiptLines([cable], true)[0].quantityBase).toBe('82500')
  expect(() => buildReceiptLines([{ ...cable, denominator: '3' }], true)).toThrow('harus tepat')
})

it('reviews a cable receipt and retries the same draft operation after a lost response without creating a new command', async () => {
  let posts = 0
  const fetch = vi.fn(async (path: string, init: RequestInit) => { if (init.method === 'POST') { if (++posts === 1) throw new TypeError('network lost'); return response({ ...receiptFixture(), revision: 0 }) } return directory(path) })
  vi.stubGlobal('fetch', fetch)
  const onSaved = vi.fn()
  render(<MemoryRouter><WarehouseReceiptEditor onSaved={onSaved} onClose={vi.fn()} onReload={vi.fn()} /></MemoryRouter>)
  await screen.findByRole('option', { name: 'Kabel drop · CABLE' })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi surat jalan' }), { target: { value: 'SJ-001' } })
  for (const [label, value] of [['Pemasok', id.supplier], ['Batas penerimaan', id.source], ['Lokasi pemeriksaan', id.inspection], ['Barang 1', id.sku]]) {
    await waitFor(() => expect((screen.getByRole('combobox', { name: label }) as HTMLSelectElement).disabled).toBe(false))
    fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value } })
  }
  fireEvent.change(screen.getByRole('textbox', { name: 'Panjang reel aktual (m)' }), { target: { value: '1000,000' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Kode lot / reel' }), { target: { value: 'REEL1' } })
  fireEvent.submit(document.querySelector('form')!)
  expect(posts).toBe(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan draft' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  const mutations = fetch.mock.calls.filter(([, init]) => init.method === 'POST')
  expect(mutations).toHaveLength(2)
  expect(mutations[0][1].body).toBe(mutations[1][1].body)
  expect((mutations[0][1].headers as Headers).get('Idempotency-Key')).toBe((mutations[1][1].headers as Headers).get('Idempotency-Key'))
  expect(JSON.parse(mutations[0][1].body as string).lines[0]).toMatchObject({ quantityBase: '1000000', lotCode: 'REEL1' })
})

it('retains edited draft after stale revision and explicitly reloads without submitting again', async () => {
  const fetch = vi.fn(async (path: string, init: RequestInit) => init.method === 'PUT' ? response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) : directory(path))
  vi.stubGlobal('fetch', fetch)
  const reload = vi.fn()
  render(<MemoryRouter><WarehouseReceiptEditor receipt={receiptFixture()} onSaved={vi.fn()} onClose={vi.fn()} onReload={reload} /></MemoryRouter>)
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi surat jalan' }), { target: { value: 'SJ-EDIT' } })
  fireEvent.submit(document.querySelector('form')!)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan draft' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  expect(reload).toHaveBeenCalledOnce()
  expect(screen.getByRole('textbox', { name: 'Referensi surat jalan' })).toHaveProperty('value', 'SJ-EDIT')
  const mutations = fetch.mock.calls.filter(([, init]) => init.method === 'PUT')
  expect(mutations).toHaveLength(1)
  expect(JSON.parse(mutations[0][1].body as string)).toMatchObject({ expectedRevision: 4, lines: [{ cost: { totalMinor: '1000006', currency: 'IDR' } }] })
})

it('blocks draft replacement when server redacts costs even if the browser still has the old permission', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseReceiptEditor receipt={{ ...receiptFixture(), costVisible: false }} onSaved={vi.fn()} onClose={vi.fn()} onReload={vi.fn()} /></MemoryRouter>)
  expect(screen.getByRole('alert').textContent).toContain('biaya tersimpan tetap terjaga')
  expect(screen.queryByRole('button', { name: 'Tinjau draft' })).toBeNull()
  expect(fetch).not.toHaveBeenCalled()
})

it('does not expose receipt mutations or an empty successful document after a scope denial', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ code: 'NOT_FOUND', message: 'NOT_FOUND' }, 404)); vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter initialEntries={[`/warehouse/receipts?id=${id.document}`]}><WarehouseReceiptsPage /></MemoryRouter>)
  await screen.findByText('Data belum berhasil dimuat')
  expect(screen.queryByRole('button', { name: 'Terima barang' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Buat penerimaan' })).toBeNull()
})

it('accepts the real transition acknowledgement and reloads the durable receipt before offering inspection', async () => {
  let posted = false
  const current = receiptFixture()
  const fetch = vi.fn(async (path: string, init: RequestInit) => {
    if (path.endsWith('/receive') && init.method === 'POST') {
      posted = true
      expect(JSON.parse(init.body as string)).toEqual({ expectedRevision: 4 })
      return response({ id: id.document, revision: 5, state: 'RECEIVED_IN_INSPECTION', operationId: id.evidence })
    }
    if (path.endsWith('/history')) return response([])
    if (path.includes('/attachments?')) return response(page([]))
    if (path.endsWith(`/receipts/${id.document}`)) return response(posted ? { ...current, revision: 5, state: 'RECEIVED_IN_INSPECTION', lines: [{ ...current.lines[0], pieces: [receiptPieceFixture()] }] } : current)
    throw new Error('Unexpected fixture request: ' + path)
  })
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter initialEntries={[`/warehouse/receipts?id=${id.document}`]}><WarehouseReceiptsPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Terima barang' }))
  fireEvent.click(screen.getByRole('button', { name: 'Konfirmasi penerimaan' }))
  expect(await screen.findByRole('button', { name: 'Periksa barang' })).toHaveProperty('disabled', false)
  expect(screen.queryByRole('button', { name: 'Coba transaksi yang sama' })).toBeNull()
  expect(fetch.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(1)
})

it('never offers rejected pieces for putaway through the SKU inspection bypass', () => {
  const receipt = receiptFixture(), line = { ...receipt.lines[0], inspectionRequired: false, pieces: [receiptPieceFixture(), { ...receiptPieceFixture(), stockIdentityId: id.source, disposition: 'QUARANTINE' as const }, { ...receiptPieceFixture(), stockIdentityId: id.supplier, disposition: 'ACCEPTED' as const }] }
  expect(receiptCandidates(receipt, line, 'putaway').map(piece => piece.stockIdentityId)).toEqual([id.piece, id.supplier])
  expect(receiptCandidates(receipt, line, 'inspect').map(piece => piece.stockIdentityId)).toEqual([id.piece])
})
