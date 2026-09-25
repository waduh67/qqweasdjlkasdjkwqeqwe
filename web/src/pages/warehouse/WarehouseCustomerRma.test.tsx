import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { WarehouseDataError } from '@/api/warehouse/codec'
import { rmaDetails, rmaWorkOrder } from '@/api/warehouse/returns'
import { readyRmaReturn, repairTransit, returnIds as id, returnQuarantine, rmaDetailsFixture, rmaField, rmaFixture, rmaOrderFixture } from '@/test/warehouseReturnFixture'
import { WarehouseReturnsPage } from './WarehouseReturnsPage'
import { buildRmaDispatch } from './rmaDraft'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value), user: { id: '', name: 'Petugas penerimaan' } } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: mocks.user }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const page = (items: unknown[]) => ({ items, page: 0, size: 25, totalElements: items.length })
function mockServer(conflict = false) {
  const current = readyRmaReturn()
  let handover = rmaFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      current.references.rmaHandoverId = id.rma
      return conflict ? response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) : response(handover, 201)
    }
    if (path.includes('/rma-work-orders/')) return response(rmaOrderFixture)
    if (path.includes('/rma-handovers/')) return response(rmaDetailsFixture(handover))
    if (path.endsWith('/details')) return response(current)
    if (path.includes('/history/page?')) return response(page([current.returnCase]))
    if (path.includes('/replacement-receipts?')) return response([])
    if (path.includes('/locations?')) return response(page([returnQuarantine, repairTransit, rmaField]))
    if (path.startsWith('/api/work-orders?')) return response({ content: [{ id: id.rmaOrder, code: rmaOrderFixture.code, title: rmaOrderFixture.title, status: 'ASSIGNED', type: 'REPAIR',
      customerId: id.evidence, customerName: 'Pelanggan asal', assignees: [{ id: id.rmaTechnician, name: 'Teknisi RMA' }] }], page: 0, size: 25, totalElements: 1 })
    throw new Error(`Unexpected read ${path}`)
  })
  vi.stubGlobal('fetch', fetch)
  return { fetch, acknowledge: () => { handover = { ...handover, revision: 2, state: 'RECEIVED', locationId: id.field } } }
}
function show() { render(<MemoryRouter initialEntries={[`/warehouse/returns?returnId=${id.returnCase}`]}><WarehouseReturnsPage /></MemoryRouter>) }
async function choose(label: string, value: string) {
  await waitFor(() => expect(screen.getByRole('combobox', { name: label })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value } })
}
async function prepare() {
  fireEvent.click(await screen.findByRole('button', { name: 'Siapkan serah-terima RMA' }))
  await choose('WO perbaikan pelanggan', id.rmaOrder)
  await screen.findByRole('form', { name: 'Serah-terima RMA' })
  await choose('Teknisi penerima RMA', id.rmaTechnician)
  await choose('Transit RMA', id.transit); await choose('Lokasi teknisi RMA', id.field)
  fireEvent.change(screen.getByRole('textbox', { name: 'Serial fisik RMA' }), { target: { value: 'ONU-001' } })
  fireEvent.keyDown(screen.getByRole('textbox', { name: 'Serial fisik RMA' }), { key: 'Enter' })
  expect(screen.queryByRole('dialog')).toBeNull()
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi bukti RMA' }), { target: { value: 'BA-RMA' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pengiriman RMA' }))
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  mocks.permissions.clear()
  for (const permission of ['inventory.return.view', 'inventory.return.manage', 'inventory.location.view', 'inventory.receipt.view', 'workorder.order.view']) mocks.permissions.add(permission)
  mocks.user.id = id.plan; tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('validates named RMA references and captures the actual current work-order revision and recipient', () => {
  expect(rmaDetails(rmaDetailsFixture())).toEqual(rmaDetailsFixture())
  expect(() => rmaDetails({ ...rmaDetailsFixture(), locations: [] })).toThrow(WarehouseDataError)
  expect(() => rmaWorkOrder({ ...rmaOrderFixture, revision: '17' })).toThrow(WarehouseDataError)
  const build = (order = rmaOrderFixture, actor = id.plan, field = rmaField) => buildRmaDispatch(readyRmaReturn(), order, id.rmaTechnician, actor, repairTransit, field, 'ONU-001', 'BA')
  expect(build()).toMatchObject({ expectedRevision: 5, workOrderId: id.rmaOrder, workOrderRevision: 17, technicianId: id.rmaTechnician })
  expect(() => build({ ...rmaOrderFixture, customerId: id.sku })).toThrow('pelanggan asal')
  expect(() => build(rmaOrderFixture, id.rmaTechnician)).toThrow('berbeda dari pengirim')
  expect(() => build(rmaOrderFixture, id.plan, { ...rmaField, custodianId: id.plan })).toThrow('lokasi teknisi')
})
it('accepts matching mixed-case RMA serial while preserving raw history and rejecting another device', () => {
  const details = readyRmaReturn(); details.references.item.serial = 'Onu-001'
  const before = JSON.stringify(details)
  const build = (observed: string) => buildRmaDispatch(details, rmaOrderFixture, id.rmaTechnician, id.plan, repairTransit, rmaField, observed, 'BA')
  expect(build(' onu-001 ').observedSerial).toBe(' onu-001 ')
  for (const wrong of ['', ' ', 'ONU-002']) expect(() => build(wrong)).toThrow('serial perangkat pelanggan yang sama')
  expect(JSON.stringify(details)).toBe(before)
})
it('dispatches only after current named WO review then reads actual handover receipt separately', async () => {
  const server = mockServer(); show()
  await screen.findByRole('button', { name: 'Siapkan serah-terima RMA' })
  expect(screen.queryByRole('button', { name: 'Periksa retur' })).toBeNull()
  await prepare()
  const dialog = await screen.findByRole('dialog', { name: 'Konfirmasi pengiriman RMA' })
  expect(dialog.textContent).toContain('Revisi WO 17'); expect(dialog.textContent).toContain('Penerima: Teknisi RMA'); expect(dialog.textContent).toContain('belum diterima teknisi')
  expect(server.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Kirim RMA ke transit' }))
  const stored = await screen.findByRole('region', { name: 'Serah-terima RMA tersimpan' })
  await within(stored).findByText(/Menunggu konfirmasi penerimaan fisik/)
  expect(stored.textContent).toContain('Teknisi RMA'); expect(stored.textContent).toContain('Milik pelanggan')
  const writes = server.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 5, workOrderId: id.rmaOrder, workOrderRevision: 17, technicianId: id.rmaTechnician,
    transitLocationId: id.transit, technicianLocationId: id.field, observedSerial: 'ONU-001', evidenceReference: 'BA-RMA' })
  const lookup = server.fetch.mock.calls.find(([path]) => path.startsWith('/api/work-orders?'))![0]
  expect(lookup).toContain(`customerId=${id.evidence}`); expect(lookup).toContain('type=REPAIR')
  expect(server.fetch.mock.calls.some(([path]) => path.includes('/materials'))).toBe(false)
  server.acknowledge(); fireEvent.click(within(stored).getByRole('button', { name: 'Muat ulang RMA' }))
  await screen.findByText(/Teknisi sudah mengonfirmasi penerimaan/)
  expect(server.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
it('requires fresh state after dispatch conflict and discovers the saved handover instead of resubmitting', async () => {
  const server = mockServer(true); show(); await prepare()
  fireEvent.click(await screen.findByRole('button', { name: 'Kirim RMA ke transit' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await screen.findByText(/Menunggu konfirmasi penerimaan fisik/)
  expect(screen.queryByRole('form', { name: 'Serah-terima RMA' })).toBeNull()
  expect(server.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
it('explains missing work-order lookup permission without reading an unrelated material summary', async () => {
  mocks.permissions.delete('workorder.order.view'); const server = mockServer(); show()
  expect(await screen.findByRole('button', { name: 'Siapkan serah-terima RMA' })).toHaveProperty('disabled', true)
  expect(screen.getByText(/memerlukan izin lihat work order dan lihat lokasi/)).toBeTruthy()
  expect(server.fetch.mock.calls.some(([path]) => path.startsWith('/api/work-orders'))).toBe(false)
})
