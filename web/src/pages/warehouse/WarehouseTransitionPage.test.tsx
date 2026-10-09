import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import { readWorkflow } from '@/api/warehouse/reference'
import { reviewWarehouseActivation } from '@/api/warehouse/referenceWorkflow'
import { WarehouseWorkflowProvider } from './WarehouseWorkflowContext'
import { WarehouseTransitionPage } from './WarehouseTransitionPage'

const auth = vi.hoisted(() => ({ readOnly: false }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'owner', tenantId: 'tenant' }, readOnly: auth.readOnly }) }))
vi.mock('@/api/warehouse/reference', () => ({ readWorkflow: vi.fn() }))
vi.mock('@/api/warehouse/referenceWorkflow', () => ({ reviewWarehouseActivation: vi.fn() }))
vi.mock('./WarehouseActivationEditor', () => ({ WarehouseActivationEditor: ({ onDone }: { onDone: () => void }) => <button onClick={onDone}>Reload rejected activation</button> }))
const workflow = { tenantId: 'tenant', epoch: 2, state: 'ENFORCED', workflow: 'DRAINING', owner: true } as const
const ready = { tenantId: 'tenant', expectedEpoch: 2, reviewHash: 'a'.repeat(64), issues: [], balances: 5, segments: 3, claims: 2, documents: 1, documentStates: [{ state: 'PUTAWAY', count: 1 }] }
function show() {
  return render(<MemoryRouter><WarehouseWorkflowProvider><WarehouseTransitionPage /></WarehouseWorkflowProvider></MemoryRouter>)
}
beforeEach(() => { vi.resetAllMocks(); auth.readOnly = false; vi.mocked(readWorkflow).mockResolvedValue(workflow); vi.mocked(reviewWarehouseActivation).mockResolvedValue(ready) })

it('denies nonowners before reading activation data', async () => {
  vi.mocked(readWorkflow).mockResolvedValue({ ...workflow, owner: false })
  show()
  await screen.findByText('Akses gudang dibatasi')
  expect(reviewWarehouseActivation).not.toHaveBeenCalled()
})

it('keeps locked readiness readable while disabling activation', async () => {
  auth.readOnly = true
  show()
  const activation = await screen.findByRole('button', { name: 'Aktifkan alur gudang baru' })
  expect(activation.hasAttribute('disabled')).toBe(true)
  expect(screen.getByText(/1 dokumen · 5 posisi stok/)).toBeTruthy()
})

it('shows actionable and unknown blockers without exposing activation', async () => {
  vi.mocked(reviewWarehouseActivation).mockResolvedValue({ ...ready, issues: ['OPEN_LEGACY_DOCUMENTS', 'FUTURE_SERVER_BLOCKER'] })
  show()
  await screen.findByText('Dokumen gudang masih berjalan')
  expect(screen.getByRole('link', { name: 'Penerimaan' }).getAttribute('href')).toBe('/warehouse/receipts')
  expect(screen.getByText(/FUTURE_SERVER_BLOCKER/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Aktifkan alur gudang baru' })).toBeNull()
})

it.each([{ tenantId: 'another-tenant' }, { expectedEpoch: 3 }])('requires status reload when the server review belongs to changed state %j', async change => {
  vi.mocked(reviewWarehouseActivation).mockResolvedValue({ ...ready, ...change })
  show()
  await screen.findByText(/Status perpindahan sudah berubah/)
  expect(screen.queryByRole('button', { name: 'Aktifkan alur gudang baru' })).toBeNull()
})

it('discards rejected review and reloads workflow before allowing a fresh activation', async () => {
  show()
  fireEvent.click(await screen.findByRole('button', { name: 'Aktifkan alur gudang baru' }))
  vi.mocked(readWorkflow).mockResolvedValue({ ...workflow, epoch: 3 })
  vi.mocked(reviewWarehouseActivation).mockResolvedValue({ ...ready, expectedEpoch: 3, reviewHash: 'b'.repeat(64) })
  fireEvent.click(screen.getByRole('button', { name: 'Reload rejected activation' }))
  await waitFor(() => expect(readWorkflow).toHaveBeenCalledTimes(2))
  await screen.findByRole('button', { name: 'Aktifkan alur gudang baru' })
  expect(screen.queryByRole('button', { name: 'Reload rejected activation' })).toBeNull()
  expect(reviewWarehouseActivation).toHaveBeenCalledTimes(2)
})

it('links activated owners to archive without rereading an activation review', async () => {
  vi.mocked(readWorkflow).mockResolvedValue({ ...workflow, workflow: 'REFERENCE' })
  show()
  expect((await screen.findByRole('link', { name: 'Baca arsip gudang' })).getAttribute('href')).toBe('/warehouse/archive')
  expect(reviewWarehouseActivation).not.toHaveBeenCalled()
})
