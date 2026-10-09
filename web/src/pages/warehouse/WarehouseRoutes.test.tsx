import { render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { readWorkflow } from '@/api/warehouse/reference'
import { WarehouseWorkflowProvider } from './WarehouseWorkflowContext'
import { WarehouseRoutes } from './WarehouseRoutes'

const access = vi.hoisted(() => ({ permissions: new Set<string>() }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: 'user', tenantId: 'tenant' } }) }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => false, hasPermission: (value: string) => access.permissions.has(value) }) }))
vi.mock('@/api/warehouse/reference', () => ({ readWorkflow: vi.fn() }))
vi.mock('./ReferenceCatalogPage', () => ({ ReferenceCatalogPage: () => <h1>Reference catalog</h1> }))
vi.mock('./ReferenceStockPage', () => ({ ReferenceStockPage: () => <h1>Reference stock</h1> }))
vi.mock('./ReferenceMovementsPage', () => ({ ReferenceReceiptsPage: () => <h1>Reference receipts</h1>, ReferenceTransfersPage: () => <h1>Reference transfers</h1> }))
vi.mock('./ReferenceRequestsPage', () => ({ ReferenceRequestsPage: () => <h1>Reference requests</h1> }))
vi.mock('./ReferenceReturnsPage', () => ({ ReferenceReturnsPage: () => <h1>Reference returns</h1> }))
vi.mock('./ReferenceCountsPage', () => ({ ReferenceCountsPage: () => <h1>Reference counts</h1> }))
vi.mock('./WarehouseCountsPage', () => ({ WarehouseCountsPage: () => <h1>Legacy counts</h1> }))
vi.mock('./WarehouseReturnsPage', () => ({ WarehouseReturnsPage: () => <h1>Legacy returns</h1> }))
vi.mock('./WarehouseCatalogPage', () => ({ WarehouseCatalogPage: () => <h1>Legacy catalog</h1> }))
vi.mock('./WarehouseReceiptsPage', () => ({ WarehouseReceiptsPage: () => <h1>Legacy receipts</h1> }))
const read = vi.mocked(readWorkflow)
const reference = { tenantId: 'tenant', epoch: 2, state: 'ENFORCED', workflow: 'REFERENCE', owner: false } as const
function route(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><WarehouseWorkflowProvider><Routes><Route path="/warehouse/*" element={<WarehouseRoutes />} /></Routes></WarehouseWorkflowProvider></MemoryRouter>)
}
beforeEach(() => { vi.clearAllMocks(); access.permissions.clear() })

it('selects reference catalog with default read grants even when mutations are locked', async () => {
  read.mockResolvedValue(reference); access.permissions.add('warehouse.catalog.view')
  route('/warehouse')
  await screen.findByRole('heading', { name: 'Reference catalog' })
  expect(screen.queryByText('Legacy catalog')).toBeNull()
})

it('never opens a legacy receipt writer in the reference workflow even with old grants', async () => {
  read.mockResolvedValue(reference); access.permissions.add('inventory.receipt.view')
  route('/warehouse/receipts')
  await screen.findByRole('alert')
  expect(screen.queryByText('Legacy receipts')).toBeNull()
})

it.each(['receipts', 'transfers'])('opens reference %s with scoped stock read permission', async path => {
  read.mockResolvedValue(reference); access.permissions.add('warehouse.stock.view')
  route('/warehouse/' + path)
  await screen.findByRole('heading', { name: 'Reference ' + path })
})

it('opens scoped reference stock with only the read grant while subscription writes are locked', async () => {
  read.mockResolvedValue(reference); access.permissions.add('warehouse.stock.view')
  route('/warehouse/stock')
  await screen.findByRole('heading', { name: 'Reference stock' })
})

it.each(['LEGACY', 'DRAINING'] as const)('keeps historical catalog readable in %s', async workflow => {
  read.mockResolvedValue({ ...reference, workflow }); access.permissions.add('inventory.sku.view')
  route('/warehouse/catalog')
  await screen.findByRole('heading', { name: 'Legacy catalog' })
  expect(screen.queryByText('Reference catalog')).toBeNull()
})

it('does not redirect an account without any warehouse grant into a protected page', async () => {
  read.mockResolvedValue(reference)
  route('/warehouse')
  await screen.findByRole('alert')
  expect(screen.queryByText('Reference catalog')).toBeNull()
  expect(screen.queryByText('Legacy catalog')).toBeNull()
})

it.each(['/warehouse', '/warehouse/requests'])('lets an own-only technician reach requests at %s', async path => {
  read.mockResolvedValue(reference); access.permissions.add('warehouse.request.own')
  route(path)
  await screen.findByRole('heading', { name: 'Reference requests' })
})

it('denies owner settings to nonowners even with warehouse grants', async () => {
  read.mockResolvedValue(reference); access.permissions.add('warehouse.request.view')
  route('/warehouse/settings')
  await screen.findByRole('alert')
  expect(screen.queryByRole('heading', { name: 'Setelan Gudang' })).toBeNull()
})

it.each(['warehouse.return.own', 'warehouse.return.manage'])('opens reference returns with %s', async permission => {
  read.mockResolvedValue(reference); access.permissions.add(permission)
  route('/warehouse/returns')
  await screen.findByRole('heading', { name: 'Reference returns' })
})

it('never opens legacy returns with an old return grant in reference workflow', async () => {
  read.mockResolvedValue(reference); access.permissions.add('inventory.return.view')
  route('/warehouse/returns')
  await screen.findByRole('alert')
  expect(screen.queryByText('Legacy returns')).toBeNull()
  expect(screen.queryByText('Reference returns')).toBeNull()
})

it('opens reference counts with the count management grant', async () => {
  read.mockResolvedValue(reference); access.permissions.add('warehouse.count.manage')
  route('/warehouse/counts')
  await screen.findByRole('heading', { name: 'Reference counts' })
})

it.each(['inventory.count.view', 'warehouse.stock.view', 'warehouse.request.own'])('denies reference counts with only %s', async permission => {
  read.mockResolvedValue(reference); access.permissions.add(permission)
  route('/warehouse/counts')
  await screen.findByRole('alert')
  expect(screen.queryByText('Legacy counts')).toBeNull()
  expect(screen.queryByText('Reference counts')).toBeNull()
})
