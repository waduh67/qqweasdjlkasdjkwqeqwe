import { useState } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'
const permission = (name: string) => ['inventory.location.manage', 'inventory.location.view', 'iam.area.view'].includes(name)
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: permission }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { platformAdmin: true, areaIds: [] } }) }))
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => vi.unstubAllGlobals())
function Example() {
  const [child, setChild] = useState(false)
  return <MemoryRouter><ResourceForm title="Penerimaan" onClose={() => {}} onBack={() => {}}>
    <input aria-label="Referensi" defaultValue="SJ-KEEP" /><button onClick={() => setChild(true)}>Tambah lokasi</button>
    {child && <WarehouseLocationEditor row={null} readOnly={false} onClose={() => setChild(false)} onReload={() => setChild(false)} onSaved={() => setChild(false)} />}
  </ResourceForm></MemoryRouter>
}
it('returns focus to the parent after deferred metadata replaces the loading layer', async () => {
  let resolve!: (value: Response) => void
  const areas = new Promise<Response>(done => { resolve = done })
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/areas' ? areas : response({ items: [], page: 0, size: 25, totalElements: 0 })))
  render(<Example />); const user = userEvent.setup(), launcher = screen.getByRole('button', { name: 'Tambah lokasi' })
  await user.click(launcher); await screen.findByRole('dialog', { name: 'Lokasi' })
  await act(async () => { resolve(response([])); await areas })
  const loaded = await screen.findByRole('dialog', { name: 'Tambah lokasi' })
  await user.click(within(loaded).getByRole('button', { name: 'Tutup' }))
  await waitFor(() => expect(document.activeElement).toBe(launcher))
  expect(screen.getByRole('textbox', { name: 'Referensi' })).toHaveProperty('value', 'SJ-KEEP')
  await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
})
it('offers only warehouse/bin kinds when creating a parent location inside another location draft', async () => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => response(path === '/api/areas' ? [] : { items: [], page: 0, size: 25, totalElements: 0 })))
  render(<Example />); const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Tambah lokasi' }))
  await user.click(await screen.findByRole('button', { name: 'Tambah lokasi induk' }))
  await waitFor(() => expect(document.querySelectorAll('[data-resource-layer]')).toHaveLength(3))
  const selector = await waitFor(() => within(document.querySelector('[data-resource-layer="2"]') as HTMLElement).getByRole('combobox', { name: 'Jenis lokasi' }))
  expect([...selector.querySelectorAll('option')].map(option => option.value)).toEqual(['WAREHOUSE', 'BIN'])
})
