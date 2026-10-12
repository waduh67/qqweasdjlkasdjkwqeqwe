import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import * as catalog from '@/api/catalog'
import * as bng from '@/api/bng'
import { ExpressPsbPanel } from './onboarding/ExpressPsbPanel'

const auth = vi.hoisted(() => ({ permissions: ['customer.customer.create', 'customer.subscription.update', 'bng.access.manage', 'workorder.order.create', 'catalog.plan.view'] }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { platformAdmin: false, permissions: auth.permissions }, readOnly: false }) }))
vi.mock('@/system', () => ({ useConfirm: () => vi.fn(), useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('@/pages/warehouse/WarehouseWorkflowContext', () => ({ useWarehouseWorkflow: () => ({ state: { status: 'ready', data: { workflow: 'REFERENCE' } }, reload: vi.fn() }) }))
vi.mock('@/components/organisms/LocationPicker', () => ({ LocationPicker: () => null }))
const required = [...auth.permissions]
beforeEach(() => {
  vi.restoreAllMocks(); auth.permissions = [...required]
  vi.spyOn(api, 'get').mockResolvedValue([])
  vi.spyOn(bng, 'listNas').mockResolvedValue([])
  vi.spyOn(catalog, 'listPlans').mockResolvedValue([])
})
function open(onClose = vi.fn()) { render(<MemoryRouter><ExpressPsbPanel onClose={onClose} onCreated={vi.fn()} /></MemoryRouter>); return userEvent.setup() }
it('explains the missing plan prerequisite to a non-platform tenant owner and links to setup', async () => {
  open()
  await screen.findByText('Belum ada paket aktif')
  expect(screen.getByText('Tambahkan paket internet untuk mulai mendaftarkan pelanggan.')).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Kelola paket' }).getAttribute('href')).toBe('/catalog')
  expect(screen.queryByText('Akses PSB dibatasi')).toBeNull()
})
it('keeps load failures distinct from empty setup and retries without reloading the page', async () => {
  vi.mocked(catalog.listPlans).mockRejectedValueOnce(new ApiError(503, 'unavailable'))
  const onClose = vi.fn(), user = open(onClose)
  const surface = screen.getByRole('dialog', { name: 'Buat PSB' })
  await screen.findByText('Paket internet belum berhasil dimuat.')
  expect(screen.queryByText('Belum ada paket aktif')).toBeNull()
  vi.mocked(catalog.listPlans).mockResolvedValueOnce([{ id: 'plan-1', active: true, serviceTypes: ['PPPOE'] } as catalog.PlanView])
  await user.click(screen.getByRole('button', { name: 'Coba lagi' }))
  await screen.findByRole('tab', { name: 'Pelanggan' })
  expect(screen.getByRole('dialog', { name: 'Buat PSB' })).toBe(surface)
  await user.click(screen.getByRole('button', { name: 'Batal' }))
  expect(onClose).toHaveBeenCalledTimes(1)
  expect(screen.queryByText('Buang perubahan?')).toBeNull()
  await waitFor(() => expect(catalog.listPlans).toHaveBeenCalledTimes(2))
})
it('requires every permission enforced by the PSB endpoint', async () => {
  auth.permissions = ['customer.customer.create', 'catalog.plan.view']
  open()
  expect(screen.getByRole('alert').textContent).toContain('Akses PSB dibatasi')
  expect(catalog.listPlans).not.toHaveBeenCalled()
})

it('protects an edited draft after loading in the same panel', async () => {
  vi.mocked(catalog.listPlans).mockResolvedValueOnce([{ id: 'plan-1', active: true, serviceTypes: ['PPPOE'] } as catalog.PlanView])
  const onClose = vi.fn(), user = open(onClose)
  const surface = screen.getByRole('dialog', { name: 'Buat PSB' })
  await user.type(await screen.findByRole('textbox', { name: 'Nama' }), 'Budi')
  expect(screen.getByRole('dialog', { name: 'Buat PSB' })).toBe(surface)
  await user.click(screen.getByRole('button', { name: 'Batal' }))
  expect(onClose).not.toHaveBeenCalled()
  await user.click(await screen.findByRole('button', { name: 'Lanjutkan pengisian' }))
  expect(await screen.findByRole('textbox', { name: 'Nama' })).toHaveProperty('value', 'Budi')
  await user.click(screen.getByRole('button', { name: 'Batal' }))
  await user.click(await screen.findByRole('button', { name: 'Buang perubahan' }))
  expect(onClose).toHaveBeenCalledTimes(1)
})
