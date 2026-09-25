import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { myContext } from '@/test/myMaterialsFixture'
import { rmaDetailsFixture, rmaFixture, returnIds as id } from '@/test/warehouseReturnFixture'
import { MyMaterialsPage } from './MyMaterialsPage'

const mocks = vi.hoisted(() => ({ actor: '', assign: true, readOnly: false }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (permission: string) => ['workorder.order.field', 'customer.onu.view', 'customer.customer.view'].includes(permission) || (mocks.assign && permission === 'customer.onu.assign') }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: mocks.actor, tenantId: 'test-tenant' }, readOnly: mocks.readOnly }) }))
const root = `/api/v1/warehouse/my-materials/${id.rmaOrder}`
const context = () => ({ ...myContext(), id: id.rmaOrder, code: 'WO-RMA-001', workOrderRevision: 17, field: null })
const received = (serial = 'ONU-001') => rmaDetailsFixture({ ...rmaFixture(), serial, state: 'RECEIVED', revision: 2, locationId: id.field })
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const page = (items: unknown[]) => response({ items, page: 0, size: 10, totalElements: items.length })
const tree = () => <MemoryRouter initialEntries={[`/my-materials?workOrderId=${id.rmaOrder}`]}><MyMaterialsPage /></MemoryRouter>
beforeEach(() => {
  mocks.actor = id.rmaTechnician; mocks.assign = true; mocks.readOnly = false
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
function scan(value: string) {
  const input = screen.getByRole('textbox', { name: 'Serial perangkat' })
  fireEvent.change(input, { target: { value } }); fireEvent.keyDown(input, { key: 'Enter' })
}
async function open(action = 'Terima perangkat servis') {
  fireEvent.click(await screen.findByRole('button', { name: 'Lihat perangkat servis' }))
  fireEvent.click(await screen.findByRole('button', { name: action }))
}
it.each(['ONU-001', 'Onu-001'])('receives and reinstalls original customer serial %s with fresh references and identical lost-response retries', async (serial) => {
  let row = rmaDetailsFixture({ ...rmaFixture(), serial }), acknowledgements = 0, installs = 0, installed = false
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      if (path.endsWith('/acknowledge')) { row = received(serial); if (++acknowledgements === 1) throw new TypeError('lost'); return response(row.handover) }
      if (path.endsWith('/authorize')) return response({ authorizationId: id.plan, revision: 3, operationId: id.issue })
      installed = true; if (++installs === 1) throw new TypeError('lost')
      return response({ assignmentId: id.demandLine, episodeId: id.document, customerId: id.evidence, assetId: id.piece })
    }
    if (path === root) return response(context())
    if (path === `${root}/rmas/${id.rma}`) return response(row)
    if (path.includes('/rmas?')) return page(installed ? [] : [row])
    return page([])
  }); vi.stubGlobal('fetch', fetch); render(tree()); await open()
  scan('WRONG'); fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti penerimaan servis/ }), { target: { value: 'Bukti terima servis' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan servis' })); await screen.findByText('Cocokkan serial fisik dengan perangkat servis ini.')
  expect(acknowledgements).toBe(0)
  scan('ONU-001'); fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan servis' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Milik pelanggan asal')
  expect(acknowledgements).toBe(0)
  fireEvent.click(screen.getByRole('button', { name: 'Terima perangkat' })); fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Pasang kembali perangkat' }))
  scan('ONU-001'); fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemasangan kembali' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Pasang kembali' })); fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await screen.findByText(/Perangkat sudah dipasang kembali/)
  expect(screen.getByRole('link', { name: 'Buka aset pelanggan' }).getAttribute('href')).toBe('/customers')
  expect(screen.queryByRole('button', { name: 'Pasang kembali perangkat' })).toBeNull()
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(5); expect(writes[0]).toEqual(writes[1]); expect(writes[3]).toEqual(writes[4])
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 1, observedSerial: serial, evidenceReference: 'Bukti terima servis' })
  expect(JSON.parse(String(writes[2][1]?.body))).toMatchObject({ expectedRevision: 17, purpose: 'RETURN_CUSTOMER_RMA', previousAssignmentId: id.demandLine, repairCaseId: id.repair })
  expect(JSON.parse(String(writes[3][1]?.body))).toEqual({ authorizationId: id.plan, expectedRevision: 3, topology: null })
  expect(fetch.mock.calls.filter(([path]) => path === `${root}/rmas/${id.rma}`)).toHaveLength(2)
})
it('keeps offline drafts, then rejects a revoked assignment and clears the draft on account switch', async () => {
  let revoked = false
  const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
    if (mocks.actor !== id.rmaTechnician) return response({ message: 'Not found' }, 404)
    if (path === root) return response({ ...context(), currentAssignee: !revoked })
    if (path === `${root}/rmas/${id.rma}`) return response(rmaDetailsFixture())
    return page(path.includes('/rmas?') ? [rmaDetailsFixture()] : [])
  }); vi.stubGlobal('fetch', fetch); const view = render(tree()); await open(); scan('ONU-001')
  fireEvent(window, new Event('offline'))
  fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti penerimaan servis/ }), { target: { value: 'Draf offline' } })
  expect(screen.getByRole('button', { name: 'Tinjau penerimaan servis' })).toHaveProperty('disabled', true)
  revoked = true; fireEvent(window, new Event('online')); fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan servis' }))
  await screen.findByText(/hanya dapat diterima dan dipasang oleh teknisi yang ditugaskan/)
  expect(screen.queryByRole('dialog')).toBeNull()
  mocks.actor = id.supplier; view.rerender(tree())
  await waitFor(() => expect(screen.queryByRole('textbox', { name: /Referensi bukti penerimaan servis/ })).toBeNull())
  expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
it('does not authorize a disappeared source and keeps read-only or unprivileged viewers from reinstalling', async () => {
  const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
    if (path === root) return response(context())
    if (path === `${root}/rmas/${id.rma}`) return response({ message: 'Sumber sudah dipasang' }, 404)
    return page(path.includes('/rmas?') ? [received()] : [])
  }); vi.stubGlobal('fetch', fetch); const view = render(tree()); await open('Pasang kembali perangkat'); scan('ONU-001')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemasangan kembali' })); await screen.findByText('Sumber sudah dipasang')
  expect(screen.queryByRole('dialog')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Batal' })); mocks.assign = false; view.rerender(tree())
  expect(await screen.findByRole('button', { name: 'Pasang kembali perangkat' })).toHaveProperty('disabled', true)
  mocks.assign = true; mocks.readOnly = true; view.rerender(tree())
  expect(screen.getByRole('button', { name: 'Pasang kembali perangkat' })).toHaveProperty('disabled', true)
  expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
