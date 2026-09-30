import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CustomersPage } from './CustomersPage'

const fixtures = vi.hoisted(() => ({ get: vi.fn(), del: vi.fn(), confirm: vi.fn() }))
vi.mock('../api/client', async importOriginal => ({ ...await importOriginal<object>(), api: { get: fixtures.get, del: fixtures.del } }))
vi.mock('../auth/useCan', () => ({ useCan: () => ({ can: () => true }) }))
vi.mock('@/system', () => ({ useConfirm: () => fixtures.confirm, useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('./CustomerDetailPage', () => ({ CustomerDetailBlade: () => null }))

const customer = (id: string) => ({ id, name: `Pelanggan ${id}`, code: id, status: 'ACTIVE', address: 'Bandung', onus: [], location: { longitude: 0, latitude: 0 } })
function RouteLocation() { return <output aria-label="Current route">{useLocation().search}</output> }
function show(path = '/customers') { return render(<MemoryRouter initialEntries={[path]}><CustomersPage /><RouteLocation /></MemoryRouter>) }
async function removeFirst() {
  const user = userEvent.setup()
  await user.click(await screen.findByLabelText('Aksi sel'))
  await user.click(screen.getByRole('menuitem', { name: 'Hapus' }))
  await waitFor(() => expect(fixtures.del).toHaveBeenCalled())
  return user
}

beforeEach(() => { vi.clearAllMocks(); fixtures.confirm.mockResolvedValue(true) })
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
