import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { tokenStore } from '@/api/client'
import { myResidual } from '@/test/myMaterialsFixture'
import { WarehousePendingMaterialReturns } from './WarehousePendingMaterialReturns'

vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ readOnly: false }) }))
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('loads the scoped inbox on request and acknowledges its actual revision before inspection intake', async () => {
  let acknowledged = false
  const fetch = vi.fn(async (_path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { acknowledged = true; return new Response(JSON.stringify({ documentId: myResidual().id, state: 'RECEIVED_IN_INSPECTION' })) }
    return new Response(JSON.stringify({ items: acknowledged ? [] : [myResidual()], page: 0, size: 25, totalElements: acknowledged ? 0 : 1 }))
  }); vi.stubGlobal('fetch', fetch); render(<WarehousePendingMaterialReturns />)
  expect(fetch).not.toHaveBeenCalled(); fireEvent.click(screen.getByRole('button', { name: 'Lihat sisa menunggu penerimaan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Akui penerimaan sisa' }))
  fireEvent.change(screen.getByRole('textbox', { name: /Bukti penerimaan serah-terima/ }), { target: { value: 'Surat petugas gudang' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau serah-terima' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('masih perlu dibuatkan penerimaan retur')
  fireEvent.click(screen.getByRole('button', { name: 'Akui penerimaan' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST'); expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ documentId: myResidual().id, expectedRevision: 1, evidenceReference: 'Surat petugas gudang' })
})
