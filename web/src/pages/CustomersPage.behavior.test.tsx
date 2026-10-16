import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CustomersPage } from './CustomersPage'
import { ExpressPsbPage } from './ExpressPsbPage'
import { PSB_PERMISSIONS } from './onboarding/useExpressPsb'

const fixtures = vi.hoisted(() => ({ get: vi.fn(), del: vi.fn(), confirm: vi.fn(), denied: [] as string[], plans: vi.fn() }))
vi.mock('../api/client', async importOriginal => ({ ...await importOriginal<object>(), api: { get: fixtures.get, del: fixtures.del } }))
vi.mock('../auth/useCan', () => ({ useCan: () => ({ can: (permission: string) => !fixtures.denied.includes(permission) }) }))
vi.mock('@/system', () => ({ useConfirm: () => fixtures.confirm, useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('./CustomerDetailPage', () => ({ CustomerDetailBlade: () => null }))
vi.mock('@/api/catalog', async importOriginal => ({ ...await importOriginal<object>(), listPlans: fixtures.plans }))
vi.mock('@/api/bng', () => ({ listNas: async () => [] }))
vi.mock('@/pages/warehouse/WarehouseWorkflowContext', () => ({ useWarehouseWorkflow: () => ({ state: { status: 'ready', data: { workflow: 'REFERENCE' } }, reload: vi.fn() }) }))
vi.mock('@/components/organisms/LocationPicker', () => ({ LocationPicker: () => null }))

const customer = (id: string) => ({ id, name: `Pelanggan ${id}`, code: id, status: 'ACTIVE', address: 'Bandung', onus: [], location: { longitude: 0, latitude: 0 } })
function RouteLocation() { return <output aria-label="Current route">{useLocation().search}</output> }
function show(path = '/customers') { return render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/customers" element={<CustomersPage />} /><Route path="/express-psb" element={<ExpressPsbPage />} /></Routes><RouteLocation /></MemoryRouter>) }
async function removeFirst() {
  const user = userEvent.setup()
  await user.click(await screen.findByLabelText('Aksi sel'))
  await user.click(screen.getByRole('menuitem', { name: 'Hapus' }))
  await waitFor(() => expect(fixtures.del).toHaveBeenCalled())
  return user
}

beforeEach(() => { vi.clearAllMocks(); fixtures.denied = []; fixtures.confirm.mockResolvedValue(true); fixtures.plans.mockResolvedValue([{ id: 'plan-1', active: true, serviceTypes: ['PPPOE'] }]) })
describe('customer navigation after mutations', () => {
  it('returns to a valid page after deleting the last row on page two', async () => {
    let deleted = false
    fixtures.get.mockImplementation(async (url: string) => {
      const page = new URL(url, 'http://local').searchParams.get('page')
      return { content: page === '1' ? deleted ? [] : [customer('51')] : [customer('1')], totalElements: deleted ? 50 : 51 }
    })
    fixtures.del.mockImplementation(async () => { deleted = true })
    show('/customers?page=1')
    await removeFirst()
    expect(await screen.findByRole('button', { name: 'Pelanggan 1' })).toBeTruthy()
    expect(screen.getByLabelText('Current route').textContent).not.toContain('page=1')
    expect(screen.getByText('50 hasil · 1–50')).toBeTruthy()
  })

  it('refreshes the current page when a deletion started on another page completes', async () => {
    let completeDelete!: () => void
    fixtures.del.mockReturnValue(new Promise<void>(resolve => { completeDelete = resolve }))
    fixtures.get.mockImplementation(async (url: string) => ({
      content: [customer(new URL(url, 'http://local').searchParams.get('page') === '1' ? '51' : '1')], totalElements: 51,
    }))
    show()
    const user = await removeFirst()
    await user.click(screen.getByRole('button', { name: 'Berikutnya' }))
    await screen.findByRole('button', { name: 'Pelanggan 51' })
    await act(async () => completeDelete())
    await waitFor(() => expect(fixtures.get).toHaveBeenCalledTimes(3))
    expect(await screen.findByRole('button', { name: 'Pelanggan 51' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Pelanggan 1' })).toBeNull()
    expect(screen.getByLabelText('Current route').textContent).toContain('page=1')
    expect(fixtures.get.mock.lastCall?.[0]).toContain('page=1')
  })
})

describe('PSB entry from customers', () => {
  beforeEach(() => { fixtures.get.mockImplementation(async (url: string) => url.startsWith('/api/customers')
    ? { content: [customer('1')], totalElements: 1 } : []) })

  it('opens beside customer migration; loads setup on demand, and closes without reloading the list', async () => {
    const user = userEvent.setup()
    show('/customers?q=Pelanggan&status=ACTIVE')
    await screen.findByRole('button', { name: 'Pelanggan 1' })
    const toolbar = screen.getByRole('toolbar', { name: 'Aksi' })
    expect([...toolbar.querySelectorAll('button')].slice(0, 2).map(button => button.textContent)).toEqual(['PSB baru', 'Migrasi pelanggan'])
    expect(fixtures.plans).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'PSB baru' }))
    await screen.findByRole('tab', { name: 'Pelanggan' })
    expect(screen.getByLabelText('Current route').textContent).toBe('?q=Pelanggan&status=ACTIVE&create=psb')
    await user.click(screen.getByRole('button', { name: 'Batal' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByLabelText('Current route').textContent).toBe('?q=Pelanggan&status=ACTIVE')
    expect(screen.getByPlaceholderText('Cari nama, kode, alamat, atau telepon…')).toHaveProperty('value', 'Pelanggan')
    expect(fixtures.get.mock.calls.filter(([url]) => String(url).startsWith('/api/customers'))).toHaveLength(1)
  })

  it.each(PSB_PERMISSIONS)('does not offer PSB without %s', async permission => {
    fixtures.denied = [permission]
    show()
    await screen.findByRole('button', { name: 'Pelanggan 1' })
    expect(screen.queryByRole('button', { name: 'PSB baru' })).toBeNull()
    expect(fixtures.plans).not.toHaveBeenCalled()
  })

  it('offers migration without installation or RADIUS management permissions', async () => {
    fixtures.denied = ['bng.access.manage', 'workorder.order.create']
    show()
    await screen.findByRole('button', { name: 'Pelanggan 1' })
    expect(screen.queryByRole('button', { name: 'PSB baru' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Migrasi pelanggan' })).toBeTruthy()
  })

  it('opens a legacy bookmark directly over the customer list and preserves its filters on close', async () => {
    show('/express-psb?q=Pelanggan&status=ACTIVE')
    await screen.findByRole('tab', { name: 'Pelanggan' })
    await userEvent.click(screen.getByRole('button', { name: 'Batal' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('button', { name: 'Pelanggan 1' })).toBeTruthy()
    expect(screen.getByLabelText('Current route').textContent).toBe('?q=Pelanggan&status=ACTIVE')
  })
})
