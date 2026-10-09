import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { selectControl } from '@/test/selectControl'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { ReferenceReceiptEditor } from './ReferenceReceiptEditor'
import { ReferenceReceiptsPage } from './ReferenceMovementsPage'

const access = vi.hoisted(() => ({ permissions: new Set<string>() }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (permission: string) => access.permissions.has(permission) }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const paged = (items: unknown[]) => ({ items, page: 0, size: 25, totalElements: items.length })
const posted = { id: id.document, operationId: id.evidence, revision: 1, kind: 'RECEIPT', state: 'PUTAWAY', warehouseId: id.source,
  sourceWarehouseId: null, notes: '', recordedAt: '2026-10-09T12:00:00Z' }
const document = { ...posted, code: 'RCV-001', actorName: 'Admin gudang', warehouseName: 'Gudang A', sourceWarehouseName: null, supplierName: null, reference: 'SJ-001', costVisible: false }
const cable = { id: id.sku, code: 'DROP', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', revision: 0, state: 'ACTIVE', category: null, model: null, allowedOwnershipModes: ['LOAN'], inspectionRequired: false, minimumQuantityBase: '0' }
const warehouse = { id: id.source, code: 'WH-A', name: 'Gudang A', kind: 'WAREHOUSE', revision: 0, state: 'ACTIVE', areaId: null, siteId: null, parentLocationId: null, custodianId: null, issueEligible: true }
function directory(path: string) {
  if (path.includes('/locations?')) return response(paged([warehouse]))
  if (path.includes('/skus?')) return response(paged([cable]))
  if (path.includes('/suppliers?')) return response(paged([]))
  throw new Error('Unexpected directory read: ' + path)
}
beforeEach(() => {
  access.permissions.clear(); access.permissions.add('warehouse.stock.manage')
  tokenStore.clear()
  Object.defineProperties(HTMLDialogElement.prototype, { showModal: { configurable: true, value: function(this: HTMLDialogElement) { this.open = true } }, close: { configurable: true, value: function(this: HTMLDialogElement) { this.open = false } } })
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('reviews an immediate receipt without mandatory supplier reference or lot and retains the same key after response loss', async () => {
  let posts = 0
  const fetch = vi.fn(async (path: string, init: RequestInit) => {
    if (init.method === 'POST') { if (++posts === 1) throw new TypeError('response lost'); return response(posted, 201) }
    return directory(path)
  })
  vi.stubGlobal('fetch', fetch)
  const saved = vi.fn()
  render(<MemoryRouter><ReferenceReceiptEditor onSaved={saved} onClose={vi.fn()} /></MemoryRouter>)
  await selectControl(screen.getByRole('combobox', { name: 'Gudang tujuan' }), { target: { value: id.source } })
  await selectControl(screen.getByRole('combobox', { name: 'Barang 1' }), { target: { value: id.sku } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Panjang reel aktual (m)' }), { target: { value: '82,501' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan' }))
  expect(posts).toBe(0)
  expect(screen.getByText('Barang langsung menjadi stok tersedia setelah penerimaan disimpan.')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Terima barang' }))
  await screen.findByRole('button', { name: 'Coba transaksi yang sama' })
  expect(screen.getByRole('button', { name: 'Sebelumnya' })).toHaveProperty('disabled', true)
  fireEvent.click(screen.getByRole('button', { name: 'Coba transaksi yang sama' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  const writes = fetch.mock.calls.filter(([, init]) => init.method === 'POST')
  expect(writes).toHaveLength(2)
  expect(writes[0][1].body).toBe(writes[1][1].body)
  expect((writes[0][1].headers as Headers).get('Idempotency-Key')).toBe((writes[1][1].headers as Headers).get('Idempotency-Key'))
  expect(JSON.parse(writes[0][1].body as string)).toMatchObject({ warehouseId: id.source, notes: '', lines: [{ skuId: id.sku, quantityBase: '82501', lotCode: null }] })
})

it('lets Manager read a saved receipt while hiding costs and write controls', async () => {
  access.permissions.clear(); access.permissions.add('warehouse.stock.view')
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path.includes('/lines?')) return response(paged([{ id: id.line, lineNumber: 1, skuId: id.sku, skuCode: 'DROP', skuName: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', quantityBase: '82501', serial: null, mac: null, lotCode: 'RCV-AUTO', conversion: null, cost: null }]))
    return response(path.includes('/movements?') ? paged([document]) : document)
  }))
  render(<MemoryRouter initialEntries={['/warehouse/receipts?id=' + id.document]}><ReferenceReceiptsPage /></MemoryRouter>)
  await screen.findByText('82,501 m')
  expect(screen.getByText('Rincian biaya memerlukan izin lihat biaya.')).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Penerimaan baru' })).toBeNull()
  expect(screen.queryByText('Total biaya input')).toBeNull()
})

it('keeps scope revocation visible as an error without pretending that the document has no lines', async () => {
  access.permissions.clear()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ code: 'NOT_FOUND', message: 'NOT_FOUND' }, 404)))
  render(<MemoryRouter initialEntries={['/warehouse/receipts?id=' + id.document]}><ReferenceReceiptsPage /></MemoryRouter>)
  await screen.findByText('Data belum berhasil dimuat')
  expect(screen.queryByRole('heading', { name: 'Barang dalam dokumen' })).toBeNull()
})
