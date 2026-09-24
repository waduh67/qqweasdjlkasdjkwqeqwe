import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { formatBaseQuantity } from '@/api/warehouse/quantity'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { WarehouseStockPage } from './WarehouseStockPage'
import { WarehouseCost } from './WarehouseStockDetail'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, size = 25, totalElements = items.length) => ({ items, page: number, size, totalElements })
const q = (quantityBase: string, baseUnit: 'EA' | 'MM' = 'MM') => ({ quantityBase, baseUnit, displayQuantity: formatBaseQuantity(quantityBase, baseUnit, '.'), displayUnit: baseUnit === 'MM' ? 'M' : 'EA' })
const cable = { id: id.sku, skuId: id.sku, name: 'Kabel drop', skuCode: 'CABLE', tracking: 'LOT', physical: q('1000000'), reservedUnpicked: q('200000'), reservedPicked: q('100000'), available: q('600000'),
  minimumQuantityBase: '750000', statusBuckets: { AVAILABLE: '900000', QUARANTINE: '100000' }, conditionBuckets: { SERVICEABLE: '900000', QUARANTINE: '100000' }, ownerBuckets: { ISP: '1000000' } }
const onu = { ...cable, id: id.supplier, skuId: id.supplier, name: 'ONU pelanggan', skuCode: 'ONU', tracking: 'SERIAL', physical: q('10', 'EA'), reservedUnpicked: q('0', 'EA'), reservedPicked: q('0', 'EA'), available: q('8', 'EA'), minimumQuantityBase: null,
  statusBuckets: { AVAILABLE: '8', QUARANTINE: '2' }, conditionBuckets: { SERVICEABLE: '8', QUARANTINE: '2' }, ownerBuckets: { ISP: '10' } }
function show(path = '/warehouse/stock') { render(<MemoryRouter initialEntries={[path]}><WarehouseStockPage /></MemoryRouter>) }
beforeEach(() => { mocks.permissions.clear(); mocks.permissions.add('inventory.item.view'); tokenStore.clear() })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('shows exact bulk and serial quantities, thresholds and server pages while preserving drilldown filters', async () => {
  const fetch = vi.fn(async (path: string) => {
    const params = new URL(path, 'http://test').searchParams
    return response(params.get('page') === '1' ? page([{ ...onu, name: 'ONU halaman kedua' }], 1, 2, 3) : page([cable, onu], 0, 2, 3))
  }); vi.stubGlobal('fetch', fetch)
  show(`/warehouse/stock?locationId=${id.inspection}&condition=SERVICEABLE`)
  const cableLink = await screen.findByRole('link', { name: 'Kabel drop' })
  const row = within(cableLink.closest('[role="row"]')!)
  expect(row.getByText('600,000 m')).toBeTruthy(); expect(row.getByText('200,000 m')).toBeTruthy()
  expect(row.getByText('100,000 m')).toBeTruthy(); expect(row.getByText('Di bawah minimum')).toBeTruthy()
  expect(screen.getByText('8 unit')).toBeTruthy(); expect(screen.getByText('Ambang tidak tersedia')).toBeTruthy()
  expect(cableLink.getAttribute('href')).toContain(`locationId=${id.inspection}`)
  expect(cableLink.getAttribute('href')).toContain('condition=SERVICEABLE')
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await screen.findByRole('link', { name: 'ONU halaman kedua' })
  expect(screen.queryByRole('link', { name: 'Kabel drop' })).toBeNull()
  expect(fetch.mock.calls.at(-1)![0]).toContain('page=1')
  expect(fetch.mock.calls.at(-1)![0]).toContain(`locationId=${id.inspection}`)
})

