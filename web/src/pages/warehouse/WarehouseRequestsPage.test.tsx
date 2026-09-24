import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { materialIds as id, allocationFixture, materialSummaryFixture, materialTotalsFixture, materialWorkOrderFixture, issueRowFixture, issueSlipFixture } from '@/test/warehouseMaterialFixture'
import { WarehouseRequestsPage } from './WarehouseRequestsPage'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, size = 25, totalElements = items.length) => ({ items, page: number, size, totalElements })
const root = `/api/work-orders/${id.source}/materials`
function show(path = `/warehouse/requests?workOrderId=${id.source}`) { return render(<MemoryRouter initialEntries={[path]}><WarehouseRequestsPage /></MemoryRouter>) }
function reader(path: string, summary: unknown = materialSummaryFixture, allocations: unknown[] = [allocationFixture], issues: unknown[] = []) {
  if (path === root) return response(summary)
  if (path === `/api/work-orders/${id.source}`) return response({ workOrder: materialWorkOrderFixture })
  if (path.includes('/allocations/')) return response(allocations)
  if (path.includes('/history?')) return response(page([]))
  if (path.includes('/issues?')) return response(page(issues))
  if (path.endsWith('/slip')) return response(issueSlipFixture)
  throw new Error(`Unexpected request ${path}`)
}
beforeEach(() => { mocks.permissions.clear(); for (const permission of ['inventory.request.view', 'inventory.request.manage', 'inventory.issue.view', 'inventory.issue.manage', 'inventory.sku.view', 'workorder.order.view', 'workorder.order.update']) mocks.permissions.add(permission); tokenStore.clear() })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('requires both request and work-order visibility before reading any scoped data', () => {
  mocks.permissions.delete('inventory.request.view')
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); show()
  expect(screen.getByText('Akses permintaan dibatasi')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
})

