import type { ComponentType, ReactNode } from 'react'
import { Link, Route, Routes } from 'react-router-dom'
import { useCan } from '@/auth/useCan'
import { EmptyState } from '@/components/atoms'
import { WarehouseDenied } from '@/components/organisms/warehouse/WarehouseState'
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

const pages = {
  catalog: WarehouseCatalogPage, receipts: WarehouseReceiptsPage, approvals: WarehouseApprovalsPage,
  stock: WarehouseStockPage, requests: WarehouseRequestsPage, replenishment: WarehouseReplenishmentPage,
  transfers: WarehouseTransfersPage, returns: WarehouseReturnsPage, counts: WarehouseCountsPage,
  settings: WarehouseSettingsPage, reports: WarehouseReportsPage,
} satisfies Record<typeof WAREHOUSE_PAGES[number]['path'], ComponentType>

function WarehouseGate({ permissions, children }: { permissions: readonly string[]; children: ReactNode }) {
  const { can } = useCan()
  return permissions.some(can) ? children : <WarehouseDenied />
}

export function WarehouseRoutes() {
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
