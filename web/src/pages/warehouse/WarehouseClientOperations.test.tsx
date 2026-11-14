import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { selectControl } from '@/test/selectControl'
import { WarehouseLoansPage } from './WarehouseLoansPage'
import { WarehouseWarrantiesPage } from './WarehouseWarrantiesPage'
import { WarehousePurchasesPage } from './WarehousePurchasesPage'

vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'admin', tenantId: 'tenant' } }) }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => true, hasPermission: () => true }) }))
const id = '11111111-1111-4111-8111-111111111111', skuId = '22222222-2222-4222-8222-222222222222', stockId = '33333333-3333-4333-8333-333333333333'
const base = { id, revision: 2, warehouseId: id, warehouseName: 'Gudang utama', createdAt: '2026-11-14T00:00:00Z', updatedAt: '2026-11-14T01:00:00Z' }
const loan = { ...base, state: 'ACTIVE', borrowerName: 'PT Client', contact: '081234', loanDate: '2026-11-14', dueDate: null, notes: '', requiresManagerApproval: false, policyRevision: 0, externalLocationId: id, issueEvidence: 'BA asli', issuedBy: 'Admin gudang', lines: [{ id, skuId, skuName: 'Konektor', baseUnit: 'EA', tracking: 'BULK', quantityBase: '5', approvedBase: '5', stocks: [{ stockIdentityId: stockId, quantityBase: '5', returnedBase: '0', serial: null }] }] }
afterEach(() => vi.unstubAllGlobals())
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }) }

it('reviews a partial damaged return and captures the selected stock and base quantity', async () => {
  const fetch = vi.fn(async (_url: unknown, _init?: RequestInit) => json({ document: loan, timeline: [] }))
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter initialEntries={['/warehouse/loans?id=' + id]}><WarehouseLoansPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Terima kembali Konektor' }))
  fireEvent.change(await screen.findByRole('textbox', { name: 'Jumlah kembali (unit)' }), { target: { value: '2' } })
  await selectControl(screen.getByRole('combobox', { name: 'Kondisi kembali' }), { target: { value: 'DAMAGED' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Bukti / kondisi pengembalian' }), { target: { value: 'Patah di client' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan' }))
  expect(await screen.findByText('Rusak: masuk karantina dan dibuat klaim garansi vendor.')).toBeTruthy()
  fetch.mockImplementation(async (_url, init) => init?.method === 'POST' ? json({ ...loan, revision: 3, state: 'PARTIALLY_RETURNED' }) : json({ document: { ...loan, revision: 3, state: 'PARTIALLY_RETURNED' }, timeline: [] }))
  fireEvent.click(screen.getByRole('button', { name: 'Simpan' }))
  await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThanOrEqual(2))
  const [, init] = fetch.mock.calls[1] as unknown as [string, RequestInit]
  expect(JSON.parse(init.body as string)).toMatchObject({ expectedRevision: 2, evidence: 'Patah di client', lines: [{ lineId: id, stockIdentityId: stockId, quantityBase: '2', condition: 'DAMAGED' }] })
})
it('blocks serialized stock release until reset is confirmed', async () => {
  const claim = { ...base, state: 'MASUK', loanId: id, borrowerName: 'PT Client', skuId, skuName: 'ONT', baseUnit: 'EA', tracking: 'SERIAL', stockIdentityId: stockId, quantityBase: '1', serial: 'NEW-001', locationId: id, result: 'REPLACED', supplierId: id, supplierName: 'Vendor', vendorReference: 'RMA', evidence: 'Pengganti diterima', sentAt: base.createdAt, returnedAt: base.updatedAt, replacementAssetId: stockId }
  const fetch = vi.fn(async () => json({ document: claim, timeline: [] })); vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter initialEntries={['/warehouse/warranties?id=' + id]}><WarehouseWarrantiesPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Periksa kelayakan' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Bukti / hasil pemeriksaan' }), { target: { value: 'Sudah diuji' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan' }))
  expect(await screen.findByText('Konfirmasi reset perangkat sebelum mengembalikan ke stok.')).toBeTruthy()
  expect(fetch).toHaveBeenCalledTimes(1)
})
it('loads the immutable issue snapshot and checks permission again before printing', async () => {
  const fetch = vi.fn(async (_url: unknown, _init?: RequestInit) => json({ document: loan, timeline: [] })); vi.stubGlobal('fetch', fetch)
  const print = vi.fn(); vi.stubGlobal('print', print)
  render(<MemoryRouter initialEntries={['/warehouse/loans?id=' + id]}><WarehouseLoansPage /></MemoryRouter>)
  await screen.findByRole('button', { name: 'Berita acara penyerahan' })
  fetch.mockImplementation(async () => json(loan))
  fireEvent.click(screen.getByRole('button', { name: 'Berita acara penyerahan' }))
  await screen.findByRole('button', { name: 'Cetak berita acara' })
  fetch.mockImplementation(async () => json({ message: 'Izin dicabut' }, 403))
  fireEvent.click(screen.getByRole('button', { name: 'Cetak berita acara' }))
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3))
  expect(fetch.mock.calls.slice(1).map(call => String(call[0]))).toEqual(['/api/v2/warehouse/loans/' + id + '/issue-snapshot', '/api/v2/warehouse/loans/' + id + '/issue-snapshot'])
  expect(print).not.toHaveBeenCalled()
})
it('offers partial receipts and displays the linked procurement request', async () => {
  const purchase = { ...base, state: 'PARTIALLY_RECEIVED', supplierId: id, supplierName: 'Vendor', purchaseDate: '2026-11-14', reference: 'PO-001', requestId: stockId, notes: '', lines: [{ id, skuId, skuName: 'Konektor', baseUnit: 'EA', quantityBase: '10', receivedBase: '4', requestLineId: stockId, unitPriceMinor: null }] }
  vi.stubGlobal('fetch', vi.fn(async () => json({ document: purchase, timeline: [] })))
  render(<MemoryRouter initialEntries={['/warehouse/purchases?id=' + id]}><WarehousePurchasesPage /></MemoryRouter>)
  expect(await screen.findByRole('button', { name: 'Terima Konektor' })).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Buka request pengadaan' }).getAttribute('href')).toBe('/warehouse/requests?id=' + stockId)
  expect(screen.getByRole('button', { name: 'Batalkan sisa pesanan' })).toBeTruthy()
})
