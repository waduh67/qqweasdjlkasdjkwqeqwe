import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { custodyFixture } from '@/test/warehouseExecutionFixture'
import { myContext, myIssue, myResidual } from '@/test/myMaterialsFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { MyMaterialsPage } from './MyMaterialsPage'

const mocks = vi.hoisted(() => ({ actor: '', allowed: true }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (permission: string) => mocks.allowed && permission === 'workorder.order.field' }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: mocks.actor, tenantId: 'tenant-one' }, readOnly: false }) }))
const root = `/api/v1/warehouse/my-materials/${id.source}`
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => response({ items, page: 0, size: 25, totalElements: items.length })
const tree = () => <MemoryRouter initialEntries={[`/my-materials?workOrderId=${id.source}`]}><MyMaterialsPage /></MemoryRouter>
beforeEach(() => {
  mocks.actor = id.inspection; mocks.allowed = true
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
async function fillReceipt() {
  fireEvent.click(await screen.findByRole('button', { name: 'Terima barang' }))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang yang diterima' }), { target: { value: id.line } })
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah diterima/ }), { target: { value: '60' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah kurang/ }), { target: { value: '40' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan selisih' }), { target: { value: 'Sisa belum dikirim' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti penerimaan/ }), { target: { value: 'Berita acara penerimaan' } })
}
function defaultRead(path: string) {
  if (path === root) return response(myContext())
  if (path === `${root}/issues/${id.issue}`) return response(myIssue())
  if (path.includes('/issues?')) return page([myIssue()])
  if (path.includes('/custody?')) return page([custodyFixture()])
  if (path.includes('/residuals?')) return page([])
  throw new Error(`Unexpected read: ${path}`)
}
it('acknowledges measured partial receipt after fresh review and preserves the key through response loss', async () => {
  let writes = 0
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { writes++; if (writes === 1) throw new TypeError('lost'); return response({ receiptId: id.document, revision: 3, state: 'PART_RECEIVED' }) }
    return defaultRead(path)
  }); vi.stubGlobal('fetch', fetch); render(tree()); await fillReceipt()
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Diterima 60, kurang 40'); expect(writes).toBe(0)
  fireEvent.click(screen.getByRole('button', { name: 'Terima material' })); fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const mutations = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(mutations).toHaveLength(2); expect(mutations[0][1]?.body).toBe(mutations[1][1]?.body)
  expect(JSON.parse(String(mutations[0][1]?.body))).toMatchObject({ expectedRevision: 2, workOrderRevision: 5, lines: [{ acceptedBase: '60000', missingBase: '40000', stockIdentityId: id.piece }] })
  expect((mutations[0][1]?.headers as Headers).get('Idempotency-Key')).toBe((mutations[1][1]?.headers as Headers).get('Idempotency-Key'))
  expect(fetch.mock.calls.filter(([path]) => path === `${root}/issues/${id.issue}`)).toHaveLength(1)
})
it('keeps offline edits as draft and rejects a revoked assignment when reconnecting before any mutation', async () => {
  let revoked = false
  const fetch = vi.fn(async (path: string) => path === root && revoked ? response({ ...myContext(), currentAssignee: false, field: null }) : defaultRead(path))
  vi.stubGlobal('fetch', fetch); render(tree()); await fillReceipt()
  fireEvent(window, new Event('offline')); expect(screen.getByText(/Offline — perubahan hanya draf/)).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Tinjau penerimaan' })).toHaveProperty('disabled', true)
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah diterima/ }), { target: { value: '59' } })
  revoked = true; fireEvent(window, new Event('online'))
  expect(screen.getByRole('textbox', { name: /Jumlah diterima/ })).toHaveProperty('value', '59')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan' }))
  await screen.findByText('Penerimaan hanya untuk penerima yang masih ditugaskan pada WO aktif.')
  expect(screen.queryByRole('dialog')).toBeNull(); expect(fetch.mock.calls.every(([path]) => !path.endsWith('/acknowledge'))).toBe(true)
})
it('returns 17.5 metres after reassignment using the refreshed physical source and work order revision', async () => {
  const context = { ...myContext(), currentAssignee: false, field: null, workOrderRevision: 9 }, source = { ...custodyFixture(), quantityBase: '17500', sourceUsageId: id.evidence, initialUseSource: false }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response({ id: id.document, workOrderId: id.source, purpose: 'RETURN' })
    if (path === root) return response(context)
    if (path === `${root}/custody/${id.piece}`) return response(source)
    if (path === `${root}/return-locations/${id.allocation}`) return response(myResidual().location)
    if (path.includes('/return-locations?')) return page([myResidual().location])
    if (path.includes('/custody?')) return page([source])
    return page([])
  }); vi.stubGlobal('fetch', fetch); render(tree())
  fireEvent.click(await screen.findByRole('button', { name: 'Kembalikan sisa' })); expect(screen.queryByRole('button', { name: 'Catat pemakaian' })).toBeNull()
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah dikembalikan/ }), { target: { value: '17,500' } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Karantina tujuan' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Karantina tujuan' }), { target: { value: id.allocation } })
  fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti pengembalian/ }), { target: { value: 'Surat kembali' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Alasan pengembalian/ }), { target: { value: 'Sisa selesai dikerjakan' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengembalian' })); fireEvent.click(await screen.findByRole('button', { name: 'Kirim pengembalian' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ workOrderRevision: 9, quantityBase: '17500', stockIdentityId: id.piece, usageId: id.evidence, targetLocationId: id.allocation })
})
it('revalidates actual custody before reporting 82.5 metres and sends no serial consumption fallback', async () => {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response({ usageId: id.evidence, workOrderId: id.source, useRevision: 1 })
    if (path === `${root}/custody/${id.piece}`) return response(custodyFixture())
    return defaultRead(path)
  }); vi.stubGlobal('fetch', fetch); render(tree())
  fireEvent.click(await screen.findByRole('button', { name: 'Catat pemakaian' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Barang diterima 1' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang diterima 1' }), { target: { value: id.piece } })
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah dipakai 1/ }), { target: { value: '82,500' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti pemakaian/ }), { target: { value: 'Ukuran lapangan' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemakaian' }))
  fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Catat pemakaian' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body)).lines[0].quantityBase).toBe('82500')
  expect(fetch.mock.calls.some(([path]) => path === `${root}/custody/${id.piece}`)).toBe(true)
})
it('clears the local draft on account change and denies a foreign actor without retaining prior fields', async () => {
  const fetch = vi.fn(async (path: string) => mocks.actor !== id.inspection ? response({ code: 'NOT_FOUND', message: 'Not found' }, 404) : defaultRead(path))
  vi.stubGlobal('fetch', fetch); const view = render(tree()); await fillReceipt()
  mocks.actor = id.supplier; view.rerender(tree())
  await waitFor(() => expect(screen.queryByRole('textbox', { name: /Referensi bukti penerimaan/ })).toBeNull())
  expect(screen.queryByRole('button', { name: 'Terima material' })).toBeNull()
  expect(fetch.mock.calls.every(([path]) => !path.endsWith('/acknowledge'))).toBe(true)
})
it('requires a matching keyboard scan before returning one unused serialized device', async () => {
  const source = { ...custodyFixture(), quantityBase: '1', baseUnit: 'EA', serial: 'ONU-01', lotCode: null, sku: { ...custodyFixture().sku, name: 'ONU rumah', tracking: 'SERIAL', baseUnit: 'EA' } }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response({ id: id.document, workOrderId: id.source, purpose: 'RETURN' })
    if (path === root) return response(myContext())
    if (path === `${root}/custody/${id.piece}`) return response(source)
    if (path === `${root}/return-locations/${id.allocation}`) return response(myResidual().location)
    if (path.includes('/return-locations?')) return page([myResidual().location])
    if (path.includes('/custody?')) return page([source])
    return page([])
  }); vi.stubGlobal('fetch', fetch); render(tree())
  fireEvent.click(await screen.findByRole('button', { name: 'Kembalikan perangkat' }))
  const serial = screen.getByRole('textbox', { name: 'Serial perangkat' })
  fireEvent.change(serial, { target: { value: 'ONU-02' } }); fireEvent.keyDown(serial, { key: 'Enter' })
  expect(screen.getByText('Serial tidak cocok dengan perangkat yang akan dikembalikan.')).toBeTruthy()
  expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  fireEvent.change(serial, { target: { value: 'ONU-01' } }); fireEvent.keyDown(serial, { key: 'Enter' })
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah dikembalikan/ }), { target: { value: '1' } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Karantina tujuan' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Karantina tujuan' }), { target: { value: id.allocation } })
  fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti pengembalian/ }), { target: { value: 'ONU tidak jadi dipasang' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Alasan pengembalian/ }), { target: { value: 'Perangkat tidak dipakai' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengembalian' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('ONU-01')
  fireEvent.click(screen.getByRole('button', { name: 'Kirim pengembalian' })); await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST'); expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ receiptId: id.document, stockIdentityId: id.piece, quantityBase: '1', baseUnit: 'EA' })
})
