import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { tokenStore } from '@/api/client'
import { handoverGrantFixture, handoverSourceFixture } from '@/test/materialHandoverFixture'
import { myContext } from '@/test/myMaterialsFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { MyMaterialHandoverGrants } from './MyMaterialHandoverGrants'

const context = () => ({ ...myContext(), workOrderRevision: 9, currentAssignee: false, field: null })
const response = (value: unknown) => new Response(JSON.stringify(value))
beforeEach(() => { HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }; HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') } })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
function reads(path: string, revision = 9) {
  if (path.includes('/pending?')) return response({ items: [handoverGrantFixture()], page: 0, size: 10, totalElements: 1 })
  if (path.includes('/pending/')) return response(handoverGrantFixture())
  if (path.includes('/custody/')) return response(handoverSourceFixture().source)
  return response({ ...context(), workOrderRevision: revision })
}
it('refreshes a sender-owned grant and preserves the exact dispatch through response loss', async () => {
  let writes = 0
  const done = vi.fn(), fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { writes++; if (writes === 1) throw new TypeError('lost'); return response({ id: id.document, workOrderId: id.source, purpose: 'HANDOVER' }) }
    return reads(path)
  }); vi.stubGlobal('fetch', fetch); render(<MyMaterialHandoverGrants context={context()} actor={id.inspection} enabled onDone={done} />)
  expect(fetch).not.toHaveBeenCalled(); fireEvent.click(screen.getByRole('button', { name: 'Lihat persetujuan serah-terima' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Tinjau pengiriman antarteknisi' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Ani penerima')
  fireEvent.click(screen.getByRole('button', { name: 'Kirim sesuai persetujuan' })); fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await waitFor(() => expect(done).toHaveBeenCalledTimes(1))
  const mutations = fetch.mock.calls.filter(([, init]) => init?.method === 'POST'); expect(mutations).toHaveLength(2)
  expect(mutations[0][1]?.body).toBe(mutations[1][1]?.body); expect(JSON.parse(String(mutations[0][1]?.body))).toMatchObject({ authorizationId: id.plan, quantityBase: '7500', usageId: id.evidence })
  expect((mutations[0][1]?.headers as Headers).get('Idempotency-Key')).toBe((mutations[1][1]?.headers as Headers).get('Idempotency-Key'))
})
it('blocks dispatch when assignment changes after the old grant was shown', async () => {
  const fetch = vi.fn(async (path: string) => reads(path, 10)); vi.stubGlobal('fetch', fetch)
  render(<MyMaterialHandoverGrants context={context()} actor={id.inspection} enabled onDone={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Lihat persetujuan serah-terima' })); fireEvent.click(await screen.findByRole('button', { name: 'Tinjau pengiriman antarteknisi' }))
  await screen.findByText('Sumber, penugasan, atau persetujuan berubah. Muat ulang sebelum mengirim.')
  expect(screen.queryByRole('dialog')).toBeNull(); expect(fetch.mock.calls.every(([path]) => !path.endsWith('/handover'))).toBe(true)
})