it('applies the reserved bucket on the server and resets paging instead of filtering a partial page locally', async () => {
  const fetch = vi.fn(async (path: string) => response(page(new URL(path, 'http://test').searchParams.get('bucket') === 'RESERVED' ? [cable] : [], 0)))
  vi.stubGlobal('fetch', fetch); show('/warehouse/stock?page=2')
  await screen.findByText('Tidak ada stok yang cocok dalam cakupan Anda')
  fireEvent.click(screen.getByText('Filter stok', { exact: true }))
  fireEvent.change(await screen.findByRole('combobox', { name: 'Kelompok stok' }), { target: { value: 'RESERVED' } })
  fireEvent.click(screen.getByRole('button', { name: 'Terapkan filter' }))
  await screen.findByRole('link', { name: 'Kabel drop' })
  const request = new URL(fetch.mock.calls.at(-1)![0], 'http://test')
  expect(request.searchParams.get('bucket')).toBe('RESERVED'); expect(request.searchParams.has('page')).toBe(false)
  fireEvent.click(screen.getByRole('tab', { name: 'Perangkat serial' }))
  await waitFor(() => expect(fetch.mock.calls.at(-1)![0]).toContain('/assets?'))
  expect(fetch.mock.calls.at(-1)![0]).not.toContain('bucket=')
})

it('uses named archived masters in historical stock filters without dropping the location restriction', async () => {
  mocks.permissions.add('inventory.sku.view'); mocks.permissions.add('inventory.location.view')
  const sku = { id: id.sku, code: 'OLD', name: 'Kabel lama', tracking: 'LOT', baseUnit: 'MM', revision: 3, state: 'ARCHIVED', category: null, model: null, allowedOwnershipModes: ['LOAN'], inspectionRequired: true, minimumQuantityBase: '0' }
  const location = { id: id.inspection, code: 'QA', name: 'Pemeriksaan', kind: 'QUARANTINE', revision: 0, state: 'ACTIVE', areaId: null, siteId: null, parentLocationId: null, custodianId: null, issueEligible: false }
  const fetch = vi.fn(async (path: string) => {
    if (path.endsWith(`/locations/${id.inspection}`)) return response(location)
    if (path.includes('/locations?')) return response(page([location]))
    if (path.includes('/skus?')) return response(page([sku]))
    if (path.endsWith(`/skus/${id.sku}`)) return response(sku)
    return response(page([]))
  }); vi.stubGlobal('fetch', fetch); show(`/warehouse/stock?locationId=${id.inspection}`)
  fireEvent.click(screen.getByText('Filter stok', { exact: true }))
  await screen.findByRole('option', { name: 'Kabel lama · OLD (arsip)' })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Barang' })).toHaveProperty('disabled', false))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang' }), { target: { value: id.sku } })
  fireEvent.click(screen.getByRole('button', { name: 'Terapkan filter' }))
  await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.includes('/stock?') && path.includes(`skuId=${id.sku}`) && path.includes(`locationId=${id.inspection}`))).toBe(true))
  expect(fetch.mock.calls.filter(([path]) => /\/(skus|locations)\?/.test(path)).every(([path]) => !path.includes('state=ACTIVE'))).toBe(true)
})

it('keeps unknown quantities and units explicit and gates the provenance view before requesting it', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  const view = render(<MemoryRouter initialEntries={['/warehouse/stock?tab=unknown']}><WarehouseStockPage /></MemoryRouter>)
  expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
  view.unmount(); mocks.permissions.add('inventory.provenance.view')
  fetch.mockResolvedValue(response(page([{ id: id.document, source: 'BALANCE', skuId: id.sku, name: 'Kabel lama', locationId: id.inspection, status: 'AVAILABLE', rawQuantity: '37', quantityBase: null, baseUnit: null,
    legalOwner: 'UNKNOWN', admission: 'LEGACY_UNRESOLVED', serial: null, available: false, reason: 'UNVERIFIED_UNIT_OR_ORIGIN_OR_TITLE' }])))
  show('/warehouse/stock?tab=unknown')
  await screen.findByText('37 · satuan belum diverifikasi')
  expect(screen.queryByText('37 unit')).toBeNull()
  expect(screen.getByText(/tidak dihitung sebagai stok tersedia/)).toBeTruthy()
})

