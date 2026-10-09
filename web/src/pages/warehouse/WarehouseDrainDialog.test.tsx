import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { readWorkflow } from '@/api/warehouse/reference'
import { startWarehouseDrain } from '@/api/warehouse/referenceWorkflow'
import { WarehouseDrainDialog } from './WarehouseDrainDialog'

vi.mock('@/api/warehouse/reference', () => ({ readWorkflow: vi.fn() }))
vi.mock('@/api/warehouse/referenceWorkflow', () => ({ startWarehouseDrain: vi.fn() }))
const snapshot = { tenantId: 'tenant', epoch: 2, state: 'ENFORCED', workflow: 'LEGACY' } as const
beforeEach(() => vi.resetAllMocks())

it('checks an uncertain drain result without resending or dismissing, then resolves the changed workflow', async () => {
  const user = userEvent.setup(), done = vi.fn(), close = vi.fn()
  vi.mocked(startWarehouseDrain).mockRejectedValue(new TypeError('Lost response'))
  vi.mocked(readWorkflow).mockRejectedValueOnce(new TypeError('Offline')).mockResolvedValueOnce({ ...snapshot, workflow: 'DRAINING', epoch: 3, owner: true })
  render(<WarehouseDrainDialog snapshot={snapshot} disabled={false} onClose={close} onDone={done} />)
  await user.click(screen.getByRole('button', { name: 'Mulai perpindahan' }))
  await screen.findByRole('button', { name: 'Periksa hasil perpindahan' })
  expect(screen.getByRole('button', { name: 'Batal' }).hasAttribute('disabled')).toBe(true)
  await user.keyboard('{Escape}')
  expect(close).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Periksa hasil perpindahan' }))
  await screen.findByRole('alert')
  expect(done).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Periksa hasil perpindahan' }))
  await waitFor(() => expect(done).toHaveBeenCalledTimes(1))
  expect(startWarehouseDrain).toHaveBeenCalledTimes(1)
})
it('permits an explicit new drain only after a read confirms the original legacy epoch', async () => {
  const user = userEvent.setup(), done = vi.fn()
  vi.mocked(startWarehouseDrain).mockRejectedValueOnce(new TypeError('Lost response')).mockResolvedValueOnce({ ...snapshot, epoch: 3, workflow: 'DRAINING' })
  vi.mocked(readWorkflow).mockResolvedValue({ ...snapshot, owner: true })
  render(<WarehouseDrainDialog snapshot={snapshot} disabled={false} onClose={vi.fn()} onDone={done} />)
  await user.click(screen.getByRole('button', { name: 'Mulai perpindahan' }))
  await user.click(await screen.findByRole('button', { name: 'Periksa hasil perpindahan' }))
  const submit = await screen.findByRole('button', { name: 'Mulai perpindahan' })
  expect(startWarehouseDrain).toHaveBeenCalledTimes(1)
  await user.click(submit)
  await waitFor(() => expect(done).toHaveBeenCalledTimes(1))
  expect(startWarehouseDrain).toHaveBeenCalledTimes(2)
  expect(startWarehouseDrain).toHaveBeenLastCalledWith(2)
})
