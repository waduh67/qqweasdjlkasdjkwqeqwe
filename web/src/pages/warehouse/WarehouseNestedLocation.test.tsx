import { useState } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'
import { WarehouseWorkflowProvider } from './WarehouseWorkflowContext'
const permission = (name: string) => ['inventory.location.manage', 'inventory.location.view', 'iam.area.view'].includes(name)
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: permission }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { platformAdmin: true, areaIds: [] } }) }))
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
const workflow = { owner: false, snapshot: { tenantId: '2ac7f1ab-d2dc-430c-bfac-b1d01ba3b918', epoch: 0, state: 'LEGACY', workflow: 'LEGACY' } }
afterEach(() => vi.unstubAllGlobals())
function Example() {
  const [child, setChild] = useState(false)
  return <MemoryRouter><WarehouseWorkflowProvider><ResourceForm title="Penerimaan" onClose={() => {}} onBack={() => {}}>
    <input aria-label="Referensi" defaultValue="SJ-KEEP" /><button onClick={() => setChild(true)}>Tambah lokasi</button>
    {child && <WarehouseLocationEditor row={null} readOnly={false} onClose={() => setChild(false)} onReload={() => setChild(false)} onSaved={() => setChild(false)} />}
  </ResourceForm></WarehouseWorkflowProvider></MemoryRouter>
}
it('keeps the location layer while metadata loads and returns focus to the parent', async () => {
  let resolve!: (value: Response) => void
  const areas = new Promise<Response>(done => { resolve = done })
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/areas' ? areas : response(path === '/api/v2/warehouse/workflow' ? workflow : { items: [], page: 0, size: 25, totalElements: 0 })))
  render(<Example />); const user = userEvent.setup(), launcher = screen.getByRole('button', { name: 'Tambah lokasi' })
  await user.click(launcher)
  const loading = await screen.findByRole('dialog', { name: 'Tambah lokasi' })
  await act(async () => { resolve(response([])); await areas })
  const loaded = await screen.findByRole('dialog', { name: 'Tambah lokasi' })
  expect(loaded).toBe(loading)
  await user.click(await within(loaded).findByRole('button', { name: 'Tutup' }))
  await waitFor(() => expect(document.activeElement).toBe(launcher))
  expect(screen.getByRole('textbox', { name: 'Referensi' })).toHaveProperty('value', 'SJ-KEEP')
  await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
})
it('offers only warehouse/bin kinds when creating a parent location inside another location draft', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => response(path === '/api/v2/warehouse/workflow' ? workflow : path === '/api/areas' ? [] : { items: [], page: 0, size: 25, totalElements: 0 })))
  render(<Example />); const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Tambah lokasi' }))
  await user.click(await screen.findByRole('button', { name: 'Tambah lokasi induk' }))
  await waitFor(() => expect(document.querySelectorAll('[data-resource-layer]')).toHaveLength(3))
  const selector = await waitFor(() => within(document.querySelector('[data-resource-layer="2"]') as HTMLElement).getByRole('combobox', { name: 'Jenis lokasi' }))
  expect([...selector.querySelectorAll('option')].map(option => option.value)).toEqual(['WAREHOUSE', 'BIN'])
})
