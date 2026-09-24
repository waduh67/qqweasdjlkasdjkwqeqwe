import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { approvalDetailsFixture } from '@/test/warehouseApprovalFixture'
import { replenishmentFixture } from '@/test/warehouseReplenishmentFixture'
import { returnDetailsFixture } from '@/test/warehouseReturnFixture'
import { reportMovementFixture, reportPage } from '@/test/warehouseReportFixture'
import { WarehouseOperationsPage } from '../WarehouseOperationsPage'
import { WarehouseOverviewPage } from './WarehouseOverviewPage'
const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const shortage = { id: id.sku, skuId: id.sku, name: 'Kabel minimum', skuCode: 'CABLE', baseUnit: 'MM', availableBase: '20001', minimumBase: '50005', shortageBase: '30004' }
function show() { return render(<MemoryRouter><WarehouseOverviewPage /></MemoryRouter>) }
beforeEach(() => { mocks.permissions.clear(); tokenStore.clear() })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('shows exact scoped shortages with server totals and pages and links directly to source work', async () => {
  mocks.permissions.add('inventory.item.view')
  const fetch = vi.fn(async (path: string) => response(reportPage([{ ...shortage, name: path.includes('page=1') ? 'Kabel berikutnya' : shortage.name }], path.includes('page=1') ? 1 : 0, 1, 2))); vi.stubGlobal('fetch', fetch); show()
  const link = await screen.findByRole('link', { name: 'Kabel minimum · CABLE' }); expect(link.getAttribute('href')).toContain(`skuId=${id.sku}`)
  expect(screen.getByText('30,004 m')).toBeTruthy(); expect(screen.getByText('2 barang di bawah minimum')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' })); await screen.findByRole('link', { name: 'Kabel berikutnya · CABLE' })
  expect(fetch.mock.calls.every(([path]) => path.includes('/stock/shortages?'))).toBe(true)
  expect(fetch.mock.calls.at(-1)![0]).toContain('page=1')
})
it('loads only the approval queue for approver-only users through the original warehouse route facade', async () => {
  mocks.permissions.add('inventory.approval.view')
  const approval = approvalDetailsFixture().approval
  const fetch = vi.fn(async () => response(reportPage([approval]))); vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter initialEntries={['/warehouse']}><Routes><Route path="/warehouse/*" element={<WarehouseOperationsPage />} /></Routes></MemoryRouter>)
  const link = await screen.findByRole('link', { name: approval.code }); expect(link.getAttribute('href')).toBe(`/warehouse/approvals?approvalId=${approval.requestId}`)
  expect(fetch).toHaveBeenCalledOnce(); expect(screen.queryByRole('heading', { name: 'Stok di bawah minimum SKU' })).toBeNull()
})
it('connects pending replenishments returns and transit aging without requiring general stock access', async () => {
  for (const permission of ['inventory.request.view', 'inventory.return.view', 'inventory.report.view']) mocks.permissions.add(permission)
  const replenishment = replenishmentFixture(), returned = returnDetailsFixture()
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/replenishments/')) return response(reportPage([{ request: replenishment.request, sku: replenishment.sku, location: replenishment.location }]))
    if (path.includes('/returns/')) return response(reportPage(path.includes('state=REPAIR') ? [] : [returned]))
    return response(reportPage([{ ...reportMovementFixture(), enteredAt: '2026-09-21T17:00:00Z', ageSeconds: '259200' }]))
  }); vi.stubGlobal('fetch', fetch); show()
  expect((await screen.findByRole('link', { name: 'Kabel pengisian' })).getAttribute('href')).toContain(`requestId=${id.document}`)
  const returns = await screen.findByRole('region', { name: 'Retur menunggu pemeriksaan' })
  expect((await within(returns).findByRole('link', { name: `${returned.references.code} · ${returned.references.item.name}` })).getAttribute('href')).toContain(`returnId=${returned.returnCase.id}`)
  expect(await screen.findByText('3 hari')).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Laporan perjalanan barang' }).getAttribute('href')).toContain('sort=createdAt')
  expect(fetch.mock.calls.some(([path]) => path.includes('/replenishments/') && path.includes('state=PENDING'))).toBe(true)
  expect(fetch.mock.calls.some(([path]) => path.includes('/stock'))).toBe(false)
})
it('keeps a failed panel distinct from an empty success and leaves other authorized work usable', async () => {
  mocks.permissions.add('inventory.item.view'); mocks.permissions.add('inventory.approval.view')
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path.includes('/stock/') ? response({ code: 'FORBIDDEN' }, 403) : response(reportPage([]))))
  show(); await screen.findByText('Data belum berhasil dimuat'); await screen.findByText('Tidak ada persetujuan menunggu dalam cakupan ini.')
  expect(screen.queryByText('0 barang di bawah minimum')).toBeNull(); expect(screen.getByRole('link', { name: 'Buka persetujuan gudang' })).toBeTruthy()
})
it('offers authorized setup routes on empty results and sends no requests with no warehouse permission', async () => {
  const fetch = vi.fn(async () => response(reportPage([]))); vi.stubGlobal('fetch', fetch)
  let view = show(); expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled(); view.unmount()
  mocks.permissions.add('inventory.location.view'); view = show(); expect(screen.getByRole('link', { name: 'Katalog & Lokasi' })).toBeTruthy(); expect(fetch).not.toHaveBeenCalled(); view.unmount()
  mocks.permissions.add('inventory.item.view'); mocks.permissions.add('inventory.receipt.view'); show()
  await screen.findByText('Tidak ada barang di bawah minimum dalam cakupan ini'); expect(screen.getByRole('link', { name: 'Buka penerimaan barang' })).toBeTruthy()
})