it('shows scope denial as a load failure without stock, costs or an empty successful result', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ code: 'NOT_FOUND', message: 'NOT_FOUND' }, 404)); vi.stubGlobal('fetch', fetch)
  show(`/warehouse/stock?asset=${id.piece}`)
  await screen.findByText('Data belum berhasil dimuat')
  expect(screen.queryByText('Tidak ada stok yang cocok dalam cakupan Anda')).toBeNull()
  expect(screen.queryByRole('heading', { name: 'Jejak barang' })).toBeNull()
  expect(fetch).toHaveBeenCalledOnce()
})

it('distinguishes unknown, redacted and exact known costs using current permission', () => {
  mocks.permissions.add('inventory.cost.view')
  const view = render(<WarehouseCost cost={{ state: 'UNKNOWN' }} unit="MM" />)
  expect(screen.getByText('Biaya asal belum diketahui.')).toBeTruthy()
  view.rerender(<WarehouseCost cost={null} unit="MM" />)
  expect(screen.getByText('Biaya tidak tersedia dalam akses saat ini.')).toBeTruthy()
  const cost = { state: 'KNOWN' as const, totalMinor: '9007199254740993', costBasisQuantityBase: '1000000', currency: 'IDR' }
  view.rerender(<WarehouseCost cost={cost} unit="MM" />)
  expect(screen.getByText(/9.007.199.254.740.993/)).toBeTruthy()
  mocks.permissions.delete('inventory.cost.view'); view.rerender(<WarehouseCost cost={cost} unit="MM" />)
  expect(screen.queryByText(/9.007.199.254.740.993/)).toBeNull()
})

it('keeps inconsistent lot conservation visible and never represents a truncated child list as the complete tree', async () => {
  const lot = { id: id.line, skuId: id.sku, code: 'R1', name: 'Kabel drop', received: q('1000000'), receivedAt: '2026-09-24T17:00:00Z', admission: 'VERIFIED', origin: null,
    conservation: { consistent: false, physicalQuantityBase: '999999', rootQuantityBase: '1000000', activeQuantityBase: '1000000', terminalQuantityBase: '0', rootCount: 1, splitCount: 1 } }
  const segment = { id: id.piece, stockIdentityId: id.piece, lotId: id.line, parentSegmentId: null, kind: 'REEL', state: 'SPLIT', quantity: q('1000000'), createdAt: lot.receivedAt, origin: null, children: [id.source], childCount: 101, conserved: false }
  vi.stubGlobal('fetch', vi.fn(async (path: string) => {
    if (path.endsWith(`/segments/${id.piece}`)) return response(segment)
    if (path.includes('/segments?')) return response(page([segment]))
    if (path.includes('/history?')) return response(page([]))
    return response(lot)
  }))
  show(`/warehouse/stock?lot=${id.line}&segment=${id.piece}`)
  await screen.findByText(/Jumlah reel dan bagiannya tidak konsisten/)
  await screen.findByText(/Ditampilkan 1 dari 101 bagian/)
  expect(screen.queryByText('Jumlah reel dan seluruh bagiannya konsisten.')).toBeNull()
  expect(screen.getByRole('link', { name: `Buka bagian ${id.source.slice(0, 8)}` }).getAttribute('href')).toContain(`segment=${id.source}`)
})

it('rejects malformed and duplicate URL filters before sending an unfiltered stock request', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  const view = render(<MemoryRouter initialEntries={['/warehouse/stock?serial=']}><WarehouseStockPage /></MemoryRouter>)
  expect(screen.getByText('Filter atau alamat stok tidak dikenal.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
  view.unmount(); show('/warehouse/stock?bucket=AVAILABLE&bucket=RESERVED')
  expect(screen.getByText('Filter atau alamat stok tidak dikenal.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
})
