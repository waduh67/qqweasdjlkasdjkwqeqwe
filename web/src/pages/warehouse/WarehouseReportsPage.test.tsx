import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { reportCostFixture, reportMovementFixture, reportPage, reportPrintFixture } from '@/test/warehouseReportFixture'
import { WarehouseReportsPage } from './WarehouseReportsPage'
const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
function show(path = '/warehouse/reports') { return render(<MemoryRouter initialEntries={[path]}><WarehouseReportsPage /></MemoryRouter>) }
beforeEach(() => { mocks.permissions.clear(); mocks.permissions.add('inventory.report.view'); tokenStore.clear() })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); tokenStore.clear() })

it('uses server pagination and preserves scope in export, then revokes prepared downloads on navigation', async () => {
  const fetch = vi.fn(async (path: string) => path.includes('/export.csv') ? new Response('"name"\r\n"Kabel"\r\n', { headers: { 'Content-Type': 'text/csv' } }) : response(reportPage([{ ...reportMovementFixture(), name: path.includes('page=1') ? 'Kabel halaman kedua' : 'Kabel laporan' }], path.includes('page=1') ? 1 : 0, 1, 2)))
  vi.stubGlobal('fetch', fetch); vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:report'), revokeObjectURL: vi.fn() }))
  show(`/warehouse/reports?locationId=${id.inspection}`)
  await screen.findByText('Kabel laporan'); expect(screen.queryByRole('link', { name: 'Kabel laporan' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' })); await screen.findByText('Kabel halaman kedua')
  fireEvent.click(screen.getByRole('button', { name: 'Siapkan CSV' })); expect((await screen.findByRole('link', { name: 'Unduh CSV' })).getAttribute('download')).toBe('gudang-movements.csv')
  const path = fetch.mock.calls.find(([p]) => p.includes('/export.csv'))![0]
  expect(path).toContain(`locationId=${id.inspection}`); expect(path).not.toContain('page=')
  fireEvent.change(screen.getByRole('combobox', { name: 'Jenis laporan' }), { target: { value: 'stock-card' } })
  await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:report'))
})
it('keeps signed unknown costs explicit and shows exact currency totals only with current cost permission', async () => {
  mocks.permissions.add('inventory.cost.view')
  const known = reportCostFixture(), unknown = { ...known, id: id.supplier, costState: 'UNKNOWN', sourceTotalMinor: null, sourceBasisQuantityBase: null, currency: null, lineTotalMinor: null }
  vi.stubGlobal('fetch', vi.fn(async () => response({ ...reportPage([known, unknown]), scope: 'VISIBLE_LOCATIONS', costBasis: 'OPERATIONAL_USE', currencyTotals: [{ currency: 'IDR', totalMinor: '-743093938516132' }], unknownQuantities: [{ baseUnit: 'MM', quantityBase: '-82500' }] })))
  show(`/warehouse/reports?kind=work-order-costs&workOrderId=${id.source}`)
  await screen.findByText('Biaya belum diketahui'); expect(screen.getByText(/Tidak dihitung sebagai biaya nol/)).toBeTruthy()
  expect(screen.getAllByText(/743.093.938.516.132 IDR/).length).toBeGreaterThan(0)
  expect(screen.getAllByText('-82,500 m').length).toBeGreaterThan(0)
  expect(screen.getAllByRole('link', { name: 'WO-7' })[0].getAttribute('href')).toContain(`workOrderId=${id.source}`)
})
it('previews actual document revision then revalidates before print and hides revoked content', async () => {
  let reads = 0
  const fetch = vi.fn(async (path: string) => {
    if (path.endsWith('/print')) { reads++; return reads === 1 ? response(reportPrintFixture()) : response({ code: 'NOT_FOUND' }, 404) }
    return response(reportPage([reportMovementFixture()]))
  }); vi.stubGlobal('fetch', fetch); const print = vi.spyOn(window, 'print').mockImplementation(() => {})
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Pratinjau dokumen' }))
  await screen.findByText('Pemasok: Pemasok laporan')
  expect(fetch.mock.calls.at(-1)![0]).toBe(`/api/v1/warehouse/reports/documents/${id.document}/revisions/7/print`)
  fireEvent.click(screen.getByRole('button', { name: 'Cetak dokumen' })); await screen.findByRole('button', { name: 'Muat ulang dokumen' })
  expect(print).not.toHaveBeenCalled(); expect(screen.queryByText('Pemasok: Pemasok laporan')).toBeNull()
})
it('prints a freshly authorized revision and removes its temporary print portal', async () => {
  let reads = 0
  vi.stubGlobal('fetch', vi.fn(async () => { reads++; return response(reportPrintFixture()) }))
  const print = vi.spyOn(window, 'print').mockImplementation(() => { expect(document.querySelector('.warehouse-issue-print')?.textContent).toContain('RC-7') })
  show(`/warehouse/reports?documentId=${id.document}&revision=7`)
  fireEvent.click(await screen.findByRole('button', { name: 'Cetak dokumen' }))
  await waitFor(() => expect(print).toHaveBeenCalledOnce()); expect(reads).toBe(2)
  await waitFor(() => expect(document.querySelector('.warehouse-issue-print')).toBeNull())
})
it('gates report and cost access and malformed links before any request, and distinguishes denied loads from empty data', async () => {
  const fetch = vi.fn(async () => response({ code: 'FORBIDDEN' }, 403)); vi.stubGlobal('fetch', fetch)
  let view = show('/warehouse/reports?kind=work-order-costs'); expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy(); view.unmount()
  view = show('/warehouse/reports?locationId='); expect(screen.getByText('Filter atau alamat laporan tidak dikenal.')).toBeTruthy(); view.unmount()
  mocks.permissions.clear(); view = show(); expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled(); view.unmount()
  mocks.permissions.add('inventory.report.view'); show(); await screen.findByText('Data belum berhasil dimuat')
  expect(screen.queryByText('Tidak ada data laporan dalam cakupan ini')).toBeNull()
})
it('shows empty results honestly, blocks oversized export, and validates paired date ranges', async () => {
  const fetch = vi.fn(async (path: string) => response(reportPage([], 0, 25, path.includes('direction=desc') ? 1001 : 0))); vi.stubGlobal('fetch', fetch)
  const view = show(); await screen.findByText('Tidak ada data laporan dalam cakupan ini')
  fireEvent.click(screen.getByText('Rentang tanggal', { exact: true })); fireEvent.change(screen.getByLabelText('Awal periode'), { target: { value: '2026-09-01T00:00:00+07:00' } })
  fireEvent.click(screen.getByRole('button', { name: 'Terapkan periode' })); expect(screen.getByText(/Isi awal dan akhir yang valid/)).toBeTruthy(); expect(fetch).toHaveBeenCalledOnce()
  view.unmount(); show('/warehouse/reports?direction=desc'); await screen.findByText(/Persempit lokasi/)
  expect(screen.getByRole('button', { name: 'Siapkan CSV' })).toHaveProperty('disabled', true)
})
