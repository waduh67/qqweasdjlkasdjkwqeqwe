import type { ComponentType, ReactNode } from 'react'
import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { useCan } from '@/auth/useCan'
import { EmptyState } from '@/components/atoms'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WAREHOUSE_PAGES, WAREHOUSE_VIEW_PERMISSIONS } from './navigation'
import { WarehouseCatalogPage } from './WarehouseCatalogPage'
import { WarehouseReceiptsPage } from './WarehouseReceiptsPage'
import { WarehouseStockPage } from './WarehouseStockPage'
import { WarehouseRequestsPage } from './WarehouseRequestsPage'
import { WarehouseTransfersPage } from './WarehouseTransfersPage'
import { WarehouseReturnsPage } from './WarehouseReturnsPage'
import { WarehouseCountsPage } from './WarehouseCountsPage'
import { WarehouseApprovalsPage } from './WarehouseApprovalsPage'
import { WarehouseSettingsPage } from './WarehouseSettingsPage'
import { WarehouseReportsPage } from './WarehouseReportsPage'
import { WarehouseReplenishmentPage } from './WarehouseReplenishmentPage'
import { WarehouseOverviewPage } from './WarehouseOverviewPage'
import { WarehouseProvenancePage } from './WarehouseProvenancePage'
import { ReferenceCatalogPage } from './ReferenceCatalogPage'
import { useWarehouseWorkflow } from './WarehouseWorkflowContext'
import { REFERENCE_WAREHOUSE_PAGES } from './navigation'

const pages = {
  catalog: WarehouseCatalogPage, receipts: WarehouseReceiptsPage, approvals: WarehouseApprovalsPage,
  stock: WarehouseStockPage, requests: WarehouseRequestsPage, replenishment: WarehouseReplenishmentPage,
  transfers: WarehouseTransfersPage, returns: WarehouseReturnsPage, counts: WarehouseCountsPage,
  settings: WarehouseSettingsPage, reports: WarehouseReportsPage, provenance: WarehouseProvenancePage,
} satisfies Record<typeof WAREHOUSE_PAGES[number]['path'], ComponentType>

function WarehouseGate({ permissions, children }: { permissions: readonly string[]; children: ReactNode }) {
  const { hasPermission } = useCan()
  return permissions.some(hasPermission) ? children : <WarehouseDenied />
}

export function WarehouseRoutes() {
  const workflow = useWarehouseWorkflow()
  return <WarehouseState {...workflow}>{data => data.workflow === 'REFERENCE' ? <ReferenceRoutes /> : <LegacyRoutes />}</WarehouseState>
}

function ReferenceRoutes() {
  const { hasPermission } = useCan()
  const first = REFERENCE_WAREHOUSE_PAGES.find(page => page.permissions.some(hasPermission))
  return <Routes>
    <Route index element={first ? <Navigate replace to={first.path} /> : <WarehouseDenied />} />
    <Route path="catalog" element={<WarehouseGate permissions={['warehouse.catalog.view']}><ReferenceCatalogPage /></WarehouseGate>} />
    <Route path="*" element={<WarehouseUnavailable />} />
  </Routes>
}

function LegacyRoutes() {
  return <Routes>
    <Route index element={<WarehouseGate permissions={WAREHOUSE_VIEW_PERMISSIONS}><WarehouseOverviewPage /></WarehouseGate>} />
    {WAREHOUSE_PAGES.map(page => {
      const Page = pages[page.path]
      return <Route key={page.path} path={page.path} element={<WarehouseGate permissions={page.permissions}><Page /></WarehouseGate>} />
    })}
    <Route path="*" element={<WarehouseUnavailable />} />
  </Routes>
}

function WarehouseUnavailable() {
  return <div className="card stack" role="alert"><EmptyState title="Halaman gudang tidak tersedia" hint="Alamat ini belum tersedia. Kembali ke ringkasan untuk melihat pekerjaan gudang." /><Link to="/warehouse">Kembali ke ringkasan gudang</Link></div>
}