it('pages named work orders on the server and keeps assigned technician names visible', async () => {
  const fetch = vi.fn(async (path: string) => response({ content: [{ ...materialWorkOrderFixture, code: path.includes('page=1') ? 'WO-LAST' : 'WO-FIRST' }], page: path.includes('page=1') ? 1 : 0, size: 1, totalElements: 2 }))
  vi.stubGlobal('fetch', fetch); show('/warehouse/requests')
  await screen.findByRole('link', { name: 'WO-FIRST · Penarikan kabel' }); expect(screen.getByText('Budi Teknisi')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await screen.findByRole('link', { name: 'WO-LAST · Penarikan kabel' })
  expect(fetch.mock.calls.at(-1)![0]).toContain('page=1'); expect(fetch.mock.calls.at(-1)![0]).toContain('size=25')
})

it('reserves an exact partial quantity using the actual demand line and reloads after a stale rejection', async () => {
  let rejected = false
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { rejected = true; return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) }
    return reader(path, rejected ? { ...materialSummaryFixture, demandRevision: 3 } : materialSummaryFixture, [{ ...allocationFixture, documentRevision: rejected ? 3 : 2 }])
  }); vi.stubGlobal('fetch', fetch); show()
  await screen.findByText(/Masih ada kekurangan material/)
  expect(screen.getByText('40,000 m')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Reservasi sebagian / pilih stok' }))
  fireEvent.click(screen.getByRole('checkbox', { name: 'Cadangkan Kabel drop' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Reservasi Kabel drop (m)' }), { target: { value: '25,125' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau reservasi' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Cadangkan pilihan' }))
  await screen.findByRole('button', { name: 'Muat ulang dokumen' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(writes[0][0]).toBe(`/api/v1/warehouse/material-requests/${id.document}/reserve`)
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ expectedRevision: 2, workOrderRevision: 5, planRevision: 1, lines: [{ demandLineId: id.demandLine, partialQuantityBase: '25125' }] })
  fireEvent.click(screen.getByRole('button', { name: 'Muat ulang dokumen' }))
  await screen.findByRole('heading', { name: 'WO-MATERIAL · Penarikan kabel' })
  expect(screen.queryByRole('textbox', { name: 'Reservasi Kabel drop (m)' })).toBeNull()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})

it('picks the selected reel with actual reservation and stock revisions then discovers its stored slip', async () => {
  let picked = false
  const after = { ...materialSummaryFixture, demandRevision: 3, lines: [{ ...materialTotalsFixture, reservedUnpickedBase: '0', reservedPickedBase: '60000' }] }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { picked = true; return response(issueSlipFixture) }
    return reader(path, picked ? after : materialSummaryFixture, [{ ...allocationFixture, documentRevision: picked ? 3 : 2, reservedUnpickedBase: picked ? '0' : '60000', reservedPickedBase: picked ? '60000' : '0' }], picked ? [issueRowFixture] : [])
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Siapkan barang' }))
  expect(screen.getByText(/Rak A/)).toBeTruthy()
  fireEvent.click(screen.getByRole('checkbox', { name: 'Pilih REEL-1' }))
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pilihan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Siapkan pilihan' }))
  await screen.findByRole('button', { name: 'ISS-MATERIAL' })
  const write = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
  expect(write[0]).toBe(`${root}/pick`)
  expect(JSON.parse(String(write[1]?.body))).toMatchObject({ expectedRevision: 1, workOrderRevision: 5, demandRevision: 2, lines: [{ reservationId: id.supplier, expectedRevision: 3, stockIdentityId: id.piece, stockRevision: 4, quantityBase: '60000', baseUnit: 'MM' }] })
})

it('requires named receiver and partial confirmation before dispatch and does not equate dispatch with receipt', async () => {
  let dispatched = false
  const picked = { ...materialSummaryFixture, demandRevision: 3, lines: [{ ...materialTotalsFixture, reservedUnpickedBase: '0', reservedPickedBase: '60000' }] }
  const sent = { ...picked, demandRevision: 4, demandState: 'PART_ISSUED', lines: [{ ...materialTotalsFixture, reservedUnpickedBase: '0', reservedPickedBase: '0', issuedBase: '60000', stillAccountableBase: '60000' }] }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { dispatched = true; return response({ ...issueSlipFixture, state: 'DISPATCHED', revision: 2 }) }
    return reader(path, dispatched ? sent : picked, [], [{ ...issueRowFixture, ...(dispatched ? { state: 'DISPATCHED', revision: 2, lines: [{ ...issueRowFixture.lines[0], pickedBase: '0', dispatchedBase: '60000', acceptedBase: '0' }] } : {}) }])
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'ISS-MATERIAL' }))
  const detail = within(await screen.findByRole('region', { name: 'Detail slip pengeluaran' }))
  expect(detail.getByRole('button', { name: 'Kirim barang' })).toHaveProperty('disabled', true)
  fireEvent.change(detail.getByRole('textbox', { name: 'Catatan pengiriman / pembatalan' }), { target: { value: 'Kiriman pertama untuk Budi' } })
  fireEvent.click(detail.getByRole('checkbox', { name: 'Konfirmasi penerima: Budi Teknisi' }))
  expect(detail.getByRole('button', { name: 'Kirim barang' })).toHaveProperty('disabled', true)
  fireEvent.click(detail.getByRole('checkbox', { name: 'Kirim sebagian; sisa kebutuhan masih harus dipenuhi' }))
  fireEvent.click(detail.getByRole('button', { name: 'Kirim barang' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Konfirmasi kirim' }))
  await screen.findByText('Dikirim', { selector: '.badge' }).catch(() => undefined)
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Detail slip pengeluaran' })).toBeNull())
  const write = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
  expect(write[0]).toBe(`${root}/dispatch`)
  expect(JSON.parse(String(write[1]?.body))).toEqual({ issueId: id.issue, expectedRevision: 1, workOrderRevision: 5, planRevision: 1, demandRevision: 3, partial: true, reason: 'Kiriman pertama untuk Budi' })
  const issues = within(screen.getByRole('region', { name: 'Slip pengeluaran' }))
  expect(issues.getByText('60,000 m')).toBeTruthy(); expect(issues.getAllByText('0,000 m')).toHaveLength(2)
})

it('blocks stale allocations and distinguishes access failure from an empty successful request', async () => {
  const fetch = vi.fn(async (path: string) => reader(path, materialSummaryFixture, [{ ...allocationFixture, documentRevision: 9 }]))
  vi.stubGlobal('fetch', fetch); const view = show()
  await screen.findByText(/Alokasi berubah saat halaman dimuat/)
  expect(screen.getByRole('button', { name: 'Siapkan barang' })).toHaveProperty('disabled', true)
  view.unmount(); fetch.mockImplementation(async () => response({ code: 'NOT_FOUND', message: 'NOT_FOUND' }, 404)); show()
  await screen.findByText('Data belum berhasil dimuat')
  expect(screen.queryByText('Rencana material belum disusun.')).toBeNull()
})

it('keeps actual accepted quantities visible while historical dispatch slips cannot be dispatched again', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path.endsWith('/slip') ? response({ ...issueSlipFixture, state: 'DISPATCHED', revision: 2 }) : reader(path, { ...materialSummaryFixture, lines: [{ ...materialTotalsFixture, reservedUnpickedBase: '0', issuedBase: '60000', stillAccountableBase: '60000' }] }, [], [{ ...issueRowFixture, state: 'PART_RECEIVED', revision: 3, lines: [{ ...issueRowFixture.lines[0], pickedBase: '0', dispatchedBase: '60000', acceptedBase: '40000' }] }])))
  show(); fireEvent.click(await screen.findByRole('button', { name: 'ISS-MATERIAL' }))
  await screen.findByRole('region', { name: 'Detail slip pengeluaran' })
  expect(screen.queryByRole('button', { name: 'Kirim barang' })).toBeNull()
  expect(within(screen.getByRole('region', { name: 'Slip pengeluaran' })).getByText('40,000 m')).toBeTruthy()
})

it('rejects invalid or duplicate work-order addresses before loading an unfiltered list', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); show(`/warehouse/requests?workOrderId=${id.source}&workOrderId=${id.sku}`)
  expect(screen.getByText('Alamat permintaan tidak dikenal.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
})
