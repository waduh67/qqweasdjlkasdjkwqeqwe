import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useCallback, useState, type ReactNode } from 'react'
import { Link, Route, Routes } from 'react-router-dom'
import { listApprovals } from '@/api/warehouse/approvals'
import { listStock } from '@/api/warehouse/masters'
import { useCan } from '@/auth/useCan'
import { EmptyState } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseTime } from '@/components/organisms/warehouse/WarehouseLines'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehouseStatus } from '@/components/organisms/warehouse/WarehouseStatus'
import { WAREHOUSE_PAGES, WAREHOUSE_VIEW_PERMISSIONS } from './navigation'
import { WarehouseCatalogPage } from './WarehouseCatalogPage'
import { WarehouseReceiptsPage } from './WarehouseReceiptsPage'
import { WarehouseStockPage } from './WarehouseStockPage'
import { WarehouseRequestsPage } from './WarehouseRequestsPage'

function WarehouseGate({ permissions, children }: { permissions: readonly string[]; children: ReactNode }) {
  const { can } = useCan()
  return permissions.some(can) ? children : <WarehouseDenied />
}

export function WarehouseRoutes() {
  return <Routes>
    <Route index element={<WarehouseGate permissions={WAREHOUSE_VIEW_PERMISSIONS}><WarehouseHome /></WarehouseGate>} />
    {WAREHOUSE_PAGES.map(page => <Route key={page.path} path={page.path} element={<WarehouseGate permissions={page.permissions}>
      {page.path === 'catalog' ? <WarehouseCatalogPage /> : page.path === 'receipts' ? <WarehouseReceiptsPage /> : page.path === 'approvals' ? <ApprovalQueue /> : page.path === 'stock' ? <WarehouseStockPage /> : page.path === 'requests' ? <WarehouseRequestsPage /> : <WarehouseUnavailable />}
    </WarehouseGate>} />)}
    <Route path="*" element={<WarehouseUnavailable />} />
  </Routes>
}

function WarehouseUnavailable() {
  return <div className="card stack" role="alert"><EmptyState title="Halaman gudang tidak tersedia" hint="Alamat ini belum tersedia. Kembali ke ringkasan untuk melihat pekerjaan gudang." /><Link to="/warehouse">Kembali ke ringkasan gudang</Link></div>
}

function WarehouseHome() {
  const { can } = useCan()
  return <div className="stack">
    <PageHeader title="Gudang & Logistik" subtitle="Stok dan pekerjaan sesuai izin serta cakupan gudang Anda." />
    {can('inventory.approval.view') && <PendingApprovals />}
    {can('inventory.item.view') && <StockSummary embedded />}
    {!can('inventory.item.view') && !can('inventory.approval.view') && <p className="muted">Pilih pekerjaan gudang dari menu sesuai izin Anda.</p>}
  </div>
}

const pendingLoader = () => listApprovals({ status: 'PENDING', size: 1 })
function PendingApprovals() {
  const result = useWarehouseQuery(pendingLoader)
  return <WarehouseState {...result}>{data => <section className="card stack" aria-label="Persetujuan menunggu">
    <strong>{data.totalElements} persetujuan menunggu</strong><p className="muted">Permintaan yang dapat Anda lihat dalam cakupan gudang saat ini.</p>
    <Link to="/warehouse/approvals">Buka persetujuan gudang</Link>
  </section>}</WarehouseState>
}

function ApprovalQueue() {
  const [page, setPage] = useState(0)
  const loader = useCallback(() => listApprovals({ page }), [page])
  const result = useWarehouseQuery(loader)
  return <div className="stack"><PageHeader title="Persetujuan Gudang" subtitle="Keputusan membutuhkan pemeriksa yang berbeda dari pembuat permintaan." />
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.requestId} empty={<EmptyState title="Tidak ada persetujuan dalam cakupan Anda" hint="Permintaan yang masuk akan terlihat setelah diajukan untuk pemeriksaan." />} columns={[
        { key: 'document', header: 'Referensi', cell: row => <span style={{ overflowWrap: 'anywhere' }}>{row.code}<br /><span className="muted">Revisi sumber {row.sourceRevision}</span></span> },
        { key: 'status', header: 'Status', cell: row => <WarehouseStatus status={row.status} /> },
        { key: 'expires', header: 'Berlaku sampai', cell: row => <WarehouseTime value={row.expiresAt} /> },
      ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </div>
}

function StockSummary({ embedded = false }: { embedded?: boolean }) {
  const [page, setPage] = useState(0)
  const loader = useCallback(() => listStock({ page }), [page])
  const result = useWarehouseQuery(loader)
  const { can } = useCan()
  return <section className="stack" aria-label="Stok terverifikasi">
    {embedded ? <h2>Stok terverifikasi</h2> : <PageHeader title="Stok & Perangkat" subtitle="Jumlah hanya mencakup lokasi yang dapat Anda akses." />}
    <WarehouseState {...result}>{data => <>
      <DataTable presentation="warehouse" rows={data.items} rowKey={row => row.id} empty={<div className="stack"><EmptyState title="Belum ada stok terverifikasi dalam cakupan Anda" hint="Siapkan lokasi dan barang, lalu catat penerimaan untuk menambah stok." />{can('inventory.location.view') && <Link to="/warehouse/catalog">Siapkan lokasi dan barang</Link>}</div>} columns={[
        { key: 'name', header: 'Barang', cell: row => <span>{row.name}<br /><span className="muted">{row.skuCode}</span></span> },
        { key: 'physical', header: 'Fisik', align: 'right', cell: row => <WarehouseQuantity value={row.physical.quantityBase} unit={row.physical.baseUnit} /> },
        { key: 'reserved', header: 'Dipesan', align: 'right', cell: row => <WarehouseQuantity value={row.reservedUnpicked.quantityBase} unit={row.reservedUnpicked.baseUnit} /> },
        { key: 'picked', header: 'Disiapkan', align: 'right', cell: row => <WarehouseQuantity value={row.reservedPicked.quantityBase} unit={row.reservedPicked.baseUnit} /> },
        { key: 'available', header: 'Tersedia', align: 'right', cell: row => <WarehouseQuantity value={row.available.quantityBase} unit={row.available.baseUnit} /> },
      ]} />
      <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
    </>}</WarehouseState>
  </section>
}
