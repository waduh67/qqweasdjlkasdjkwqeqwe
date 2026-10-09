import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { readWorkflow } from '@/api/warehouse/reference'
import { WarehouseWorkflowProvider, useWarehouseWorkflow } from './WarehouseWorkflowContext'
import { WorkflowSurface } from './WorkflowSurface'

const account = vi.hoisted(() => ({ user: { id: 'user-a', tenantId: 'tenant-a' } }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => account }))
vi.mock('@/api/warehouse/reference', () => ({ readWorkflow: vi.fn() }))
const read = vi.mocked(readWorkflow)
const snapshot = { tenantId: 'tenant-a', epoch: 2, state: 'ENFORCED', workflow: 'REFERENCE', owner: true } as const

function Consumer({ name }: { readonly name: string }) {
  const { state, reload } = useWarehouseWorkflow()
  return <div><output aria-label={name}>{state.status === 'ready' ? state.data.workflow : state.status}</output><button onClick={reload}>Retry {name}</button></div>
}
const surface = <WarehouseWorkflowProvider><Consumer name="menu" /><Consumer name="route" /></WarehouseWorkflowProvider>
beforeEach(() => { vi.clearAllMocks(); account.user = { id: 'user-a', tenantId: 'tenant-a' } })

it('shares one server workflow between navigation and the route across profile refresh', async () => {
  read.mockResolvedValue(snapshot)
  const view = render(surface)
  await waitFor(() => expect(screen.getByLabelText('route').textContent).toBe('REFERENCE'))
  expect(screen.getByLabelText('menu').textContent).toBe('REFERENCE')
  account.user = { ...account.user }
  view.rerender(<WarehouseWorkflowProvider><Consumer name="menu" /><Consumer name="route" /></WarehouseWorkflowProvider>)
  expect(read).toHaveBeenCalledTimes(1)
})

it('discards a late workflow when the signed-in account or tenant changes', async () => {
  const pending: ((value: typeof snapshot) => void)[] = []
  read.mockImplementation(() => new Promise(resolve => pending.push(resolve)))
  const view = render(surface)
  account.user = { id: 'user-b', tenantId: 'tenant-b' }
  view.rerender(<WarehouseWorkflowProvider><Consumer name="menu" /><Consumer name="route" /></WarehouseWorkflowProvider>)
  await act(async () => { pending[0]?.(snapshot) })
  expect(screen.getByLabelText('route').textContent).toBe('loading')
  await act(async () => { pending[1]?.(snapshot) })
  expect(screen.getByLabelText('menu').textContent).toBe('REFERENCE')
  expect(read).toHaveBeenCalledTimes(2)
})

it('keeps both consumers closed on a failed read until an explicit retry succeeds', async () => {
  read.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(snapshot)
  render(surface)
  await waitFor(() => expect(screen.getByLabelText('route').textContent).toBe('error'))
  await act(async () => { screen.getByRole('button', { name: 'Retry route' }).click() })
  await waitFor(() => expect(screen.getByLabelText('menu').textContent).toBe('REFERENCE'))
})

it('mounts neither writer while loading or failed, and only the selected writer after retry', async () => {
  read.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(snapshot)
  const legacy = vi.fn(() => <p>Legacy writer</p>), reference = vi.fn(() => <p>Reference writer</p>)
  const Legacy = legacy, Reference = reference
  render(<WarehouseWorkflowProvider><WorkflowSurface legacy={<Legacy />} reference={<Reference />} /></WarehouseWorkflowProvider>)
  expect(legacy).not.toHaveBeenCalled()
  expect(reference).not.toHaveBeenCalled()
  await screen.findByRole('alert')
  expect(legacy).not.toHaveBeenCalled()
  expect(reference).not.toHaveBeenCalled()
  await act(async () => { screen.getByRole('button', { name: 'Coba lagi' }).click() })
  await screen.findByText('Reference writer')
  expect(legacy).not.toHaveBeenCalled()
})
